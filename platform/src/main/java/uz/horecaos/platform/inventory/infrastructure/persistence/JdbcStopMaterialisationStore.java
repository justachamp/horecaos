package uz.horecaos.platform.inventory.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the materialisation runs that precede a decommission of stops (ADR 0141,
 * rollback switch three, {@code V0489}).
 *
 * <p>Every statement carries {@code tenant_id}; none deletes. The two reads that decide whether the
 * decommission may proceed -- {@link #hasAcknowledgedRun} and {@link #stopsInForceNoAcknowledgedRunCarried}
 * -- are written as questions about <em>stop ids</em>, never as a comparison of timestamps: "a stop
 * created after the run re-blocks it" must hold for a stop that committed a moment after the run
 * read, and a clock cannot promise that.
 */
@Repository
public class JdbcStopMaterialisationStore {

    private static final String RUN_COLUMNS = """
            id, tenant_id, brand_id, status, started_by, started_at, completed_at,
            stops_seen, positions_written, positions_already_unavailable, not_carried, failed_stops,
            acknowledged_by, acknowledged_at, version
            """;

    private final JdbcClient jdbc;

    public JdbcStopMaterialisationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ runs

    /**
     * Opens a {@code RUNNING} run. The partial unique index admits one per brand, so a second
     * concurrent start raises {@link org.springframework.dao.DuplicateKeyException}.
     */
    public void insertRun(UUID id, UUID tenantId, UUID brandId, String startedBy, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO inventory.stop_materialisation_runs
                    (id, tenant_id, brand_id, status, started_by, started_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, 'RUNNING', :startedBy, :startedAt, 1, :startedAt, :startedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("startedBy", startedBy)
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    /**
     * A run left {@code RUNNING} by a process that died is closed as {@code FAILED}, so it does not
     * hold the one-at-a-time index forever. Its stops were committed one by one and stay in its set.
     */
    public int failAbandonedRuns(UUID tenantId, UUID brandId, Instant startedBefore, Instant now) {
        return jdbc.sql("""
                UPDATE inventory.stop_materialisation_runs
                SET status = 'FAILED', completed_at = :now, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND status = 'RUNNING' AND started_at < :startedBefore
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("startedBefore", timestamp(startedBefore))
                .param("now", timestamp(now))
                .update();
    }

    public void completeRun(
            UUID tenantId,
            UUID runId,
            String status,
            Instant completedAt,
            int stopsSeen,
            int positionsWritten,
            int positionsAlreadyUnavailable,
            int notCarried,
            int failedStops) {
        jdbc.sql("""
                UPDATE inventory.stop_materialisation_runs
                SET status = :status, completed_at = :completedAt,
                    stops_seen = :stopsSeen, positions_written = :written,
                    positions_already_unavailable = :already, not_carried = :notCarried,
                    failed_stops = :failed, version = version + 1, updated_at = :completedAt
                WHERE tenant_id = :tenantId AND id = :runId AND status = 'RUNNING'
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("status", status)
                .param("completedAt", timestamp(completedAt))
                .param("stopsSeen", stopsSeen)
                .param("written", positionsWritten)
                .param("already", positionsAlreadyUnavailable)
                .param("notCarried", notCarried)
                .param("failed", failedStops)
                .update();
    }

    public Optional<RunRow> findRun(UUID tenantId, UUID brandId, UUID runId) {
        return jdbc.sql("""
                SELECT %s FROM inventory.stop_materialisation_runs
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :runId
                """.formatted(RUN_COLUMNS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("runId", runId)
                .query(JdbcStopMaterialisationStore::mapRun)
                .optional();
    }

    /** The brand's runs, newest first. */
    public List<RunRow> listRuns(UUID tenantId, UUID brandId, int limit) {
        return jdbc.sql("""
                SELECT %s FROM inventory.stop_materialisation_runs
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY started_at DESC, id DESC
                LIMIT :limit
                """.formatted(RUN_COLUMNS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit)
                .query(JdbcStopMaterialisationStore::mapRun)
                .list();
    }

    /**
     * Records the owner's acknowledgement of a finished run's report, if the run is still at the
     * version the owner read.
     *
     * @return false when the version moved, the run is not finished or it was already acknowledged
     */
    public boolean acknowledge(UUID tenantId, UUID brandId, UUID runId, int expectedVersion, String by, Instant at) {
        return jdbc.sql("""
                UPDATE inventory.stop_materialisation_runs
                SET acknowledged_by = :by, acknowledged_at = :at, version = version + 1, updated_at = :at
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :runId
                  AND version = :expectedVersion AND status = 'COMPLETED' AND acknowledged_at IS NULL
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("runId", runId)
                        .param("expectedVersion", expectedVersion)
                        .param("by", by)
                        .param("at", timestamp(at))
                        .update()
                == 1;
    }

    // ------------------------------------------------------------------ the set and the report

    public void recordStop(
            UUID tenantId,
            UUID runId,
            UUID stopId,
            int positionsWritten,
            int positionsAlreadyUnavailable,
            int notCarried) {
        jdbc.sql("""
                INSERT INTO inventory.stop_materialisation_stops
                    (run_id, tenant_id, stop_id, positions_written, positions_already_unavailable, not_carried)
                VALUES (:runId, :tenantId, :stopId, :written, :already, :notCarried)
                ON CONFLICT (run_id, stop_id) DO NOTHING
                """)
                .param("runId", runId)
                .param("tenantId", tenantId)
                .param("stopId", stopId)
                .param("written", positionsWritten)
                .param("already", positionsAlreadyUnavailable)
                .param("notCarried", notCarried)
                .update();
    }

    public void insertLine(
            UUID id,
            UUID tenantId,
            UUID runId,
            UUID stopId,
            UUID variantId,
            String scopeType,
            String source,
            @Nullable UUID locationId,
            @Nullable UUID channelId,
            @Nullable UUID menuId,
            String reasonCode) {
        jdbc.sql("""
                INSERT INTO inventory.stop_materialisation_lines
                    (id, tenant_id, run_id, stop_id, variant_id, scope_type, source,
                     location_id, channel_id, menu_id, reason_code)
                VALUES (:id, :tenantId, :runId, :stopId, :variantId, :scopeType, :source,
                        :locationId, :channelId, :menuId, :reasonCode)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("stopId", stopId)
                .param("variantId", variantId)
                .param("scopeType", scopeType)
                .param("source", source)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("menuId", menuId)
                .param("reasonCode", reasonCode)
                .update();
    }

    /** A page of a run's report in a stable order; {@code after} is the last line id seen. */
    public List<LineRow> lines(UUID tenantId, UUID runId, @Nullable UUID after, int limit) {
        return jdbc.sql("""
                SELECT id, stop_id, variant_id, scope_type, source, location_id, channel_id, menu_id, reason_code
                FROM inventory.stop_materialisation_lines
                WHERE tenant_id = :tenantId AND run_id = :runId
                  AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
                ORDER BY id
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("after", after)
                .param("limit", limit)
                .query((row, number) -> new LineRow(
                        row.getObject("id", UUID.class),
                        row.getObject("stop_id", UUID.class),
                        row.getObject("variant_id", UUID.class),
                        row.getString("scope_type"),
                        row.getString("source"),
                        row.getObject("location_id", UUID.class),
                        row.getObject("channel_id", UUID.class),
                        row.getObject("menu_id", UUID.class),
                        row.getString("reason_code")))
                .list();
    }

    // ------------------------------------------------------------------ what the guard asks

    /** The brands of a tenant that have ever had a stop row: the only ones a decommission could lose anything in. */
    public List<UUID> brandsWithStops(UUID tenantId) {
        return jdbc.sql("""
                SELECT DISTINCT brand_id FROM inventory.availability_stops WHERE tenant_id = :tenantId
                """).param("tenantId", tenantId).query(UUID.class).list();
    }

    /** Every {@code (tenant, brand)} that has ever had a stop row, for a platform-wide switch. */
    public List<BrandRef> allBrandsWithStops() {
        return jdbc.sql("""
                SELECT DISTINCT tenant_id, brand_id FROM inventory.availability_stops
                """)
                .query((row, number) ->
                        new BrandRef(row.getObject("tenant_id", UUID.class), row.getObject("brand_id", UUID.class)))
                .list();
    }

    /** Whether a finished run of the brand has had its report acknowledged. */
    public boolean hasAcknowledgedRun(UUID tenantId, UUID brandId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM inventory.stop_materialisation_runs
                    WHERE tenant_id = :tenantId AND brand_id = :brandId
                      AND status = 'COMPLETED' AND acknowledged_at IS NOT NULL)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Boolean.class)
                .single());
    }

    /**
     * How many stops of the brand are in force at {@code at} that no acknowledged, finished run
     * lists as carried. Zero is the second half of the guard's answer; anything else is a stop
     * nobody has been told about.
     */
    public int stopsInForceNoAcknowledgedRunCarried(UUID tenantId, UUID brandId, Instant at) {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM inventory.availability_stops s
                WHERE s.tenant_id = :tenantId AND s.brand_id = :brandId
                  AND s.status = 'ACTIVE' AND (s.ends_at IS NULL OR s.ends_at > :at)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM inventory.stop_materialisation_stops m
                      JOIN inventory.stop_materialisation_runs r ON r.id = m.run_id
                      WHERE m.stop_id = s.id AND r.tenant_id = s.tenant_id
                        AND r.status = 'COMPLETED' AND r.acknowledged_at IS NOT NULL)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("at", timestamp(at))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ mapping

    private static RunRow mapRun(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        OffsetDateTime completed = row.getObject("completed_at", OffsetDateTime.class);
        OffsetDateTime acknowledged = row.getObject("acknowledged_at", OffsetDateTime.class);
        return new RunRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("status"),
                row.getString("started_by"),
                row.getObject("started_at", OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant(),
                row.getInt("stops_seen"),
                row.getInt("positions_written"),
                row.getInt("positions_already_unavailable"),
                row.getInt("not_carried"),
                row.getInt("failed_stops"),
                row.getString("acknowledged_by"),
                acknowledged == null ? null : acknowledged.toInstant(),
                row.getInt("version"));
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One run of one brand. */
    public record RunRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String status,
            String startedBy,
            Instant startedAt,
            @Nullable Instant completedAt,
            int stopsSeen,
            int positionsWritten,
            int positionsAlreadyUnavailable,
            int notCarried,
            int failedStops,
            @Nullable String acknowledgedBy,
            @Nullable Instant acknowledgedAt,
            int version) {}

    /** One line of a run's report: a stop (at a location) that did not land on a position, and why. */
    public record LineRow(
            UUID id,
            UUID stopId,
            UUID variantId,
            String scopeType,
            String source,
            @Nullable UUID locationId,
            @Nullable UUID channelId,
            @Nullable UUID menuId,
            String reasonCode) {}

    /** A brand of a tenant. */
    public record BrandRef(UUID tenantId, UUID brandId) {}
}
