package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.courier.domain.DutyState;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.domain.ShiftStatus;

/**
 * Shifts, breaks, and the cash handover that closes one (ADR 0042).
 *
 * <p>Every transition is a conditional UPDATE naming the status it expects, and
 * the row count decides. A courier tapping close twice on a slow connection, a
 * manager closing the same shift from the office, and the auto-close sweeper
 * arriving at the same second are one question, answered by PostgreSQL rather
 * than by whichever request happened to be read first — and the loser of that
 * race must not produce a second set of paid hours.
 */
@Repository
public class JdbcCourierShiftStore {

    private final JdbcClient jdbc;

    public JdbcCourierShiftStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- shifts

    public void insertShift(ShiftRow shift) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", shift.id());
        params.put("tenantId", shift.tenantId());
        params.put("brandId", shift.brandId());
        params.put("locationId", shift.locationId());
        params.put("courierId", shift.courierId());
        params.put("engagementId", shift.engagementId());
        params.put("openedAt", JdbcCourierStore.utc(shift.openedAt()));
        params.put("openPoint", shift.protectedOpenPoint());
        params.put("enforcementMode", shift.enforcementMode().name());
        params.put("policyId", shift.enforcementPolicyId());
        params.put("policyVersion", shift.enforcementPolicyVersion());
        params.put("periodId", shift.settlementPeriodId());
        params.put("now", JdbcCourierStore.utc(Instant.now()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_shifts (
                    id, tenant_id, brand_id, location_id, courier_id, engagement_id,
                    status, duty_state, opened_at, open_source, protected_open_point,
                    break_seconds, enforcement_mode, enforcement_policy_id,
                    enforcement_policy_version, settlement_period_id,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :engagementId,
                    'OPEN', 'AVAILABLE', :openedAt, 'COURIER', :openPoint,
                    0, :enforcementMode, :policyId, :policyVersion, :periodId,
                    1, :now, :now)
                """).params(params).update();
    }

    public Optional<ShiftRow> findShift(UUID tenantId, UUID shiftId) {
        return jdbc.sql(SELECT_SHIFT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", shiftId)
                .query(JdbcCourierShiftStore::mapShift)
                .optional();
    }

    public Optional<ShiftRow> findLiveShift(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_SHIFT + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                   AND status IN ('OPEN', 'CLOSE_REQUESTED', 'RECONCILING')
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierShiftStore::mapShift)
                .optional();
    }

    /**
     * Everybody who has declared themselves working at this branch, with the two
     * dispatch numbers their vehicle class carries and what they have already
     * done this shift (ADR 0042).
     *
     * <p>This is the enumeration behind {@code InternalFleetPort.candidates}. It
     * decides nothing about eligibility — {@code CourierDispatchGate} does that,
     * and it stays the only place that does. What this answers is the prior
     * question the gate cannot: <em>which</em> couriers to ask about. A courier
     * is associated with a branch by having opened a shift there and by nothing
     * else, so a fleet with no open shifts is an empty list rather than a
     * tenant-wide scan.
     *
     * <p>{@code status = 'OPEN'} and not the wider live set. A courier in
     * {@code CLOSE_REQUESTED} or {@code RECONCILING} has asked to stop, and
     * handing him one more order is how a shift never closes. That is a
     * narrowing of who is asked and not a second copy of an eligibility rule:
     * the gate has no opinion about a closing shift, because until now nothing
     * enumerated one.
     *
     * <p>{@code deliveries_this_shift} counts ADR 0042's own earnings and not
     * ADR 0014's shipments, because it is a fairness input into pay-adjacent
     * ranking and this module owns what a courier has earned. Nothing writes
     * those rows in production yet — {@code CourierAccrualService.recordDelivery}
     * has no caller — so the count is honestly zero everywhere until it does,
     * and it is the last tiebreaker before the courier id.
     */
    public List<FleetRow> fleetOnShiftAt(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT shift.courier_id,
                       shift.id AS shift_id,
                       type.offer_ttl_seconds,
                       type.max_concurrent_assignments,
                       (SELECT count(*)
                          FROM fulfillment.courier_assignment_earnings earning
                         WHERE earning.tenant_id = shift.tenant_id
                           AND earning.shift_id = shift.id) AS deliveries_this_shift
                  FROM fulfillment.courier_shifts shift
                  JOIN fulfillment.couriers courier
                    ON courier.id = shift.courier_id AND courier.tenant_id = shift.tenant_id
                  JOIN fulfillment.courier_types type
                    ON type.id = courier.courier_type_id AND type.tenant_id = courier.tenant_id
                 WHERE shift.tenant_id = :tenantId
                   AND shift.brand_id = :brandId
                   AND shift.location_id = :locationId
                   AND shift.status = 'OPEN'
                 ORDER BY shift.courier_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((ResultSet rs, int rowNumber) -> new FleetRow(
                        rs.getObject("courier_id", UUID.class),
                        rs.getObject("shift_id", UUID.class),
                        rs.getInt("offer_ttl_seconds"),
                        rs.getInt("max_concurrent_assignments"),
                        rs.getInt("deliveries_this_shift")))
                .list();
    }

    /** Shifts still open past a cut-off. The auto-close sweeper's only query. */
    public List<ShiftRow> openBefore(Instant cutoff) {
        return jdbc.sql(SELECT_SHIFT + """
                 WHERE status = 'OPEN' AND opened_at < :cutoff
                 ORDER BY opened_at
                """)
                .param("cutoff", JdbcCourierStore.utc(cutoff))
                .query(JdbcCourierShiftStore::mapShift)
                .list();
    }

    public boolean setDutyState(UUID tenantId, UUID shiftId, DutyState from, DutyState to, Instant now) {

        return jdbc.sql("""
                UPDATE fulfillment.courier_shifts
                   SET duty_state = :to, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'OPEN' AND duty_state = :from
                """)
                        .param("tenantId", tenantId)
                        .param("id", shiftId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("now", JdbcCourierStore.utc(now))
                        .update()
                == 1;
    }

    /**
     * Closes a live shift, stamping the paid and break seconds computed by the
     * caller. Conditional on the shift still being live, so a manager close and
     * a sweeper auto-close cannot both land.
     */
    public boolean close(
            UUID tenantId,
            UUID shiftId,
            ShiftStatus status,
            String closeSource,
            String closeReasonCode,
            String protectedClosePoint,
            long paidSeconds,
            long breakSeconds,
            Instant closedAt) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", shiftId);
        params.put("status", status.name());
        params.put("closeSource", closeSource);
        params.put("reasonCode", closeReasonCode);
        params.put("closePoint", protectedClosePoint);
        params.put("paidSeconds", paidSeconds);
        params.put("breakSeconds", breakSeconds);
        params.put("closedAt", JdbcCourierStore.utc(closedAt));

        return jdbc.sql("""
                UPDATE fulfillment.courier_shifts
                   SET status = :status,
                       duty_state = 'AVAILABLE',
                       close_source = :closeSource,
                       close_reason_code = :reasonCode,
                       protected_close_point = :closePoint,
                       paid_seconds = :paidSeconds,
                       break_seconds = :breakSeconds,
                       closed_at = :closedAt,
                       version = version + 1,
                       updated_at = :closedAt
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status IN ('OPEN', 'CLOSE_REQUESTED', 'RECONCILING')
                """).params(params).update() == 1;
    }

    public boolean approveHours(UUID tenantId, UUID shiftId, UUID approvalRequestId, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", shiftId);
        params.put("approvalId", approvalRequestId);
        params.put("now", JdbcCourierStore.utc(now));

        return jdbc.sql("""
                UPDATE fulfillment.courier_shifts
                   SET status = 'CLOSED',
                       approval_request_id = :approvalId,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status IN ('AWAITING_APPROVAL', 'AUTO_CLOSED')
                """).params(params).update() == 1;
    }

    // ------------------------------------------------------------------- breaks

    public void startBreak(UUID id, UUID tenantId, UUID shiftId, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_shift_breaks (
                    id, tenant_id, shift_id, started_at)
                VALUES (:id, :tenantId, :shiftId, :startedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("shiftId", shiftId)
                .param("startedAt", JdbcCourierStore.utc(startedAt))
                .update();
    }

    /**
     * @param endedBySource {@code COURIER} when the courier ended it, {@code
     *                      SHIFT_CLOSE} when a close swept it up. A manager never
     *                      appears here
     */
    public boolean endOpenBreak(UUID tenantId, UUID shiftId, String endedBySource, Instant endedAt) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_shift_breaks
                   SET ended_at = :endedAt, ended_by_source = :source
                 WHERE tenant_id = :tenantId AND shift_id = :shiftId AND ended_at IS NULL
                """)
                        .param("tenantId", tenantId)
                        .param("shiftId", shiftId)
                        .param("source", endedBySource)
                        .param("endedAt", JdbcCourierStore.utc(endedAt))
                        .update()
                == 1;
    }

    /**
     * Total break seconds on a shift, counting an open break up to the instant
     * given. A close that ignored the open break would pay for it.
     */
    public long breakSeconds(UUID tenantId, UUID shiftId, Instant asOf) {
        Long seconds = jdbc.sql("""
                SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(ended_at, :asOf) - started_at))), 0)::bigint
                  FROM fulfillment.courier_shift_breaks
                 WHERE tenant_id = :tenantId AND shift_id = :shiftId
                """)
                .param("tenantId", tenantId)
                .param("shiftId", shiftId)
                .param("asOf", JdbcCourierStore.utc(asOf))
                .query(Long.class)
                .single();
        return seconds == null ? 0L : seconds;
    }

    public List<BreakRow> breaksOf(UUID tenantId, UUID shiftId) {
        return jdbc.sql("""
                SELECT id, tenant_id, shift_id, started_at, ended_at, ended_by_source
                  FROM fulfillment.courier_shift_breaks
                 WHERE tenant_id = :tenantId AND shift_id = :shiftId
                 ORDER BY started_at
                """)
                .param("tenantId", tenantId)
                .param("shiftId", shiftId)
                .query((ResultSet rs, int rowNumber) -> new BreakRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("shift_id", UUID.class),
                        JdbcCourierStore.instant(rs.getObject("started_at", OffsetDateTime.class)),
                        JdbcCourierStore.instant(rs.getObject("ended_at", OffsetDateTime.class)),
                        rs.getString("ended_by_source")))
                .list();
    }

    // ------------------------------------------------------------ cash handovers

    public void insertHandover(HandoverRow handover) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", handover.id());
        params.put("tenantId", handover.tenantId());
        params.put("shiftId", handover.shiftId());
        params.put("courierId", handover.courierId());
        params.put("locationId", handover.locationId());
        params.put("currency", handover.currency());
        params.put("expected", handover.expectedMinor());
        params.put("now", JdbcCourierStore.utc(Instant.now()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_cash_handovers (
                    id, tenant_id, shift_id, courier_id, location_id, status, currency,
                    expected_minor, created_at, updated_at)
                VALUES (:id, :tenantId, :shiftId, :courierId, :locationId, 'PENDING', :currency,
                    :expected, :now, :now)
                """).params(params).update();
    }

    public Optional<HandoverRow> findHandover(UUID tenantId, UUID handoverId) {
        return jdbc.sql(SELECT_HANDOVER + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", handoverId)
                .query(JdbcCourierShiftStore::mapHandover)
                .optional();
    }

    public Optional<HandoverRow> findHandoverByShift(UUID tenantId, UUID shiftId) {
        return jdbc.sql(SELECT_HANDOVER + " WHERE tenant_id = :tenantId AND shift_id = :shiftId")
                .param("tenantId", tenantId)
                .param("shiftId", shiftId)
                .query(JdbcCourierShiftStore::mapHandover)
                .optional();
    }

    public boolean declare(UUID tenantId, UUID handoverId, long declaredMinor, Instant declaredAt) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_cash_handovers
                   SET declared_minor = :declared, declared_at = :declaredAt,
                       status = 'DECLARED', updated_at = :declaredAt
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                """)
                        .param("tenantId", tenantId)
                        .param("id", handoverId)
                        .param("declared", declaredMinor)
                        .param("declaredAt", JdbcCourierStore.utc(declaredAt))
                        .update()
                == 1;
    }

    public boolean confirm(
            UUID tenantId,
            UUID handoverId,
            long confirmedMinor,
            long varianceMinor,
            String status,
            String reasonCode,
            String confirmedBy,
            Instant confirmedAt) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", handoverId);
        params.put("confirmed", confirmedMinor);
        params.put("variance", varianceMinor);
        params.put("status", status);
        params.put("reasonCode", reasonCode);
        params.put("confirmedBy", confirmedBy);
        params.put("confirmedAt", JdbcCourierStore.utc(confirmedAt));

        return jdbc.sql("""
                UPDATE fulfillment.courier_cash_handovers
                   SET confirmed_minor = :confirmed, variance_minor = :variance,
                       status = :status, reason_code = :reasonCode,
                       confirmed_by = :confirmedBy, confirmed_at = :confirmedAt,
                       updated_at = :confirmedAt
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DECLARED'
                """).params(params).update() == 1;
    }

    // -------------------------------------------------------------------- rows

    public record ShiftRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID courierId,
            UUID engagementId,
            ShiftStatus status,
            DutyState dutyState,
            Instant openedAt,
            Instant closedAt,
            String openSource,
            String closeSource,
            String closeReasonCode,
            String protectedOpenPoint,
            Long paidSeconds,
            long breakSeconds,
            ShiftEnforcement enforcementMode,
            UUID enforcementPolicyId,
            Integer enforcementPolicyVersion,
            UUID approvalRequestId,
            UUID settlementPeriodId,
            int version) {}

    public record BreakRow(UUID id, UUID shiftId, Instant startedAt, Instant endedAt, String endedBySource) {}

    /**
     * One courier on shift at one branch, as dispatch needs to see them.
     *
     * <p>Carries no name, no display reference and no engagement detail. This
     * row is the raw material for a {@code FleetCandidate} that crosses a module
     * boundary, and the cheapest way to keep personal data off that boundary is
     * for it never to be selected in the first place.
     */
    public record FleetRow(
            UUID courierId, UUID shiftId, int offerTtlSeconds, int concurrencyCeiling, int deliveriesThisShift) {}

    public record HandoverRow(
            UUID id,
            UUID tenantId,
            UUID shiftId,
            UUID courierId,
            UUID locationId,
            String status,
            String currency,
            long expectedMinor,
            Long declaredMinor,
            Long confirmedMinor,
            Long varianceMinor,
            Instant declaredAt,
            String confirmedBy,
            Instant confirmedAt,
            String reasonCode) {}

    // ----------------------------------------------------------------- mapping

    private static final String SELECT_SHIFT = """
            SELECT id, tenant_id, brand_id, location_id, courier_id, engagement_id,
                   status, duty_state, opened_at, closed_at, open_source, close_source,
                   close_reason_code, protected_open_point, paid_seconds, break_seconds,
                   enforcement_mode, enforcement_policy_id, enforcement_policy_version,
                   approval_request_id, settlement_period_id, version
              FROM fulfillment.courier_shifts
            """;

    private static final String SELECT_HANDOVER = """
            SELECT id, tenant_id, shift_id, courier_id, location_id, status, currency,
                   expected_minor, declared_minor, confirmed_minor, variance_minor,
                   declared_at, confirmed_by, confirmed_at, reason_code
              FROM fulfillment.courier_cash_handovers
            """;

    private static ShiftRow mapShift(ResultSet rs, int rowNumber) throws SQLException {
        return new ShiftRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                ShiftStatus.valueOf(rs.getString("status")),
                DutyState.valueOf(rs.getString("duty_state")),
                JdbcCourierStore.instant(rs.getObject("opened_at", OffsetDateTime.class)),
                JdbcCourierStore.instant(rs.getObject("closed_at", OffsetDateTime.class)),
                rs.getString("open_source"),
                rs.getString("close_source"),
                rs.getString("close_reason_code"),
                rs.getString("protected_open_point"),
                // Nullable until the shift closes. getLong would answer zero and
                // a zero-second shift is a different fact from an open one.
                rs.getObject("paid_seconds", Long.class),
                rs.getLong("break_seconds"),
                ShiftEnforcement.valueOf(rs.getString("enforcement_mode")),
                rs.getObject("enforcement_policy_id", UUID.class),
                rs.getObject("enforcement_policy_version", Integer.class),
                rs.getObject("approval_request_id", UUID.class),
                rs.getObject("settlement_period_id", UUID.class),
                rs.getInt("version"));
    }

    private static HandoverRow mapHandover(ResultSet rs, int rowNumber) throws SQLException {
        return new HandoverRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("shift_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getLong("expected_minor"),
                rs.getObject("declared_minor", Long.class),
                rs.getObject("confirmed_minor", Long.class),
                rs.getObject("variance_minor", Long.class),
                JdbcCourierStore.instant(rs.getObject("declared_at", OffsetDateTime.class)),
                rs.getString("confirmed_by"),
                JdbcCourierStore.instant(rs.getObject("confirmed_at", OffsetDateTime.class)),
                rs.getString("reason_code"));
    }
}
