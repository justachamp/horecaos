package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.DistanceSource;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.OnTimeOutcome;
import uz.horecaos.platform.courier.domain.PayoutMethod;
import uz.horecaos.platform.courier.domain.SettlementPeriodStatus;

/**
 * The ledger, the settlement period, the earning, and the statement (ADR 0042).
 *
 * <p>The ledger has no update method and no delete method, and the migration
 * grants the application role neither. That is not caution: every statement
 * figure is the sum of its ledger lines, so a ledger the application can rewrite
 * is a statement that can be made to say something the courier was never paid.
 * A mistake is corrected by writing another entry, which leaves both facts
 * visible — the wrong one and the correction.
 *
 * <p>Period totals are recomputed from the entries at close and stored once. No
 * reader recomputes them afterwards, because two screens computing the same
 * "К оплате" independently is precisely how they come to differ.
 */
@Repository
public class JdbcCourierLedgerStore {

    private final JdbcClient jdbc;

    public JdbcCourierLedgerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------- settlement periods

    public void insertPeriod(PeriodRow period) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", period.id());
        params.put("tenantId", period.tenantId());
        params.put("courierId", period.courierId());
        params.put("engagementId", period.engagementId());
        params.put("start", period.periodStart());
        params.put("end", period.periodEnd());
        params.put("currency", period.currency());
        params.put("now", JdbcCourierStore.utc(Instant.now()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_settlement_periods (
                    id, tenant_id, courier_id, engagement_id, period_start, period_end,
                    status, currency, version, created_at, updated_at)
                VALUES (:id, :tenantId, :courierId, :engagementId, :start, :end,
                    'OPEN', :currency, 1, :now, :now)
                """).params(params).update();
    }

    public Optional<PeriodRow> findPeriod(UUID tenantId, UUID periodId) {
        return jdbc.sql(SELECT_PERIOD + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", periodId)
                .query(JdbcCourierLedgerStore::mapPeriod)
                .optional();
    }

    public Optional<PeriodRow> findOpenPeriod(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_PERIOD + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND status = 'OPEN'
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierLedgerStore::mapPeriod)
                .optional();
    }

    /**
     * The last day any period of this courier's covers.
     *
     * <p>A new period starts the day after it, never at the business date of
     * whatever entry triggered the opening: an adjustment carrying last week's
     * occurrence instant would otherwise try to open a period that overlaps the
     * closed one it is correcting.
     */
    public Optional<LocalDate> latestPeriodEnd(UUID tenantId, UUID courierId) {
        return jdbc.sql("""
                SELECT MAX(period_end)
                  FROM fulfillment.courier_settlement_periods
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(LocalDate.class)
                .optional();
    }

    public List<PeriodRow> periodsOf(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_PERIOD + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                 ORDER BY period_start DESC
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierLedgerStore::mapPeriod)
                .list();
    }

    /** Closes a period with the totals computed from its entries, once. */
    public boolean closePeriod(
            UUID tenantId,
            UUID periodId,
            int expectedVersion,
            PeriodTotals totals,
            boolean complianceFlag,
            String statementHash,
            String closedBy,
            Instant closedAt) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", periodId);
        params.put("expectedVersion", expectedVersion);
        params.put("gross", totals.grossEarningsMinor());
        params.put("adjustments", totals.adjustmentsMinor());
        params.put("cashHeld", totals.cashHeldMinor());
        params.put("payable", totals.amountPayableMinor());
        params.put("delivered", totals.deliveredCount());
        params.put("onTime", totals.onTimeCount());
        params.put("distance", totals.distanceMeters());
        params.put("paidSeconds", totals.paidSeconds());
        params.put("shifts", totals.shiftCount());
        params.put("complianceFlag", complianceFlag);
        params.put("hash", statementHash);
        params.put("closedBy", closedBy);
        params.put("closedAt", JdbcCourierStore.utc(closedAt));

        return jdbc.sql("""
                UPDATE fulfillment.courier_settlement_periods
                   SET status = 'CLOSED',
                       gross_earnings_minor = :gross,
                       adjustments_minor = :adjustments,
                       cash_held_minor = :cashHeld,
                       amount_payable_minor = :payable,
                       delivered_count = :delivered,
                       on_time_count = :onTime,
                       distance_meters = :distance,
                       paid_seconds = :paidSeconds,
                       shift_count = :shifts,
                       compliance_flag = :complianceFlag,
                       statement_hash = :hash,
                       closed_by = :closedBy,
                       closed_at = :closedAt,
                       version = version + 1,
                       updated_at = :closedAt
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                   AND status = 'OPEN'
                """).params(params).update() == 1;
    }

    public boolean markSettled(UUID tenantId, UUID periodId, Instant settledAt) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_settlement_periods
                   SET status = 'SETTLED', settled_at = :settledAt,
                       version = version + 1, updated_at = :settledAt
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'CLOSED'
                """)
                        .param("tenantId", tenantId)
                        .param("id", periodId)
                        .param("settledAt", JdbcCourierStore.utc(settledAt))
                        .update()
                == 1;
    }

    /**
     * The totals a close writes, computed from the entries and the earnings of
     * the period itself. Cash held is the negated sum of the cash entries: the
     * courier holds what he collected less what he handed over, and a variance
     * moves that position by its own explicit entry.
     */
    public PeriodTotals computeTotals(UUID tenantId, UUID periodId) {
        Map<String, Object> sums = jdbc.sql("""
                SELECT
                    COALESCE(SUM(amount_minor) FILTER (
                        WHERE entry_type IN ('DELIVERY_EARNING', 'SHIFT_EARNING')), 0)::bigint AS gross,
                    COALESCE(SUM(amount_minor) FILTER (
                        WHERE entry_type IN ('BONUS', 'PENALTY', 'PRIOR_PERIOD_ADJUSTMENT',
                                             'CORRECTION')), 0)::bigint AS adjustments,
                    COALESCE(-SUM(amount_minor) FILTER (
                        WHERE entry_type IN ('CASH_COLLECTED', 'CASH_HANDED_OVER',
                                             'CASH_VARIANCE')), 0)::bigint AS cash_held
                  FROM fulfillment.courier_ledger_entries
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query()
                .singleRow();

        Map<String, Object> counts = jdbc.sql("""
                SELECT COUNT(*)::int AS delivered,
                       COUNT(*) FILTER (WHERE on_time_outcome = 'ON_TIME')::int AS on_time,
                       COALESCE(SUM(distance_meters), 0)::bigint AS distance
                  FROM fulfillment.courier_assignment_earnings
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query()
                .singleRow();

        Map<String, Object> shifts = jdbc.sql("""
                SELECT COUNT(*)::int AS shift_count,
                       COALESCE(SUM(paid_seconds), 0)::bigint AS paid_seconds
                  FROM fulfillment.courier_shifts
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                   AND status IN ('CLOSED', 'AUTO_CLOSED', 'SETTLED')
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query()
                .singleRow();

        long gross = longOf(sums, "gross");
        long adjustments = longOf(sums, "adjustments");
        long cashHeld = longOf(sums, "cash_held");

        return new PeriodTotals(
                gross,
                adjustments,
                cashHeld,
                gross + adjustments - cashHeld,
                intOf(counts, "delivered"),
                intOf(counts, "on_time"),
                longOf(counts, "distance"),
                longOf(shifts, "paid_seconds"),
                intOf(shifts, "shift_count"));
    }

    /**
     * One aggregate column out of a single-row result. Every aggregate above is
     * wrapped in COALESCE, so an absent value is a query defect rather than an
     * empty period — the guard keeps that checked instead of assumed.
     */
    private static long longOf(Map<String, Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }

    private static int intOf(Map<String, Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).intValue();
    }

    // ------------------------------------------------------------ ledger entries

    /**
     * Appends an entry, or reports that this idempotency key already wrote one.
     *
     * <p>The unique constraint is the arbiter rather than a prior SELECT: a
     * replayed delivery event and a retried cash confirmation arrive
     * concurrently often enough that checking first and inserting second pays a
     * courier twice.
     */
    public boolean append(LedgerEntryRow entry) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", entry.id());
        params.put("tenantId", entry.tenantId());
        params.put("courierId", entry.courierId());
        params.put("periodId", entry.settlementPeriodId());
        params.put("legalEntityId", entry.legalEntityId());
        params.put("entryType", entry.entryType().name());
        params.put("amount", entry.amountMinor());
        params.put("currency", entry.currency());
        params.put("sourceType", entry.sourceType());
        params.put("sourceId", entry.sourceId());
        params.put("origin", entry.origin().name());
        params.put("reasonCode", entry.reasonCode());
        params.put("occurredAt", JdbcCourierStore.utc(entry.occurredAt()));
        params.put("idempotencyKey", entry.idempotencyKey());
        params.put("approvalId", entry.approvalRequestId());
        params.put("adjustsEntryId", entry.adjustsEntryId());
        params.put("createdBy", entry.createdBy());

        try {
            return jdbc.sql("""
                    INSERT INTO fulfillment.courier_ledger_entries (
                        id, tenant_id, courier_id, settlement_period_id, legal_entity_id,
                        entry_type, amount_minor, currency, source_type, source_id,
                        origin, reason_code, occurred_at, recorded_at, idempotency_key,
                        approval_request_id, adjusts_entry_id, created_by)
                    VALUES (:id, :tenantId, :courierId, :periodId, :legalEntityId,
                        :entryType, :amount, :currency, :sourceType, :sourceId,
                        :origin, :reasonCode, :occurredAt, now(), :idempotencyKey,
                        :approvalId, :adjustsEntryId, :createdBy)
                    ON CONFLICT ON CONSTRAINT uq_ledger_idempotency DO NOTHING
                    """).params(params).update() == 1;
        } catch (DuplicateKeyException alreadyWritten) {
            return false;
        }
    }

    public List<LedgerEntryRow> entriesOf(UUID tenantId, UUID periodId) {
        return jdbc.sql(SELECT_ENTRY + """
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                 ORDER BY occurred_at, recorded_at
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query(JdbcCourierLedgerStore::mapEntry)
                .list();
    }

    /**
     * A courier's own ledger. Takes the courier id resolved from the caller's
     * own subject, never one from a path: "a courier reads their own ledger and
     * nobody else's" has to be a property of the query.
     */
    public List<LedgerEntryRow> entriesOfCourier(UUID tenantId, UUID courierId, int limit) {
        return jdbc.sql(SELECT_ENTRY + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                 ORDER BY occurred_at DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("limit", limit)
                .query(JdbcCourierLedgerStore::mapEntry)
                .list();
    }

    public Optional<LedgerEntryRow> findEntry(UUID tenantId, UUID entryId) {
        return jdbc.sql(SELECT_ENTRY + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", entryId)
                .query(JdbcCourierLedgerStore::mapEntry)
                .optional();
    }

    /**
     * The cash a courier took during one shift, as a positive figure.
     *
     * <p>Joined through the earnings rather than stamped on the entry, because
     * the fact that ties a collection to a shift is that the courier delivered
     * that shipment during it, and stamping a shift id onto a money row would
     * make the two able to disagree.
     */
    public long cashCollectedDuringShift(UUID tenantId, UUID shiftId) {
        Long collected = jdbc.sql("""
                SELECT COALESCE(-SUM(entry.amount_minor), 0)::bigint
                  FROM fulfillment.courier_ledger_entries AS entry
                  JOIN fulfillment.courier_assignment_earnings AS earning
                    ON earning.tenant_id = entry.tenant_id
                   AND earning.shipment_id = entry.source_id
                 WHERE entry.tenant_id = :tenantId
                   AND entry.entry_type = 'CASH_COLLECTED'
                   AND earning.shift_id = :shiftId
                """)
                .param("tenantId", tenantId)
                .param("shiftId", shiftId)
                .query(Long.class)
                .single();
        return collected == null ? 0L : collected;
    }

    /** The courier's single net position: what the tenant owes, cash in hand included. */
    public long balanceMinor(UUID tenantId, UUID courierId) {
        Long balance = jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0)::bigint
                  FROM fulfillment.courier_ledger_entries
                 WHERE tenant_id = :tenantId AND courier_id = :courierId
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(Long.class)
                .single();
        return balance == null ? 0L : balance;
    }

    // ------------------------------------------------------- assignment earnings

    public boolean insertEarning(EarningRow earning) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", earning.id());
        params.put("tenantId", earning.tenantId());
        params.put("courierId", earning.courierId());
        params.put("shiftId", earning.shiftId());
        params.put("shipmentId", earning.shipmentId());
        params.put("attemptId", earning.assignmentAttemptId());
        params.put("legalEntityId", earning.legalEntityId());
        params.put("locationId", earning.locationId());
        params.put("businessDate", earning.businessDate());
        params.put("rateCardId", earning.rateCardId());
        params.put("rateCardVersion", earning.rateCardVersion());
        params.put("courierTypeId", earning.courierTypeId());
        params.put("distance", earning.distanceMeters());
        params.put("distanceSource", earning.distanceSource().name());
        params.put("onTime", earning.onTimeOutcome().name());
        params.put("promisedEnd", JdbcCourierStore.utc(earning.promisedDeliveryEnd()));
        params.put("grace", earning.graceSeconds());
        params.put("policyVersion", earning.onTimePolicyVersion());
        params.put("deliveredAt", JdbcCourierStore.utc(earning.deliveredAt()));
        params.put("handoverAt", JdbcCourierStore.utc(earning.kitchenHandoverAt()));
        params.put("pickupWindowEnd", JdbcCourierStore.utc(earning.pickupWindowEnd()));
        params.put("fixed", earning.fixedMinor());
        params.put("perOrder", earning.perOrderMinor());
        params.put("perKm", earning.perKmMinor());
        params.put("topUp", earning.minimumTopUpMinor());
        params.put("total", earning.totalMinor());
        params.put("currency", earning.currency());
        params.put("geoUnverified", earning.geoUnverified());
        params.put("pickupPoint", earning.protectedPickupPoint());
        params.put("deliveryPoint", earning.protectedDeliveryPoint());
        params.put("periodId", earning.settlementPeriodId());

        return jdbc.sql("""
                INSERT INTO fulfillment.courier_assignment_earnings (
                    id, tenant_id, courier_id, shift_id, shipment_id, assignment_attempt_id,
                    legal_entity_id, location_id, business_date,
                    rate_card_id, rate_card_version, courier_type_id,
                    distance_meters, distance_source, on_time_outcome,
                    promised_delivery_end, grace_seconds, on_time_policy_version,
                    delivered_at, kitchen_handover_at, pickup_window_end,
                    fixed_minor, per_order_minor, per_km_minor, minimum_topup_minor,
                    total_minor, currency, geo_unverified,
                    protected_pickup_point, protected_delivery_point,
                    settlement_period_id, computed_at)
                VALUES (:id, :tenantId, :courierId, :shiftId, :shipmentId, :attemptId,
                    :legalEntityId, :locationId, :businessDate,
                    :rateCardId, :rateCardVersion, :courierTypeId,
                    :distance, :distanceSource, :onTime,
                    :promisedEnd, :grace, :policyVersion,
                    :deliveredAt, :handoverAt, :pickupWindowEnd,
                    :fixed, :perOrder, :perKm, :topUp,
                    :total, :currency, :geoUnverified,
                    :pickupPoint, :deliveryPoint,
                    :periodId, now())
                ON CONFLICT ON CONSTRAINT uq_earning_attempt DO NOTHING
                """).params(params).update() == 1;
    }

    public Optional<EarningRow> findEarningByAttempt(UUID tenantId, UUID assignmentAttemptId) {
        return jdbc.sql(SELECT_EARNING + " WHERE tenant_id = :tenantId AND assignment_attempt_id = :attemptId")
                .param("tenantId", tenantId)
                .param("attemptId", assignmentAttemptId)
                .query(JdbcCourierLedgerStore::mapEarning)
                .optional();
    }

    public List<EarningRow> earningsOf(UUID tenantId, UUID periodId) {
        return jdbc.sql(SELECT_EARNING + """
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                 ORDER BY delivered_at
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query(JdbcCourierLedgerStore::mapEarning)
                .list();
    }

    /**
     * Deletes the two confirmation coordinates of every earning in periods that
     * settled before the cut-off, and answers how many rows lost them.
     *
     * <p>The only UPDATE this module makes to an earning. Everything the accrual
     * was computed from — the outcome, the flag, the distance and its source —
     * is untouched, which is what makes a dispute in month four still answerable
     * about amounts.
     */
    public int purgeConfirmationPoints(Instant settledBefore) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_assignment_earnings AS e
                   SET protected_pickup_point = NULL,
                       protected_delivery_point = NULL,
                       points_purged_at = now()
                  FROM fulfillment.courier_settlement_periods AS p
                 WHERE p.id = e.settlement_period_id
                   AND p.tenant_id = e.tenant_id
                   AND p.status = 'SETTLED'
                   AND p.settled_at < :settledBefore
                   AND e.protected_delivery_point IS NOT NULL
                """)
                .param("settledBefore", JdbcCourierStore.utc(settledBefore))
                .update();
    }

    // ----------------------------------------------------------- statements

    public void insertStatement(
            UUID id, UUID tenantId, UUID periodId, String statementHash, String documentJson, String generatedBy) {

        jdbc.sql("""
                INSERT INTO fulfillment.courier_settlement_statements (
                    id, tenant_id, settlement_period_id, statement_hash, document,
                    generated_at, generated_by)
                VALUES (:id, :tenantId, :periodId, :hash, CAST(:document AS jsonb),
                    now(), :generatedBy)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .param("hash", statementHash)
                .param("document", documentJson)
                .param("generatedBy", generatedBy)
                .update();
    }

    public Optional<StatementRow> findStatement(UUID tenantId, UUID periodId) {
        return jdbc.sql("""
                SELECT id, tenant_id, settlement_period_id, statement_hash, document::text AS document,
                       generated_at, generated_by
                  FROM fulfillment.courier_settlement_statements
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query((ResultSet rs, int rowNumber) -> new StatementRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("settlement_period_id", UUID.class),
                        rs.getString("statement_hash"),
                        rs.getString("document"),
                        // generated_at is NOT NULL: insertStatement writes now().
                        Objects.requireNonNull(
                                JdbcCourierStore.instant(rs.getObject("generated_at", OffsetDateTime.class))),
                        rs.getString("generated_by")))
                .optional();
    }

    // -------------------------------------------------------------- payouts

    public void insertPayout(
            UUID id,
            UUID tenantId,
            UUID courierId,
            UUID periodId,
            long amountMinor,
            String currency,
            PayoutMethod method,
            String authorisedBy,
            @Nullable UUID approvalRequestId) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("courierId", courierId);
        params.put("periodId", periodId);
        params.put("amount", amountMinor);
        params.put("currency", currency);
        params.put("method", method.name());
        params.put("authorisedBy", authorisedBy);
        params.put("approvalId", approvalRequestId);

        jdbc.sql("""
                INSERT INTO fulfillment.courier_payouts (
                    id, tenant_id, courier_id, settlement_period_id, amount_minor, currency,
                    method, status, authorised_by, authorised_at, approval_request_id)
                VALUES (:id, :tenantId, :courierId, :periodId, :amount, :currency,
                    :method, 'AUTHORISED', :authorisedBy, now(), :approvalId)
                """).params(params).update();
    }

    public Optional<PayoutRow> findPayout(UUID tenantId, UUID periodId) {
        return jdbc.sql("""
                SELECT id, tenant_id, courier_id, settlement_period_id, amount_minor, currency,
                       method, status, authorised_by, approval_request_id, paid_at
                  FROM fulfillment.courier_payouts
                 WHERE tenant_id = :tenantId AND settlement_period_id = :periodId
                """)
                .param("tenantId", tenantId)
                .param("periodId", periodId)
                .query((ResultSet rs, int rowNumber) -> new PayoutRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("courier_id", UUID.class),
                        rs.getObject("settlement_period_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        PayoutMethod.valueOf(rs.getString("method")),
                        rs.getString("status"),
                        rs.getString("authorised_by"),
                        rs.getObject("approval_request_id", UUID.class),
                        JdbcCourierStore.instant(rs.getObject("paid_at", OffsetDateTime.class))))
                .optional();
    }

    /** Stamps the period onto the shifts it contains, so the statement can count them. */
    public void assignShiftToPeriod(UUID tenantId, UUID shiftId, UUID periodId) {
        jdbc.sql("""
                UPDATE fulfillment.courier_shifts
                   SET settlement_period_id = :periodId, version = version + 1, updated_at = now()
                 WHERE tenant_id = :tenantId AND id = :id AND settlement_period_id IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", shiftId)
                .param("periodId", periodId)
                .update();
    }

    // -------------------------------------------------------------------- rows

    /**
     * A settlement period and its stored totals.
     *
     * <p>The close columns are null while the period is open, and
     * {@code settledAt} until the payout marks it settled — a period's history
     * is written by the transitions, never ahead of them.
     */
    public record PeriodRow(
            UUID id,
            UUID tenantId,
            UUID courierId,
            UUID engagementId,
            LocalDate periodStart,
            LocalDate periodEnd,
            SettlementPeriodStatus status,
            String currency,
            long grossEarningsMinor,
            long adjustmentsMinor,
            long cashHeldMinor,
            long amountPayableMinor,
            int deliveredCount,
            int onTimeCount,
            long distanceMeters,
            long paidSeconds,
            int shiftCount,
            boolean complianceFlag,
            @Nullable String statementHash,
            @Nullable String closedBy,
            @Nullable Instant closedAt,
            @Nullable Instant settledAt,
            int version) {}

    public record PeriodTotals(
            long grossEarningsMinor,
            long adjustmentsMinor,
            long cashHeldMinor,
            long amountPayableMinor,
            int deliveredCount,
            int onTimeCount,
            long distanceMeters,
            long paidSeconds,
            int shiftCount) {}

    /**
     * One immutable ledger line.
     *
     * <p>{@code legalEntityId} is null where no ADR 0038 entity resolves;
     * {@code sourceId} where the entry has no source aggregate; the reason
     * code, approval and correction references only exist on the entries whose
     * kind demands them.
     */
    public record LedgerEntryRow(
            UUID id,
            UUID tenantId,
            UUID courierId,
            UUID settlementPeriodId,
            @Nullable UUID legalEntityId,
            LedgerEntryType entryType,
            long amountMinor,
            String currency,
            String sourceType,
            @Nullable UUID sourceId,
            AdjustmentOrigin origin,
            @Nullable String reasonCode,
            Instant occurredAt,
            Instant recordedAt,
            String idempotencyKey,
            @Nullable UUID approvalRequestId,
            @Nullable UUID adjustsEntryId,
            String createdBy) {}

    /**
     * What one delivered assignment earned, and everything it was computed from.
     *
     * <p>{@code shiftId} is null for a delivery outside any shift; the promise
     * and kitchen instants where ADR 0014's plan recorded none; the two
     * protected points where no confirmation coordinate was captured — and both
     * become null again once {@code pointsPurgedAt} records the retention
     * deletion.
     */
    public record EarningRow(
            UUID id,
            UUID tenantId,
            UUID courierId,
            @Nullable UUID shiftId,
            UUID shipmentId,
            UUID assignmentAttemptId,
            @Nullable UUID legalEntityId,
            UUID locationId,
            LocalDate businessDate,
            UUID rateCardId,
            int rateCardVersion,
            UUID courierTypeId,
            int distanceMeters,
            DistanceSource distanceSource,
            OnTimeOutcome onTimeOutcome,
            @Nullable Instant promisedDeliveryEnd,
            int graceSeconds,
            int onTimePolicyVersion,
            Instant deliveredAt,
            @Nullable Instant kitchenHandoverAt,
            @Nullable Instant pickupWindowEnd,
            long fixedMinor,
            long perOrderMinor,
            long perKmMinor,
            long minimumTopUpMinor,
            long totalMinor,
            String currency,
            boolean geoUnverified,
            @Nullable String protectedPickupPoint,
            @Nullable String protectedDeliveryPoint,
            @Nullable Instant pointsPurgedAt,
            UUID settlementPeriodId) {}

    public record StatementRow(
            UUID id,
            UUID settlementPeriodId,
            String statementHash,
            String document,
            Instant generatedAt,
            String generatedBy) {}

    /**
     * A recorded payout authorisation.
     *
     * <p>{@code approvalRequestId} exists only when the period's compliance
     * flag demanded four eyes; {@code paidAt} only once somebody has moved the
     * money on whatever rail the method names.
     */
    public record PayoutRow(
            UUID id,
            UUID courierId,
            UUID settlementPeriodId,
            long amountMinor,
            String currency,
            PayoutMethod method,
            String status,
            String authorisedBy,
            @Nullable UUID approvalRequestId,
            @Nullable Instant paidAt) {}

    // ----------------------------------------------------------------- mapping

    private static final String SELECT_PERIOD = """
            SELECT id, tenant_id, courier_id, engagement_id, period_start, period_end, status,
                   currency, gross_earnings_minor, adjustments_minor, cash_held_minor,
                   amount_payable_minor, delivered_count, on_time_count, distance_meters,
                   paid_seconds, shift_count, compliance_flag, statement_hash, closed_by,
                   closed_at, settled_at, version
              FROM fulfillment.courier_settlement_periods
            """;

    private static final String SELECT_ENTRY = """
            SELECT id, tenant_id, courier_id, settlement_period_id, legal_entity_id, entry_type,
                   amount_minor, currency, source_type, source_id, origin, reason_code,
                   occurred_at, recorded_at, idempotency_key, approval_request_id,
                   adjusts_entry_id, created_by
              FROM fulfillment.courier_ledger_entries
            """;

    private static final String SELECT_EARNING = """
            SELECT id, tenant_id, courier_id, shift_id, shipment_id, assignment_attempt_id,
                   legal_entity_id, location_id, business_date, rate_card_id, rate_card_version,
                   courier_type_id, distance_meters, distance_source, on_time_outcome,
                   promised_delivery_end, grace_seconds, on_time_policy_version, delivered_at,
                   kitchen_handover_at, pickup_window_end, fixed_minor, per_order_minor,
                   per_km_minor, minimum_topup_minor, total_minor, currency, geo_unverified,
                   protected_pickup_point, protected_delivery_point, points_purged_at,
                   settlement_period_id
              FROM fulfillment.courier_assignment_earnings
            """;

    private static PeriodRow mapPeriod(ResultSet rs, int rowNumber) throws SQLException {
        return new PeriodRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                SettlementPeriodStatus.valueOf(rs.getString("status")),
                rs.getString("currency"),
                rs.getLong("gross_earnings_minor"),
                rs.getLong("adjustments_minor"),
                rs.getLong("cash_held_minor"),
                rs.getLong("amount_payable_minor"),
                rs.getInt("delivered_count"),
                rs.getInt("on_time_count"),
                rs.getLong("distance_meters"),
                rs.getLong("paid_seconds"),
                rs.getInt("shift_count"),
                rs.getBoolean("compliance_flag"),
                rs.getString("statement_hash"),
                rs.getString("closed_by"),
                JdbcCourierStore.instant(rs.getObject("closed_at", OffsetDateTime.class)),
                JdbcCourierStore.instant(rs.getObject("settled_at", OffsetDateTime.class)),
                rs.getInt("version"));
    }

    private static LedgerEntryRow mapEntry(ResultSet rs, int rowNumber) throws SQLException {
        return new LedgerEntryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getObject("settlement_period_id", UUID.class),
                rs.getObject("legal_entity_id", UUID.class),
                LedgerEntryType.valueOf(rs.getString("entry_type")),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                AdjustmentOrigin.valueOf(rs.getString("origin")),
                rs.getString("reason_code"),
                // Both instants are NOT NULL columns; the guards keep that
                // checked rather than assumed now the helper is @Nullable.
                Objects.requireNonNull(JdbcCourierStore.instant(rs.getObject("occurred_at", OffsetDateTime.class))),
                Objects.requireNonNull(JdbcCourierStore.instant(rs.getObject("recorded_at", OffsetDateTime.class))),
                rs.getString("idempotency_key"),
                rs.getObject("approval_request_id", UUID.class),
                rs.getObject("adjusts_entry_id", UUID.class),
                rs.getString("created_by"));
    }

    private static EarningRow mapEarning(ResultSet rs, int rowNumber) throws SQLException {
        return new EarningRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getObject("shift_id", UUID.class),
                rs.getObject("shipment_id", UUID.class),
                rs.getObject("assignment_attempt_id", UUID.class),
                rs.getObject("legal_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                rs.getObject("rate_card_id", UUID.class),
                rs.getInt("rate_card_version"),
                rs.getObject("courier_type_id", UUID.class),
                rs.getInt("distance_meters"),
                DistanceSource.valueOf(rs.getString("distance_source")),
                OnTimeOutcome.valueOf(rs.getString("on_time_outcome")),
                JdbcCourierStore.instant(rs.getObject("promised_delivery_end", OffsetDateTime.class)),
                rs.getInt("grace_seconds"),
                rs.getInt("on_time_policy_version"),
                // delivered_at is NOT NULL: an earning exists because a delivery happened.
                Objects.requireNonNull(JdbcCourierStore.instant(rs.getObject("delivered_at", OffsetDateTime.class))),
                JdbcCourierStore.instant(rs.getObject("kitchen_handover_at", OffsetDateTime.class)),
                JdbcCourierStore.instant(rs.getObject("pickup_window_end", OffsetDateTime.class)),
                rs.getLong("fixed_minor"),
                rs.getLong("per_order_minor"),
                rs.getLong("per_km_minor"),
                rs.getLong("minimum_topup_minor"),
                rs.getLong("total_minor"),
                rs.getString("currency"),
                rs.getBoolean("geo_unverified"),
                rs.getString("protected_pickup_point"),
                rs.getString("protected_delivery_point"),
                JdbcCourierStore.instant(rs.getObject("points_purged_at", OffsetDateTime.class)),
                rs.getObject("settlement_period_id", UUID.class));
    }
}
