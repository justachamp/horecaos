package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.courier.domain.PlannedShiftStatus;

/**
 * The roster a manager plans, as distinct from the shift a courier opens
 * (ADR 0042, IA 3.5). See {@code V0262}'s own comment for why this table
 * carries no foreign key to {@code fulfillment.courier_shifts}: the
 * planned-versus-actual comparison is a read-time join by courier and
 * overlapping time window, computed by {@code PlannedShiftService}, not a
 * stored relationship.
 */
@Repository
public class JdbcPlannedShiftStore {

    private final JdbcClient jdbc;

    public JdbcPlannedShiftStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PlannedShiftRow entry) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", entry.id());
        params.put("tenantId", entry.tenantId());
        params.put("brandId", entry.brandId());
        params.put("locationId", entry.locationId());
        params.put("courierId", entry.courierId());
        params.put("engagementId", entry.engagementId());
        params.put("plannedStart", JdbcCourierStore.utc(entry.plannedStart()));
        params.put("plannedEnd", JdbcCourierStore.utc(entry.plannedEnd()));
        params.put("createdBy", entry.createdBy());
        params.put("now", JdbcCourierStore.utc(Instant.now()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_roster_entries (
                    id, tenant_id, brand_id, location_id, courier_id, engagement_id,
                    status, planned_start, planned_end, created_by,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :engagementId,
                    'DRAFT', :plannedStart, :plannedEnd, :createdBy,
                    1, :now, :now)
                """).params(params).update();
    }

    public Optional<PlannedShiftRow> find(UUID tenantId, UUID id) {
        return jdbc.sql(SELECT_ENTRY + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcPlannedShiftStore::mapEntry)
                .optional();
    }

    /**
     * The branch's planned shifts, newest-start first, optionally windowed to
     * a period. Both bounds are inclusive-overlap: an entry that only partly
     * falls inside {@code [from, to]} is still returned, the same way a
     * manager reading "this week's roster" wants to see a shift that started
     * Sunday night and runs past midnight.
     */
    public List<PlannedShiftRow> atLocation(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable Instant from, @Nullable Instant to, int limit) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("locationId", locationId);
        params.put("limit", limit);

        StringBuilder filter =
                new StringBuilder(" WHERE tenant_id = :tenantId AND brand_id = :brandId AND location_id = :locationId");
        if (from != null) {
            filter.append(" AND planned_end >= :from");
            params.put("from", JdbcCourierStore.utc(from));
        }
        if (to != null) {
            filter.append(" AND planned_start <= :to");
            params.put("to", JdbcCourierStore.utc(to));
        }

        return jdbc.sql(SELECT_ENTRY + filter + """
                 ORDER BY planned_start DESC
                 LIMIT :limit
                """)
                .params(params)
                .query(JdbcPlannedShiftStore::mapEntry)
                .list();
    }

    /** Every planned entry for one courier overlapping a window — the comparison's other half. */
    public List<PlannedShiftRow> forCourier(UUID tenantId, UUID courierId, Instant from, Instant to) {
        return jdbc.sql(SELECT_ENTRY + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                   AND planned_end >= :from AND planned_start <= :to
                 ORDER BY planned_start
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("from", JdbcCourierStore.utc(from))
                .param("to", JdbcCourierStore.utc(to))
                .query(JdbcPlannedShiftStore::mapEntry)
                .list();
    }

    public boolean publish(UUID tenantId, UUID id, UUID publishedBy, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_roster_entries
                   SET status = 'PUBLISHED', published_at = :now, published_by = :publishedBy,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("publishedBy", publishedBy)
                        .param("now", JdbcCourierStore.utc(now))
                        .update()
                == 1;
    }

    public boolean cancel(UUID tenantId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_roster_entries
                   SET status = 'CANCELLED', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status IN ('DRAFT', 'PUBLISHED')
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("now", JdbcCourierStore.utc(now))
                        .update()
                == 1;
    }

    // -------------------------------------------------------------------- rows

    /**
     * One planned shift as every reader sees it. {@code publishedAt}/{@code
     * publishedBy} are null while the entry is still {@code DRAFT}; {@code
     * respondedAt} is null until a courier-facing surface this wave does not
     * build answers the offer.
     */
    public record PlannedShiftRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID courierId,
            UUID engagementId,
            PlannedShiftStatus status,
            Instant plannedStart,
            Instant plannedEnd,
            UUID createdBy,
            @Nullable Instant publishedAt,
            @Nullable UUID publishedBy,
            @Nullable Instant respondedAt,
            int version) {}

    // ----------------------------------------------------------------- mapping

    private static final String SELECT_ENTRY = """
            SELECT id, tenant_id, brand_id, location_id, courier_id, engagement_id,
                   status, planned_start, planned_end, created_by,
                   published_at, published_by, responded_at, version
              FROM fulfillment.courier_roster_entries
            """;

    private static PlannedShiftRow mapEntry(ResultSet rs, int rowNumber) throws SQLException {
        return new PlannedShiftRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                PlannedShiftStatus.valueOf(rs.getString("status")),
                Objects.requireNonNull(JdbcCourierStore.instant(rs.getObject("planned_start", OffsetDateTime.class))),
                Objects.requireNonNull(JdbcCourierStore.instant(rs.getObject("planned_end", OffsetDateTime.class))),
                rs.getObject("created_by", UUID.class),
                JdbcCourierStore.instant(rs.getObject("published_at", OffsetDateTime.class)),
                rs.getObject("published_by", UUID.class),
                JdbcCourierStore.instant(rs.getObject("responded_at", OffsetDateTime.class)),
                rs.getInt("version"));
    }
}
