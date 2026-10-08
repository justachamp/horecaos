package uz.horecaos.platform.iam.application.staff;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.ProtectionIntegrityException;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.GrantPlace;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffPreferencesStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffPreferencesStore.PreferenceRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A person's own interface preferences (ADR 0160): what the console remembers for them across devices --
 * the default branch, the order board's optional columns and saved views.
 *
 * <p><strong>Only the person it belongs to.</strong> Every method takes the tenant and the signed-in
 * subject and nothing that names another person: there is no member id, no manager read, no tenant-wide
 * write, and a subject with no member row (a device, a support session) is answered as «no such profile»
 * exactly as {@code GET .../staff/me} answers it. The route that calls this is authorised by {@code
 * @StaffSelfAuthorized}, which is sound only because of that.
 *
 * <p><strong>Writes are cheap and unaudited, and say so.</strong> A preference write writes no ADR 0027
 * fact and publishes no event: it is neither a business act nor a security one, and an audit record per
 * toggle is a cost with no reader. A counter with an outcome and nothing else counts them; no identity,
 * no surface, no document reaches a metric, a log line or a trace. The one preference-related change that
 * is audited is the language default, which is a profile edit under ADR 0139 and is not stored here.
 *
 * <p><strong>Protected.</strong> The whole document is {@code PERSONAL} (ADR 0029): one field in it is
 * text a person typed, and the platform cannot prove that text is not about someone. It is sealed with
 * the tenant, table, column and row bound into the associated data, so a ciphertext copied to another row
 * or tenant fails to open instead of showing someone else's layout. The documents are opened on every
 * read; the cache holds ciphertext only ({@link StaffPreferencesCache}).
 *
 * <p><strong>Versions.</strong> A first write carries no version, a later one carries the stored
 * version, and two devices writing first, or writing from the same version, are told {@code
 * STALE_VERSION}: the loser re-reads, re-applies its one change and tries once more (the console's rule).
 * The check is against the database, never the cache.
 */
@Service
public class StaffPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(StaffPreferencesService.class);

    static final String TABLE = "iam.staff_preferences";
    static final String COLUMN = "protected_document";
    private static final String PURPOSE = "staff.preferences.read";

    private final JdbcStaffPreferencesStore store;
    private final JdbcStaffMemberStore members;
    private final StaffPreferenceSchemas schemas;
    private final StaffPreferencesCache cache;
    private final FieldProtection protection;
    private final MeterRegistry meters;
    private final Clock clock;

    public StaffPreferencesService(
            JdbcStaffPreferencesStore store,
            JdbcStaffMemberStore members,
            StaffPreferenceSchemas schemas,
            StaffPreferencesCache cache,
            FieldProtection protection,
            MeterRegistry meters,
            Clock clock) {
        this.store = store;
        this.members = members;
        this.schemas = schemas;
        this.cache = cache;
        this.protection = protection;
        this.meters = meters;
        this.clock = clock;
    }

    /** One surface's document as the person last saved it, already upgraded to the current schema. */
    public record StoredPreference(
            StaffPreferenceSurface surface, JsonNode document, int documentSchema, int version) {}

    // ====================================================================== reads

    /**
     * Every document the person has stored, once per session on the console's side.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when this tenant keeps no record for the account
     */
    @Transactional(readOnly = true)
    public List<StoredPreference> readAll(UUID tenantId, String subject) {
        requireMember(tenantId, subject);
        List<PreferenceRow> rows = cache.get(tenantId, subject);
        if (rows == null) {
            rows = store.findAll(tenantId, subject);
            cache.put(tenantId, subject, rows);
        }
        List<StoredPreference> out = new ArrayList<>();
        for (PreferenceRow row : rows) {
            Optional<StaffPreferenceSurface> surface = StaffPreferenceSurface.ofName(row.surface());
            if (surface.isEmpty()) {
                // A surface a later release wrote and this one does not know: not ours to show or to refuse.
                continue;
            }
            open(row, surface.get()).ifPresent(out::add);
        }
        return out;
    }

    // ===================================================================== writes

    /**
     * Saves the document for one surface.
     *
     * @param expectedVersion the version the caller read, or {@code null} for a first write
     * @throws ApiException {@code VALIDATION_FAILED} naming the field when the schema refuses the document,
     *                      {@code STALE_VERSION} when the stored version is not the one expected
     */
    @Transactional
    public StoredPreference write(
            UUID tenantId,
            String subject,
            StaffPreferenceSurface surface,
            @Nullable JsonNode document,
            @Nullable Long expectedVersion) {
        requireMember(tenantId, subject);
        ObjectNode canonical;
        try {
            canonical = schemas.validate(surface, document);
            if (surface == StaffPreferenceSurface.SHELL) {
                requireReachableBranch(tenantId, subject, canonical);
            }
        } catch (ApiException refused) {
            count("refused");
            throw refused;
        }
        String text = schemas.serialize(canonical);
        int schema = schemas.currentSchema(surface);
        Instant now = clock.instant();
        Optional<PreferenceRow> existing = store.find(tenantId, subject, surface.name());
        long expected = expectedVersion == null ? 0 : expectedVersion;

        if (existing.isEmpty()) {
            if (expected != 0) {
                count("stale");
                throw ApiException.staleVersion(expected, 0);
            }
            if (store.count(tenantId, subject) >= StaffPreferenceSchemas.MAX_SURFACES_PER_PERSON) {
                count("refused");
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "At most %d surfaces".formatted(StaffPreferenceSchemas.MAX_SURFACES_PER_PERSON),
                        Map.of("field", "surface"));
            }
            UUID id = Ids.newId();
            PreferenceRow row = new PreferenceRow(
                    id, tenantId, subject, surface.name(), seal(tenantId, id, text), schema, 1, now, now);
            if (!store.insertIfAbsent(row)) {
                count("stale");
                throw ApiException.staleVersion(0, currentVersion(tenantId, subject, surface));
            }
            cache.evictAfterCommit(tenantId, subject);
            count("created");
            return new StoredPreference(surface, canonical, schema, 1);
        }

        PreferenceRow current = existing.get();
        if (expected != current.version()) {
            count("stale");
            throw ApiException.staleVersion(expected, current.version());
        }
        boolean updated = store.update(
                tenantId, subject, surface.name(), seal(tenantId, current.id(), text), schema, current.version(), now);
        if (!updated) {
            count("stale");
            throw ApiException.staleVersion(expected, currentVersion(tenantId, subject, surface));
        }
        cache.evictAfterCommit(tenantId, subject);
        count("updated");
        return new StoredPreference(surface, canonical, schema, current.version() + 1);
    }

    /** Resets one surface to the default. Nothing stored is nothing to do: the answer is the same. */
    @Transactional
    public void reset(UUID tenantId, String subject, StaffPreferenceSurface surface) {
        requireMember(tenantId, subject);
        if (store.delete(tenantId, subject, surface.name())) {
            cache.evictAfterCommit(tenantId, subject);
            count("reset");
        }
    }

    // ==================================================================== internals

    private void requireMember(UUID tenantId, String subject) {
        if (members.findBySubject(tenantId, subject).isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "There is no staff profile for this account in this tenant");
        }
    }

    /**
     * The default branch has to be one the person works at: a LOCATION grant there, a BRAND grant over its
     * brand, or a TENANT grant (ADR 0025 scopes cover downward). Checked on write only; a grant that has
     * since gone is the console's to ignore, not this store's to repair.
     */
    private void requireReachableBranch(UUID tenantId, String subject, ObjectNode canonical) {
        JsonNode location = canonical.get("defaultLocationId");
        if (location == null) {
            return;
        }
        UUID locationId = UUID.fromString(location.asString());
        UUID brandId = store.brandOfLocation(tenantId, locationId).orElse(null);
        List<GrantPlace> places = members.activeGrantPlaces(
                        tenantId, List.of(subject), clock.instant(), StaffMembers.MACHINE_ROLE_CODES)
                .getOrDefault(subject, List.of());
        boolean reachable = brandId != null
                && places.stream().anyMatch(place -> switch (place.scopeType()) {
                    case "TENANT" -> true;
                    case "BRAND" -> brandId.equals(place.scopeId());
                    case "LOCATION" -> locationId.equals(place.scopeId());
                    default -> false;
                });
        if (!reachable) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Not a branch you work at",
                    Map.of("field", "document.defaultLocationId"));
        }
    }

    private String seal(UUID tenantId, UUID rowId, String plaintext) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, COLUMN, rowId), plaintext)
                .serialize();
    }

    /**
     * Opens one row. A ciphertext that fails its integrity check is a security event and is logged -- by
     * tenant and surface, never by value -- and the surface reads as absent, so one bad row does not take
     * the person's other documents down with it.
     */
    private Optional<StoredPreference> open(PreferenceRow row, StaffPreferenceSurface surface) {
        try {
            String plaintext = protection.reveal(
                    row.tenantId(),
                    ProtectedValue.deserialize(row.protectedDocument()),
                    new RecordRef(TABLE, COLUMN, row.id()),
                    PURPOSE);
            JsonNode stored = schemas.parse(plaintext);
            JsonNode upgraded = schemas.upgrade(surface, stored, row.documentSchema());
            int schema = Math.max(row.documentSchema(), schemas.currentSchema(surface));
            return Optional.of(new StoredPreference(surface, upgraded, schema, row.version()));
        } catch (ProtectionIntegrityException | IllegalArgumentException failure) {
            log.error(
                    "A stored preference failed its integrity check (surface {} in tenant {})",
                    surface,
                    row.tenantId());
            return Optional.empty();
        }
    }

    private int currentVersion(UUID tenantId, String subject, StaffPreferenceSurface surface) {
        return store.find(tenantId, subject, surface.name())
                .map(PreferenceRow::version)
                .orElse(0);
    }

    /** The only measurement: what became of a write. No identity, no surface, no document. */
    private void count(String outcome) {
        meters.counter("horecaos.iam.staff.preferences.writes", "outcome", outcome)
                .increment();
    }
}
