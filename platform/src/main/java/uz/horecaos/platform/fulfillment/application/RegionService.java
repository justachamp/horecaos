package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionGeography;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionRow;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Authoring the geography a geocoder is allowed to answer inside (ADR 0037,
 * ADR 0104).
 *
 * <p>A region is a code, a tri-lingual name, a centre and a SW/NE bounding
 * box, and the box is the whole point of the row: V0025's own comment says an
 * unconstrained geocoder asked for a Tashkent street name "will return a
 * plausible street of the same name in another country", and {@link
 * ServiceZoneService#activate} refuses a polygon outside its region's box —
 * the check that catches a transposed latitude, where "the geometry is valid,
 * it is simply somewhere else".
 *
 * <p>Before ADR 0104 the table's only writer in this repository was test SQL.
 * That made the guard inert in production: with no region there is nothing to
 * check against, so every zone activated anywhere passed the box check by
 * default.
 *
 * <p>A region is archived, never deleted. {@code
 * fulfillment.service_zone_versions.region_id} points at it and a months-old
 * fee resolution's evidence must not turn into a dangling id.
 */
@Service
public class RegionService {

    /** {@code display_name_*} and a translation row all fit this. */
    static final int MAX_NAME_LENGTH = 200;

    private final JdbcRegionStore store;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final BrandLocaleLookup brandLocales;

    @Autowired
    public RegionService(
            JdbcRegionStore store,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor,
            BrandLocaleLookup brandLocales) {
        this.store = store;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
        this.brandLocales = brandLocales;
    }

    /** A service that treats every tenant as sitting on the platform locale fallback (row 10.12). */
    public RegionService(JdbcRegionStore store, Clock clock, AuditRecorder audit, CurrentActor currentActor) {
        this(store, clock, audit, currentActor, BrandLocaleLookup.platformFallback());
    }

    /** This tenant's regions and the platform's, the platform's first. */
    @Transactional(readOnly = true)
    public List<RegionRow> list(UUID tenantId) {
        return store.list(tenantId);
    }

    /**
     * {@link #list} with each region's name merged from the platform columns and
     * the per-locale table, each locale once (row 10.12). A platform region has no
     * translation rows, so it answers its columns alone.
     */
    @Transactional(readOnly = true)
    public List<RegionView> listWithNames(UUID tenantId) {
        Map<UUID, Map<String, String>> translations = store.translationsForTenant(tenantId);
        return store.list(tenantId).stream()
                .map(row -> new RegionView(row, mergedNames(row, translations.getOrDefault(row.regionId(), Map.of()))))
                .toList();
    }

    /**
     * The locale set the region editor offers. A region belongs to the tenant, not
     * to a brand, so it is edited in the union of the tenant's brands' locales --
     * see {@link TenantLocaleSet}.
     */
    public TenantLocaleSet localeSet(UUID tenantId) {
        return brandLocales.tenantLocaleSet(tenantId);
    }

    /** A region and its merged names. */
    public record RegionView(RegionRow row, Map<String, String> names) {}

    @Transactional
    public UUID create(UUID tenantId, RegionGeography requested) {
        refuseBadGeography(requested);
        // Row 10.12: the tenant's default language is the one name a region must have;
        // the three platform columns are NOT NULL, so a triple locale not supplied is
        // filled with it.
        Map<String, String> names = suppliedNames(requested);
        String defaultLocale = brandLocales.tenantLocaleSet(tenantId).defaultLocale();
        String defaultName = names.get(defaultLocale);
        if (defaultName == null) {
            throw new RegionRefusedException(
                    List.of("A region needs its name in the tenant's default language (%s)".formatted(defaultLocale)));
        }
        RegionGeography geography = requested.withTriple(
                names.getOrDefault(LocalizedLabels.RU, defaultName),
                names.getOrDefault(LocalizedLabels.UZ_LATN, defaultName),
                names.getOrDefault(LocalizedLabels.EN, defaultName));
        UUID id = Ids.newId();
        Instant now = clock.instant();
        store.insert(id, tenantId, geography, now);
        store.upsertTranslations(tenantId, id, names, now);

        audit.record(AuditFact.of("delivery.region.created", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("Region", id)
                .because("Registered region '%s'".formatted(geography.code()))
                // Staff 9.3a: a freshly inserted region has no prior state.
                .changed(ChangeDocuments.created(withNames(boxOf(geography), geography, names)))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return id;
    }

    /**
     * Rewrites one of this tenant's own regions under its expected version.
     *
     * <p>A platform region reaches the update as a row this tenant does not own,
     * the read below finds it foreign, and the refusal is not-found rather than
     * forbidden — the same choice {@code DeliveryTariffService.requireOwned}
     * makes, and for the same reason: a forbidden here would confirm that the id
     * names a real platform row.
     *
     * <p>Reads the row first, mirroring {@code LegalEntityService.transition}:
     * the read is what tells not-found (missing, foreign, platform, archived)
     * apart from a stale write, so the conditional update below only ever fails
     * for the second reason — a concurrent editor's write already landed
     * between the read and this one, which is {@code STALE_VERSION}
     * (ADR 0031), not {@code RESOURCE_NOT_FOUND}.
     *
     * @throws ServiceZoneService.DeliveryResourceNotFoundException no active
     *         region this tenant owns exists at that id
     * @throws uz.horecaos.platform.web.api.ApiException {@code STALE_VERSION}
     *         when the row has moved on since {@code expectedVersion} was read
     */
    @Transactional
    public void update(UUID tenantId, UUID regionId, RegionGeography geography, int expectedVersion) {
        refuseBadGeography(geography);
        RegionRow current = store.find(tenantId, regionId).orElse(null);
        if (current == null || current.platform() || !"ACTIVE".equals(current.status())) {
            throw new ServiceZoneService.DeliveryResourceNotFoundException("No active region " + regionId
                    + " this tenant may edit. A tenant may edit its " + "own regions and not the platform's");
        }
        // Row 10.12: only the locales the request names are written; a name it leaves
        // out is kept, so an editor that shows only some languages never deletes the rest.
        Map<String, String> names = suppliedNames(geography);
        Map<String, String> existingTranslations = store.translationsFor(tenantId, regionId);
        Instant now = clock.instant();
        if (store.update(
                        tenantId,
                        regionId,
                        geography.withTriple(
                                names.get(LocalizedLabels.RU),
                                names.get(LocalizedLabels.UZ_LATN),
                                names.get(LocalizedLabels.EN)),
                        expectedVersion,
                        now)
                != 1) {
            throw ApiException.staleVersion(expectedVersion, current.version());
        }
        store.upsertTranslations(tenantId, regionId, names, now);

        Map<String, Object> before = boxOf(current);
        Map<String, Object> after = boxOf(geography);
        names.forEach((locale, name) -> {
            before.put("displayName." + locale, currentName(current, existingTranslations, locale));
            after.put("displayName." + locale, name);
        });
        audit.record(AuditFact.of("delivery.region.updated", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("Region", regionId)
                .because("Rewrote region '%s'".formatted(geography.code()))
                // Staff 9.3a: every field genuinely moves from the region's
                // prior geography (read above, before the write) to the new one.
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /** What the region was called in this locale before the write: the column for the triple, else its translation row. */
    private static @Nullable String currentName(RegionRow region, Map<String, String> translationRows, String locale) {
        return switch (locale) {
            case LocalizedLabels.RU -> region.displayNameRu();
            case LocalizedLabels.UZ_LATN -> region.displayNameUz();
            case LocalizedLabels.EN -> region.displayNameEn();
            default -> translationRows.get(locale);
        };
    }

    private static Map<String, String> suppliedNames(RegionGeography geography) {
        try {
            return LocalizedLabels.supplied(
                    geography.displayNameRu(),
                    geography.displayNameUz(),
                    geography.displayNameEn(),
                    geography.names(),
                    MAX_NAME_LENGTH);
        } catch (IllegalArgumentException invalid) {
            throw new RegionRefusedException(List.of(invalid.getMessage()));
        }
    }

    private static Map<String, String> mergedNames(RegionRow row, Map<String, String> translationRows) {
        return LocalizedLabels.merge(row.displayNameRu(), row.displayNameUz(), row.displayNameEn(), translationRows);
    }

    private static Map<String, Object> withNames(
            Map<String, Object> box, RegionGeography geography, Map<String, String> names) {
        Map<String, Object> changed = new LinkedHashMap<>(box);
        changed.put("displayNameRu", geography.displayNameRu());
        changed.put("displayNameUz", geography.displayNameUz());
        changed.put("displayNameEn", geography.displayNameEn());
        names.forEach((locale, name) -> {
            if (!LocalizedLabels.PLATFORM_TRIPLE.contains(locale)) {
                changed.put("displayName." + locale, name);
            }
        });
        return changed;
    }

    /**
     * Archives one of this tenant's own regions.
     *
     * <p>Zone versions that already name it keep naming it, and keep being
     * checked against its box — an archived region is one nobody may pick again,
     * not one whose geography is withdrawn from the evidence that used it. The
     * count of those versions rides in the audit fact so the operator's own
     * record says what the archive left standing.
     */
    @Transactional
    public void archive(UUID tenantId, UUID regionId) {
        Instant now = clock.instant();
        long stillNaming = store.zoneVersionsNaming(tenantId, regionId);
        if (store.archive(tenantId, regionId, now) != 1) {
            throw new ServiceZoneService.DeliveryResourceNotFoundException(
                    "No active region " + regionId + " this tenant may archive");
        }

        audit.record(AuditFact.of("delivery.region.archived", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("Region", regionId)
                .because("Archived region %s".formatted(regionId))
                // Staff 9.3a: "status" genuinely moves from ACTIVE (this
                // store's own archive() only ever affects an active region)
                // to ARCHIVED.
                .changed(ChangeDocuments.diff(
                        Map.of("status", "ACTIVE"),
                        Map.of("status", "ARCHIVED", "zoneVersionsStillNaming", stillNaming)))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /**
     * Reproduces {@code ck_region_coordinates}, {@code ck_region_bbox_oriented}
     * and {@code ck_region_centre_within_bbox} as sentences, and collects every
     * failure rather than throwing on the first.
     *
     * <p>The constraints are still the authority — they hold when the writer is
     * not this service. What this adds is an answer an operator can act on: a
     * driver-level check-constraint violation names {@code
     * ck_region_centre_within_bbox} and not which of the six numbers is wrong.
     */
    private static void refuseBadGeography(RegionGeography geography) {
        List<String> problems = new ArrayList<>();
        if (outOfRange(geography.centreLat(), 90)
                || outOfRange(geography.bboxSwLat(), 90)
                || outOfRange(geography.bboxNeLat(), 90)) {
            problems.add("A latitude must be between -90 and 90");
        }
        if (outOfRange(geography.centreLon(), 180)
                || outOfRange(geography.bboxSwLon(), 180)
                || outOfRange(geography.bboxNeLon(), 180)) {
            problems.add("A longitude must be between -180 and 180");
        }
        if (geography.bboxNeLat() <= geography.bboxSwLat() || geography.bboxNeLon() <= geography.bboxSwLon()) {
            // V0025: a degenerate box marks every geocode LOW_CONFIDENCE and an
            // inverted one marks none, and neither raises an error anywhere.
            problems.add("The north-east corner must be north and east of the south-west corner. "
                    + "A box that is inverted or has no area accepts everything or nothing, and "
                    + "both fail silently");
        }
        if (!problems.isEmpty()) {
            throw new RegionRefusedException(problems);
        }
        // Checked after the box itself, because "the centre is outside the box"
        // is not a useful sentence about a box that is inside out.
        if (geography.centreLat() < geography.bboxSwLat()
                || geography.centreLat() > geography.bboxNeLat()
                || geography.centreLon() < geography.bboxSwLon()
                || geography.centreLon() > geography.bboxNeLon()) {
            throw new RegionRefusedException(List.of("The centre falls outside its own bounding box, which "
                    + "would put the map editor's opening view outside the area it is meant to be "
                    + "drawing in"));
        }
    }

    private static boolean outOfRange(double value, double limit) {
        return value < -limit || value > limit;
    }

    /**
     * The geography as an audit {@code changed} map.
     *
     * <p>A bounding box is geography, not personal data (ADR 0029): it is the
     * outline of a city, chosen by the operator, and it is the thing being
     * decided. Nothing here names a person or an address.
     */
    private static Map<String, Object> boxOf(RegionGeography geography) {
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("code", geography.code());
        changed.put("swLat", geography.bboxSwLat());
        changed.put("swLon", geography.bboxSwLon());
        changed.put("neLat", geography.bboxNeLat());
        changed.put("neLon", geography.bboxNeLon());
        return changed;
    }

    /** {@link #boxOf(RegionGeography)}'s same shape, read from a stored row for {@link #update}'s before side. */
    private static Map<String, Object> boxOf(RegionRow region) {
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("code", region.code());
        changed.put("swLat", region.bboxSwLat());
        changed.put("swLon", region.bboxSwLon());
        changed.put("neLat", region.bboxNeLat());
        changed.put("neLon", region.bboxNeLon());
        return changed;
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /** Carries every reason at once, the shape {@code ZoneActivationRefusedException} established. */
    public static final class RegionRefusedException extends RuntimeException {

        private final List<String> problems;

        public RegionRefusedException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }
}
