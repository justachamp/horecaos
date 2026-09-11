package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Authoring zones and moving their versions through the lifecycle (ADR 0037).
 *
 * <p>Editing never mutates a live version. Every change to geometry, priority,
 * tariff binding or threshold produces a new one, because a payout dispute six
 * weeks from now asks whether <em>that</em> address was inside <em>that</em>
 * polygon, and today's geometry cannot answer it. Operators who expect to nudge a
 * boundary and save will find this annoying; ADR 0037 accepts that explicitly.
 */
@Service
public class ServiceZoneService {

    /**
     * The largest polygon anyone may activate, in square metres — two thousand
     * square kilometres.
     *
     * <p>What this actually stops is a slip of the drawing tool that encloses the
     * country, which containment tests accept happily and which turns every
     * address in Uzbekistan into a serviceable one at a single branch's district
     * price. Tashkent proper is around 335 km², so the limit is generous by a
     * factor of six for any real city zone and still four hundred times too small
     * for the accident.
     *
     * <p>A constant rather than an ADR 0030 configuration key, for now, and
     * deliberately: adding a key is a release either way, and the number has no
     * per-tenant meaning until somebody produces a zone this refuses that should
     * have been allowed.
     */
    public static final double MAX_ZONE_AREA_SQUARE_METERS = 2_000_000_000d;

    private final JdbcServiceZoneStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;

    public ServiceZoneService(
            JdbcServiceZoneStore store,
            ObjectMapper objectMapper,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
    }

    @Transactional
    public UUID createZone(
            UUID tenantId, UUID brandId, ZoneRole role, String code, String nameRu, String nameUz, String nameEn) {
        UUID zoneId = UUID.randomUUID();
        Instant now = clock.instant();
        store.insertZone(zoneId, tenantId, brandId, role, code, nameRu, nameUz, nameEn, now);

        audit.record(AuditFact.of("delivery.zone.registered", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ServiceZone", zoneId)
                .because("Registered a %s zone lineage '%s'".formatted(role, code))
                .changed(Map.of("role", role.name(), "code", code))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return zoneId;
    }

    /**
     * A circle drawn around a branch — the shape the legacy {@code max_distance}
     * import produces, and the one most operators reach for first.
     *
     * <p>The branch has to be located before this is allowed to succeed. That check
     * is here and not only in the resolver, because a zone built from an unlocated
     * branch is a zone drawn around the wrong place: a null coordinate has no
     * centre at all, and (0, 0) produces a perfectly valid circle in the Gulf of
     * Guinea that no test complains about and no customer is ever inside. Refusing
     * at authoring time is the only point at which the operator is still looking
     * at the screen that can fix it.
     *
     * @throws BranchOrigin.UnlocatedBranchException naming which of the two
     *                                               failures it was, and what to do
     */
    @Transactional
    public DraftedVersion draftCircleVersion(NewVersion request, UUID originLocationId, int radiusMeters) {

        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("A circle needs a positive radius");
        }
        BranchOrigin origin = store.findBranch(request.tenantId(), originLocationId)
                .orElseThrow(() ->
                        new DeliveryResourceNotFoundException("No location " + originLocationId + " for this tenant"))
                .origin();

        int version = store.nextVersion(request.tenantId(), request.zoneId());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();

        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("kind", "CIRCLE");
        shape.put("centreLat", origin.point().latitude());
        shape.put("centreLon", origin.point().longitude());
        shape.put("radiusMeters", radiusMeters);
        shape.put("originLocationId", originLocationId.toString());

        store.insertCircleVersion(
                draft(request, id, version, originLocationId, now),
                origin.point(),
                radiusMeters,
                objectMapper.writeValueAsString(shape));
        auditVersionDrafted(request, version, "CIRCLE", now);
        return new DraftedVersion(id, request.zoneId(), version);
    }

    /** A hand-drawn polygon, taken as GeoJSON exactly as the map editor emits it. */
    @Transactional
    public DraftedVersion draftPolygonVersion(NewVersion request, String geoJson) {
        int version = store.nextVersion(request.tenantId(), request.zoneId());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();

        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("kind", "POLYGON");
        shape.put("geoJson", geoJson);

        store.insertPolygonVersion(
                draft(request, id, version, null, now), geoJson, objectMapper.writeValueAsString(shape));
        auditVersionDrafted(request, version, "POLYGON", now);
        return new DraftedVersion(id, request.zoneId(), version);
    }

    private void auditVersionDrafted(NewVersion request, int version, String shapeKind, Instant now) {
        audit.record(AuditFact.of("delivery.zone.version.drafted", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(request.tenantId(), request.brandId()))
                .target("ServiceZone", request.zoneId())
                .targetVersion((long) version)
                .because("Drafted %s version %d of zone %s".formatted(shapeKind, version, request.zoneId()))
                .changed(Map.of(
                        "shape", shapeKind,
                        "priority", request.priority(),
                        "currency", request.currency()))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /**
     * Activates a draft, or refuses with every reason at once.
     *
     * <p>Every problem is collected rather than thrown on the first, so an operator
     * fixing a zone is shown the whole list instead of discovering it one failed
     * attempt at a time.
     */
    @Transactional
    public void activate(UUID tenantId, UUID brandId, UUID zoneId, int version, UUID actorId) {
        if (store.zoneRole(tenantId, brandId, zoneId).isEmpty()) {
            throw new DeliveryResourceNotFoundException("No zone " + zoneId + " for this brand");
        }

        VersionStatus status = store.versionStatus(tenantId, zoneId, version)
                .orElseThrow(() ->
                        new DeliveryResourceNotFoundException("Zone %s has no version %d".formatted(zoneId, version)));
        if (status != VersionStatus.DRAFT) {
            throw new ZoneActivationRefusedException(
                    List.of("Only a DRAFT version can be activated; this one is " + status));
        }

        var facts = store.geometryFacts(tenantId, zoneId, version)
                .orElseThrow(() ->
                        new DeliveryResourceNotFoundException("Zone %s has no version %d".formatted(zoneId, version)));

        List<String> problems = new ArrayList<>();
        if (!facts.validRings()) {
            // A self-intersecting ring makes containment genuinely undefined:
            // PostGIS will answer, and the answer differs between predicates.
            problems.add("The polygon's rings self-intersect: " + facts.invalidReason());
        }
        if (facts.areaSquareMeters() > MAX_ZONE_AREA_SQUARE_METERS) {
            problems.add(("This polygon covers %.0f km², above the %.0f km² limit. A zone this "
                            + "size is almost always a drawing slip, and activating one stops nothing "
                            + "and serves everything.")
                    .formatted(facts.areaSquareMeters() / 1_000_000d, MAX_ZONE_AREA_SQUARE_METERS / 1_000_000d));
        }
        if (facts.hasRegion() && !facts.withinRegion()) {
            problems.add("The polygon falls outside its region's bounding box, which is what a "
                    + "latitude and longitude transposition looks like: the geometry is valid, "
                    + "it is simply somewhere else.");
        }
        if (!problems.isEmpty()) {
            throw new ZoneActivationRefusedException(problems);
        }

        Instant now = clock.instant();
        if (store.activateVersion(tenantId, zoneId, version, actorId, now) != 1) {
            // Lost a race with another activation of the same zone. A conflict and
            // not a fault: the caller re-reads and decides.
            throw new ZoneActivationRefusedException(
                    List.of("This version was activated or withdrawn by someone else"));
        }

        audit.record(AuditFact.of("delivery.zone.version.activated", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ServiceZone", zoneId)
                .targetVersion((long) version)
                .because("Activated version %d of zone %s".formatted(version, zoneId))
                .changed(Map.of("version", version))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /**
     * The zone's role, which decides what a new version of it is allowed to carry.
     *
     * <p>Read rather than taken from the request, because the role is a property of
     * the lineage and a version that disagreed with its own zone would be refused
     * by {@code fk_zone_version_zone} anyway — as a driver error at the end of a
     * transaction rather than as an answer anybody can act on.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ZoneRole> roleOf(UUID tenantId, UUID brandId, UUID zoneId) {
        return store.zoneRole(tenantId, brandId, zoneId);
    }

    @Transactional
    public void bindLocation(UUID tenantId, UUID brandId, UUID zoneId, UUID locationId) {
        Instant now = clock.instant();
        store.bindLocation(tenantId, brandId, zoneId, locationId, now);

        audit.record(AuditFact.of("delivery.zone.location.bound", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ServiceZone", zoneId)
                .because("Bound location %s to zone %s".formatted(locationId, zoneId))
                .changed(Map.of("locationId", locationId.toString()))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /**
     * Retires the live version of a zone and activates nothing in its place
     * (ADR 0101).
     *
     * <p>The zone then covers nothing. That is the point, and it is ADR 0037's
     * own safe direction — a zone that covers nothing is visibly inert, and an
     * operator who has just discovered a wrong radius wants the wrong radius to
     * stop applying now, not to stay live until a corrected one is drawn.
     *
     * @throws ZoneActivationRefusedException when the named version is not the
     *                                        live one, which includes the case
     *                                        where someone else retired it first
     */
    @Transactional
    public void deactivate(UUID tenantId, UUID brandId, UUID zoneId, int version) {
        if (store.zoneRole(tenantId, brandId, zoneId).isEmpty()) {
            throw new DeliveryResourceNotFoundException("No zone " + zoneId + " for this brand");
        }
        VersionStatus status = store.versionStatus(tenantId, zoneId, version)
                .orElseThrow(() ->
                        new DeliveryResourceNotFoundException("Zone %s has no version %d".formatted(zoneId, version)));
        if (status != VersionStatus.ACTIVE) {
            throw new ZoneActivationRefusedException(
                    List.of("Only the live version can be deactivated; this one is " + status));
        }

        Instant now = clock.instant();
        if (store.deactivateVersion(tenantId, zoneId, version, now) != 1) {
            throw new ZoneActivationRefusedException(List.of("This version was retired by someone else"));
        }

        audit.record(AuditFact.of("delivery.zone.version.deactivated", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ServiceZone", zoneId)
                .targetVersion((long) version)
                .because("Deactivated version %d of zone %s; the zone now covers nothing".formatted(version, zoneId))
                .changed(Map.of("version", version))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /**
     * Stops a zone applying to a branch (ADR 0101).
     *
     * <p>Closes the binding's window rather than deleting the row: a fee
     * resolution six weeks old names the binding that applied. An unbind of a
     * branch that was not bound is refused rather than treated as a success,
     * because the two look identical on the screen that asked and one of them
     * means the operator clicked the wrong row.
     */
    @Transactional
    public void unbindLocation(UUID tenantId, UUID brandId, UUID zoneId, UUID locationId) {
        if (store.zoneRole(tenantId, brandId, zoneId).isEmpty()) {
            throw new DeliveryResourceNotFoundException("No zone " + zoneId + " for this brand");
        }
        Instant now = clock.instant();
        if (store.unbindLocation(tenantId, zoneId, locationId, now) < 1) {
            throw new DeliveryResourceNotFoundException(
                    "Location %s is not bound to zone %s".formatted(locationId, zoneId));
        }

        audit.record(AuditFact.of("delivery.zone.location.unbound", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ServiceZone", zoneId)
                .because("Unbound location %s from zone %s".formatted(locationId, zoneId))
                .changed(Map.of("locationId", locationId.toString()))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    /** Every version of one zone, newest first (§3.6's missing version history). */
    @Transactional(readOnly = true)
    public List<JdbcServiceZoneStore.ZoneVersionRow> listVersions(UUID tenantId, UUID brandId, UUID zoneId) {
        if (store.zoneRole(tenantId, brandId, zoneId).isEmpty()) {
            throw new DeliveryResourceNotFoundException("No zone " + zoneId + " for this brand");
        }
        return store.listVersions(tenantId, zoneId);
    }

    /** Every zone this brand has registered (operations §3.6 Delivery zones). */
    @Transactional(readOnly = true)
    public List<JdbcServiceZoneStore.ZoneSummaryRow> listZones(UUID tenantId, UUID brandId) {
        return store.listZones(tenantId, brandId);
    }

    /** One zone's lineage plus its live version's numbers, and the branches it currently applies to. */
    @Transactional(readOnly = true)
    public ZoneDetail zoneDetail(UUID tenantId, UUID brandId, UUID zoneId) {
        JdbcServiceZoneStore.ZoneSummaryRow zone = store.findZone(tenantId, brandId, zoneId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No zone " + zoneId + " for this brand"));
        List<UUID> locations = store.boundLocations(tenantId, zoneId, clock.instant());
        return new ZoneDetail(zone, locations);
    }

    public record ZoneDetail(JdbcServiceZoneStore.ZoneSummaryRow zone, List<UUID> boundLocationIds) {}

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    private JdbcServiceZoneStore.DraftVersion draft(
            NewVersion request, UUID id, int version, @Nullable UUID originLocationId, Instant now) {
        return new JdbcServiceZoneStore.DraftVersion(
                id,
                request.tenantId(),
                request.zoneId(),
                request.role(),
                version,
                originLocationId,
                request.regionId(),
                resolveRegion(request),
                request.priority(),
                request.currency(),
                request.deliveryTariffId(),
                request.freeDeliveryFromMinor(),
                request.minBasketMinor(),
                request.createdBy(),
                now);
    }

    /**
     * Resolves the region a new version names, in the authoring tenant, and
     * refuses anything else.
     *
     * <p>{@code regionId} arrives in the request body and went straight to the
     * insert, checked only by a foreign key that asked whether the id existed
     * <em>anywhere on the platform</em> — because {@code
     * fulfillment.regions.tenant_id} is nullable, and V0025's comment says why:
     * "Null means a platform region every tenant may reference. Tashkent is not
     * one tenant's fact." So a tenant could name another tenant's private region,
     * and the region is not decorative: {@link #activate} checks the polygon
     * against its bounding box, so one tenant's geography would be gating another
     * tenant's zone.
     *
     * <p>The two answers a tenant may have are a platform region and its own,
     * which is exactly what V0088's {@code fk_zone_version_region} accepts. This
     * is the refusal an operator can read; the constraint is what holds when a
     * write path is not this one.
     *
     * @return whether the named region is a platform region, or null when the
     *         version names none — a zone without a region is ordinary
     */
    private @Nullable Boolean resolveRegion(NewVersion request) {
        if (request.regionId() == null) {
            return null;
        }
        return store.regionIsPlatform(request.tenantId(), request.regionId())
                .orElseThrow(() -> new DeliveryResourceNotFoundException(
                        "No region " + request.regionId() + " this tenant may use. A zone may "
                                + "name a platform region or one of its own, and nothing else"));
    }

    /**
     * A new zone version to draft.
     *
     * @param priority the first key of the overlap ranking. Higher wins; ties fall
     *                 to the smaller area and then to the zone id, so a tie is
     *                 resolved rather than left to the planner
     */
    public record NewVersion(
            UUID tenantId,
            UUID brandId,
            UUID zoneId,
            ZoneRole role,
            @Nullable UUID regionId,
            int priority,
            String currency,
            @Nullable UUID deliveryTariffId,
            @Nullable Long freeDeliveryFromMinor,
            @Nullable Long minBasketMinor,
            UUID createdBy) {}

    public record DraftedVersion(UUID id, UUID zoneId, int version) {}

    /** Carries every reason at once, so the console can list them. */
    public static final class ZoneActivationRefusedException extends RuntimeException {

        private final List<String> problems;

        public ZoneActivationRefusedException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public static final class DeliveryResourceNotFoundException extends RuntimeException {
        public DeliveryResourceNotFoundException(String message) {
            super(message);
        }
    }
}
