package uz.horecaos.platform.inventory.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;

/**
 * Persistence for {@code inventory.availability_stops} (ADR 0141, {@code V0462}).
 *
 * <p>Every statement carries {@code tenant_id}; none deletes. A row is lifted or
 * expired in place and stays as the history. "Is this stop in force" is a
 * predicate on {@code status} <em>and</em> {@code ends_at} evaluated against the
 * caller's own instant — never {@code status} alone — because the expiry sweeper
 * is not on the correctness path of any read (ADR 0141 Decision 5).
 */
@Repository
public class JdbcAvailabilityStopStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, variant_id, scope_type, location_id, menu_id, channel_id,
            source, source_ref, reason_code, ends_at, status, group_id,
            created_by, created_at, lifted_by, lifted_at, version
            """;

    private final JdbcClient jdbc;

    public JdbcAvailabilityStopStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an ACTIVE stop, or does nothing when one with the same key is
     * already active.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the violation: a
     * constraint violation aborts the surrounding PostgreSQL transaction and
     * everything after it then fails for the wrong reason.
     *
     * @return true when this call created the row
     */
    public boolean insertIfAbsent(StopRow stop) {
        return jdbc.sql("""
                INSERT INTO inventory.availability_stops (
                    id, tenant_id, brand_id, variant_id, scope_type, location_id, menu_id, channel_id,
                    source, source_ref, reason_code, ends_at, status, group_id,
                    created_by, created_at, version, updated_at)
                VALUES (
                    :id, :tenantId, :brandId, :variantId, :scopeType, :locationId, :menuId, :channelId,
                    :source, :sourceRef, :reasonCode, :endsAt, 'ACTIVE', :groupId,
                    :createdBy, :createdAt, 1, :createdAt)
                ON CONFLICT DO NOTHING
                """)
                        .param("id", stop.id())
                        .param("tenantId", stop.tenantId())
                        .param("brandId", stop.brandId())
                        .param("variantId", stop.variantId())
                        .param("scopeType", stop.scopeType().name())
                        .param("locationId", stop.locationId())
                        .param("menuId", stop.menuId())
                        .param("channelId", stop.channelId())
                        .param("source", stop.source().name())
                        .param("sourceRef", stop.sourceRef())
                        .param("reasonCode", stop.reasonCode())
                        .param("endsAt", timestamp(stop.endsAt()))
                        .param("groupId", stop.groupId())
                        .param("createdBy", stop.createdBy())
                        .param("createdAt", timestamp(stop.createdAt()))
                        .update()
                == 1;
    }

    /** The ACTIVE stop with exactly this key, if any ({@code IS NOT DISTINCT FROM}: the nullable columns match nulls). */
    public Optional<StopRow> findActiveByKey(
            UUID tenantId,
            UUID variantId,
            StopScopeType scopeType,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            StopSource source,
            @Nullable String sourceRef) {
        return jdbc.sql("""
                SELECT %s FROM inventory.availability_stops
                WHERE tenant_id = :tenantId AND variant_id = :variantId AND scope_type = :scopeType
                  AND location_id IS NOT DISTINCT FROM :locationId
                  AND menu_id IS NOT DISTINCT FROM :menuId
                  AND channel_id IS NOT DISTINCT FROM :channelId
                  AND source = :source
                  AND source_ref IS NOT DISTINCT FROM :sourceRef
                  AND status = 'ACTIVE'
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("variantId", variantId)
                .param("scopeType", scopeType.name())
                .param("locationId", locationId)
                .param("menuId", menuId)
                .param("channelId", channelId)
                .param("source", source.name())
                .param("sourceRef", sourceRef)
                .query(JdbcAvailabilityStopStore::mapStop)
                .optional();
    }

    public Optional<StopRow> findById(UUID tenantId, UUID stopId) {
        return jdbc.sql("SELECT %s FROM inventory.availability_stops WHERE tenant_id = :tenantId AND id = :id"
                        .formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("id", stopId)
                .query(JdbcAvailabilityStopStore::mapStop)
                .optional();
    }

    /**
     * Every stop in force at {@code at} on any of these variants — the
     * resolver's single read. In force means {@code ACTIVE} and not past its
     * {@code ends_at}; a row whose end has passed but which the sweeper has not
     * yet marked {@code EXPIRED} is excluded here, by the predicate, which is
     * what makes the sweeper optional for correctness.
     */
    public List<StopRow> activeForVariants(UUID tenantId, Collection<UUID> variantIds, Instant at) {
        if (variantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT %s FROM inventory.availability_stops
                WHERE tenant_id = :tenantId AND variant_id = ANY(:variantIds)
                  AND status = 'ACTIVE' AND (ends_at IS NULL OR ends_at > :at)
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("variantIds", variantIds.toArray(UUID[]::new))
                .param("at", timestamp(at))
                .query(JdbcAvailabilityStopStore::mapStop)
                .list();
    }

    /**
     * Every stop of the brand in force at {@code at}, whatever its scope or source: what a
     * materialisation run (ADR 0141, rollback switch three) has to account for. Oldest first, so a
     * run is deterministic and a re-run walks the stops in the same order.
     */
    public List<StopRow> activeForBrand(UUID tenantId, UUID brandId, Instant at) {
        return jdbc.sql("""
                SELECT %s FROM inventory.availability_stops
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND status = 'ACTIVE' AND (ends_at IS NULL OR ends_at > :at)
                ORDER BY created_at, id
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("at", timestamp(at))
                .query(JdbcAvailabilityStopStore::mapStop)
                .list();
    }

    /**
     * The stops in force at {@code at} that apply to a whole location without
     * naming a channel: every {@code LOCATION} stop there and every {@code
     * BRAND} stop of its brand. What the stop list reads to say a dish is
     * stopped everywhere at this branch.
     */
    public List<StopRow> activeAtLocation(UUID tenantId, UUID brandId, UUID locationId, Instant at) {
        return jdbc.sql("""
                SELECT %s FROM inventory.availability_stops
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND status = 'ACTIVE' AND (ends_at IS NULL OR ends_at > :at)
                  AND (scope_type = 'BRAND'
                       OR (scope_type = 'LOCATION' AND location_id = :locationId)
                       OR (scope_type = 'CHANNEL' AND (location_id IS NULL OR location_id = :locationId))
                       OR scope_type = 'MENU')
                ORDER BY created_at, id
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("at", timestamp(at))
                .query(JdbcAvailabilityStopStore::mapStop)
                .list();
    }

    /** The console's list: a brand's stops, narrowed by variant, scope, source and whether they are in force. */
    public List<StopRow> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID variantId,
            @Nullable StopScopeType scopeType,
            @Nullable StopSource source,
            @Nullable UUID locationId,
            boolean activeOnly,
            Instant at,
            @Nullable UUID cursor,
            int limit) {
        return jdbc.sql("""
                SELECT %s FROM inventory.availability_stops
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND (CAST(:variantId AS uuid) IS NULL OR variant_id = CAST(:variantId AS uuid))
                  AND (CAST(:scopeType AS varchar) IS NULL OR scope_type = CAST(:scopeType AS varchar))
                  AND (CAST(:source AS varchar) IS NULL OR source = CAST(:source AS varchar))
                  AND (CAST(:locationId AS uuid) IS NULL
                       OR location_id = CAST(:locationId AS uuid)
                       OR (location_id IS NULL AND scope_type IN ('BRAND', 'MENU', 'CHANNEL')))
                  AND (:activeOnly = false
                       OR (status = 'ACTIVE' AND (ends_at IS NULL OR ends_at > :at)))
                  AND (CAST(:cursor AS uuid) IS NULL OR id < CAST(:cursor AS uuid))
                ORDER BY id DESC
                LIMIT :limit
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("variantId", variantId)
                .param("scopeType", scopeType == null ? null : scopeType.name())
                .param("source", source == null ? null : source.name())
                .param("locationId", locationId)
                .param("activeOnly", activeOnly)
                .param("at", timestamp(at))
                .param("cursor", cursor)
                .param("limit", limit)
                .query(JdbcAvailabilityStopStore::mapStop)
                .list();
    }

    /**
     * Changes the end and reason of an ACTIVE stop in place — a repeated stop
     * for the same key with a new end. Bumps the version so a console that read
     * the old one loses loudly.
     *
     * @return the new row, or empty when the stop was no longer ACTIVE
     */
    public Optional<StopRow> reviseActive(
            UUID tenantId, UUID stopId, @Nullable Instant endsAt, String reasonCode, Instant now) {
        return jdbc.sql("""
                UPDATE inventory.availability_stops
                SET ends_at = :endsAt, reason_code = :reasonCode, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                RETURNING %s
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("id", stopId)
                .param("endsAt", timestamp(endsAt))
                .param("reasonCode", reasonCode)
                .param("now", timestamp(now))
                .query(JdbcAvailabilityStopStore::mapStop)
                .optional();
    }

    /**
     * Lifts one ACTIVE stop. {@code expectedVersion} null means "whatever it is"
     * (a source lifting its own stop by key); a number is the ADR 0031
     * {@code If-Match} precondition, evaluated inside the statement so two
     * concurrent lifts cannot both succeed.
     *
     * @return the lifted row, or empty when it was not ACTIVE or the version moved
     */
    public Optional<StopRow> lift(
            UUID tenantId, UUID stopId, @Nullable Integer expectedVersion, String liftedBy, Instant now) {
        return jdbc.sql("""
                UPDATE inventory.availability_stops
                SET status = 'LIFTED', lifted_by = :liftedBy, lifted_at = :now,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                  AND (CAST(:expectedVersion AS integer) IS NULL OR version = CAST(:expectedVersion AS integer))
                RETURNING %s
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("id", stopId)
                .param("expectedVersion", expectedVersion)
                .param("liftedBy", liftedBy)
                .param("now", timestamp(now))
                .query(JdbcAvailabilityStopStore::mapStop)
                .optional();
    }

    /**
     * Lifts the ACTIVE {@code LOCATION} stops of exactly one source (and, for
     * POS, one binding) on one variant at one location — what a source does when
     * it takes back its own opinion. Never touches another source's stop.
     */
    public List<StopRow> liftLocationStopsOf(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            StopSource source,
            @Nullable String sourceRef,
            String liftedBy,
            Instant now) {
        return jdbc.sql("""
                UPDATE inventory.availability_stops
                SET status = 'LIFTED', lifted_by = :liftedBy, lifted_at = :now,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND variant_id = :variantId AND scope_type = 'LOCATION'
                  AND location_id = :locationId AND source = :source
                  AND (CAST(:sourceRef AS varchar) IS NULL OR source_ref = CAST(:sourceRef AS varchar))
                  AND status = 'ACTIVE'
                RETURNING %s
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("source", source.name())
                .param("sourceRef", sourceRef)
                .param("liftedBy", liftedBy)
                .param("now", timestamp(now))
                .query(JdbcAvailabilityStopStore::mapStop)
                .list();
    }

    /**
     * Marks every ACTIVE stop whose end has passed as {@code EXPIRED}, across
     * every tenant. The caller has assumed the ADR 0056 platform role; this is
     * the one statement in the table that is cross-tenant by design.
     */
    public List<StopRow> expireDue(Instant now) {
        return jdbc.sql("""
                UPDATE inventory.availability_stops
                SET status = 'EXPIRED', lifted_by = 'system', lifted_at = :now,
                    version = version + 1, updated_at = :now
                WHERE status = 'ACTIVE' AND ends_at IS NOT NULL AND ends_at <= :now
                RETURNING %s
                """.formatted(COLUMNS))
                .param("now", timestamp(now))
                .query(JdbcAvailabilityStopStore::mapStop)
                .list();
    }

    /** The locations at which a variant is listed as stock — where a brand-wide stop changes what is sold. */
    public List<UUID> locationsStocking(UUID tenantId, UUID brandId, UUID variantId) {
        return jdbc.sql("""
                SELECT DISTINCT location_id FROM inventory.stock_items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND variant_id = :variantId
                  AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("variantId", variantId)
                .query(UUID.class)
                .list();
    }

    /** Variant ids stopped everywhere at a location (LOCATION or BRAND scope) at {@code at}. */
    public Set<UUID> variantsStoppedEverywhereAt(UUID tenantId, UUID brandId, UUID locationId, Instant at) {
        return Set.copyOf(new ArrayList<>(jdbc.sql("""
                SELECT DISTINCT variant_id FROM inventory.availability_stops
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND status = 'ACTIVE' AND (ends_at IS NULL OR ends_at > :at)
                  AND (scope_type = 'BRAND' OR (scope_type = 'LOCATION' AND location_id = :locationId))
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("at", timestamp(at))
                .query(UUID.class)
                .list()));
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static StopRow mapStop(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        OffsetDateTime endsAt = row.getObject("ends_at", OffsetDateTime.class);
        OffsetDateTime liftedAt = row.getObject("lifted_at", OffsetDateTime.class);
        return new StopRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("variant_id", UUID.class),
                StopScopeType.valueOf(row.getString("scope_type")),
                row.getObject("location_id", UUID.class),
                row.getObject("menu_id", UUID.class),
                row.getObject("channel_id", UUID.class),
                StopSource.valueOf(row.getString("source")),
                row.getString("source_ref"),
                row.getString("reason_code"),
                endsAt == null ? null : endsAt.toInstant(),
                row.getString("status"),
                row.getObject("group_id", UUID.class),
                row.getString("created_by"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getString("lifted_by"),
                liftedAt == null ? null : liftedAt.toInstant(),
                row.getInt("version"));
    }

    /** One {@code inventory.availability_stops} row. */
    public record StopRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID variantId,
            StopScopeType scopeType,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            StopSource source,
            @Nullable String sourceRef,
            String reasonCode,
            @Nullable Instant endsAt,
            String status,
            @Nullable UUID groupId,
            String createdBy,
            Instant createdAt,
            @Nullable String liftedBy,
            @Nullable Instant liftedAt,
            int version) {

        /** A new ACTIVE row; the store stamps version 1. */
        public static StopRow newStop(
                UUID id,
                UUID tenantId,
                UUID brandId,
                UUID variantId,
                StopScopeType scopeType,
                @Nullable UUID locationId,
                @Nullable UUID menuId,
                @Nullable UUID channelId,
                StopSource source,
                @Nullable String sourceRef,
                String reasonCode,
                @Nullable Instant endsAt,
                @Nullable UUID groupId,
                String createdBy,
                Instant createdAt) {
            return new StopRow(
                    id,
                    tenantId,
                    brandId,
                    variantId,
                    scopeType,
                    locationId,
                    menuId,
                    channelId,
                    source,
                    sourceRef,
                    reasonCode,
                    endsAt,
                    "ACTIVE",
                    groupId,
                    createdBy,
                    createdAt,
                    null,
                    null,
                    1);
        }

        /** Whether this row is in force at {@code at}: ACTIVE and not past its end. */
        public boolean inForceAt(Instant at) {
            return "ACTIVE".equals(status) && (endsAt == null || endsAt.isAfter(at));
        }
    }
}
