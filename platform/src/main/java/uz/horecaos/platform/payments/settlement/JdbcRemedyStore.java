package uz.horecaos.platform.payments.settlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.payments.api.EntitlementBenefit;
import uz.horecaos.platform.payments.api.EntitlementScope;

/**
 * Remedies, the entitlements a remedy can grant, and what has been redeemed
 * against them (ADR 0013 as amended 2026-08-25).
 *
 * <p>Every query carries the tenant predicate; a remedy read by id alone would be
 * a cross-tenant read of somebody else's refund history.
 *
 * <p>Two writes here are conditional UPDATEs whose bound lives in the WHERE
 * clause rather than in Java, for the reason {@code JdbcSettlementStore.addRefunded}
 * gives: two concurrent callers that each read "one use left" both pass a check
 * made in application code, and only one of them can match a row.
 */
@Repository
public class JdbcRemedyStore {

    private final JdbcClient jdbc;

    public JdbcRemedyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- remedies

    /**
     * @param attestedMoneyMinor   the part a person says moved in a place HorecaOS
     *                             cannot see. Unverifiable by construction
     * @param platformSettledMinor the part HorecaOS performed in its own ledger and
     *                             can prove. Today only a points reversal
     * @param deliveryFeeBasisMinor the fee ceiling that was checked, or null when
     *                             {@link uz.horecaos.platform.payments.application.DeliveryFeeBasisPort}
     *                             could not supply one
     */
    public record RemedyRow(UUID id, UUID tenantId, UUID brandId, UUID orderId,
            RemedyType remedyType, String reasonCode, String reason, String currency,
            long amountMinor, long attestedMoneyMinor, long platformSettledMinor,
            SettlementBasis settlementBasis, ExecutionChannel executionChannel,
            String providerReference, String executedBy, Instant executedAt,
            VerificationState verificationState, String verificationSource, Instant verifiedAt,
            Long deliveryFeeBasisMinor, String recordedBy, Instant recordedAt,
            UUID approvalRequestId, int version) {
    }

    public void insertRemedy(RemedyRow remedy, String idempotencyKey, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", remedy.id());
        parameters.put("tenantId", remedy.tenantId());
        parameters.put("brandId", remedy.brandId());
        parameters.put("orderId", remedy.orderId());
        parameters.put("remedyType", remedy.remedyType().name());
        parameters.put("reasonCode", remedy.reasonCode());
        parameters.put("reason", remedy.reason());
        parameters.put("currency", remedy.currency());
        parameters.put("amount", remedy.amountMinor());
        parameters.put("attested", remedy.attestedMoneyMinor());
        parameters.put("platformSettled", remedy.platformSettledMinor());
        parameters.put("basis", remedy.settlementBasis().name());
        parameters.put("channel",
                remedy.executionChannel() == null ? null : remedy.executionChannel().name());
        parameters.put("providerReference", remedy.providerReference());
        parameters.put("executedBy", remedy.executedBy());
        parameters.put("executedAt", utc(remedy.executedAt()));
        parameters.put("verification", remedy.verificationState().name());
        parameters.put("feeBasis", remedy.deliveryFeeBasisMinor());
        parameters.put("recordedBy", remedy.recordedBy());
        parameters.put("recordedAt", utc(remedy.recordedAt()));
        parameters.put("approvalRequestId", remedy.approvalRequestId());
        parameters.put("idempotencyKey", idempotencyKey);
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO payments.order_remedies (
                    id, tenant_id, brand_id, order_id, remedy_type, reason_code, reason,
                    currency, amount_minor, attested_money_minor, platform_settled_minor,
                    settlement_basis, execution_channel, provider_reference, executed_by,
                    executed_at, verification_state, delivery_fee_basis_minor,
                    recorded_by, recorded_at, approval_request_id, idempotency_key,
                    version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :brandId, :orderId, :remedyType, :reasonCode, :reason,
                    :currency, :amount, :attested, :platformSettled,
                    :basis, :channel, :providerReference, :executedBy,
                    :executedAt, :verification, :feeBasis,
                    :recordedBy, :recordedAt, :approvalRequestId, :idempotencyKey,
                    1, :now, :now)
                """)
                .params(parameters)
                .update();
    }

    public Optional<RemedyRow> findRemedy(UUID tenantId, UUID remedyId) {
        return jdbc.sql("""
                SELECT * FROM payments.order_remedies
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", remedyId)
                .query(JdbcRemedyStore::toRemedy)
                .optional();
    }

    public List<RemedyRow> remediesOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT * FROM payments.order_remedies
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                 ORDER BY recorded_at
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query(JdbcRemedyStore::toRemedy)
                .list();
    }

    /**
     * What money has already been given back on this order, across every money
     * remedy on it.
     *
     * <p>Feeds the approval threshold, which is aggregate for the reason
     * {@code LoyaltyAdjustmentService} gives: five 40 000 refunds in an afternoon
     * are the 200 000 refund the operator was not allowed to make in one go.
     */
    public long moneyRemediedMinor(UUID tenantId, UUID orderId) {
        Long total = jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments.order_remedies
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query(Long.class).optional().orElse(0L);
        return total == null ? 0L : total;
    }

    /** What has already been reimbursed against this order's delivery fee. */
    public long reimbursedDeliveryFeeMinor(UUID tenantId, UUID orderId) {
        Long total = jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments.order_remedies
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                   AND remedy_type = 'DELIVERY_FEE_REIMBURSEMENT'
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query(Long.class).optional().orElse(0L);
        return total == null ? 0L : total;
    }

    /**
     * The reconciliation worklist: money HorecaOS asserted moved and nothing has
     * corroborated.
     *
     * <p>This is the query that makes the gap visible rather than implied. Ordered
     * oldest first, because age is the signal — an attestation from this morning is
     * ordinary and one from six weeks ago that no settlement file ever matched is a
     * refund that may never have happened.
     */
    public List<RemedyRow> unverifiedAttestations(UUID tenantId, Instant recordedBefore,
            int limit) {
        return jdbc.sql("""
                SELECT * FROM payments.order_remedies
                 WHERE tenant_id = :tenantId
                   AND attested_money_minor > 0
                   AND verification_state = 'UNVERIFIED'
                   AND recorded_at < :before
                 ORDER BY recorded_at
                 LIMIT :limit
                """)
                .param("tenantId", tenantId).param("before", utc(recordedBefore))
                .param("limit", limit)
                .query(JdbcRemedyStore::toRemedy)
                .list();
    }

    /**
     * One line per remedy type, and the money split by who moved it.
     *
     * <p>There is deliberately no ungrouped total on this class. A caller that
     * wants one refund figure has to decide which types belong in it and whether
     * an unverified assertion counts, and making that decision at the call site is
     * the point: the two questions have different answers for a P&amp;L and for a
     * bank reconciliation.
     */
    public record RemedyTotals(RemedyType remedyType, String currency, long remedyCount,
            long amountMinor, long attestedMoneyMinor, long platformSettledMinor,
            long unverifiedMinor) {
    }

    public List<RemedyTotals> totalsByType(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT remedy_type, currency,
                       COUNT(*) AS remedy_count,
                       COALESCE(SUM(amount_minor), 0) AS amount_minor,
                       COALESCE(SUM(attested_money_minor), 0) AS attested_money_minor,
                       COALESCE(SUM(platform_settled_minor), 0) AS platform_settled_minor,
                       COALESCE(SUM(CASE WHEN verification_state = 'UNVERIFIED'
                                         THEN attested_money_minor ELSE 0 END), 0)
                           AS unverified_minor
                  FROM payments.order_remedies
                 WHERE tenant_id = :tenantId
                   AND recorded_at >= :from AND recorded_at < :to
                 GROUP BY remedy_type, currency
                 ORDER BY remedy_type, currency
                """)
                .param("tenantId", tenantId).param("from", utc(from)).param("to", utc(to))
                .query((row, number) -> new RemedyTotals(
                        RemedyType.valueOf(row.getString("remedy_type")),
                        row.getString("currency"),
                        row.getLong("remedy_count"),
                        row.getLong("amount_minor"),
                        row.getLong("attested_money_minor"),
                        row.getLong("platform_settled_minor"),
                        row.getLong("unverified_minor")))
                .list();
    }

    /**
     * Records that something outside HorecaOS agreed, or disagreed, with an
     * attestation.
     *
     * <p>Conditional on the row still being {@code UNVERIFIED}, so a second
     * reconciliation run cannot overwrite a dispute with a confirmation.
     */
    public boolean recordVerification(UUID tenantId, UUID remedyId, VerificationState state,
            String source, Instant now) {
        return jdbc.sql("""
                UPDATE payments.order_remedies
                   SET verification_state = :state,
                       verification_source = :source,
                       verified_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND verification_state = 'UNVERIFIED'
                   AND attested_money_minor > 0
                """)
                .param("tenantId", tenantId).param("id", remedyId)
                .param("state", state.name()).param("source", source).param("now", utc(now))
                .update() == 1;
    }

    // --------------------------------------------------------- entitlements

    public record EntitlementRow(UUID id, UUID tenantId, UUID brandId, UUID remedyId,
            UUID customerAccountId, EntitlementScope appliesTo, EntitlementBenefit benefit,
            Integer percentBasisPoints, Long amountMinor, Long maximumMinor, String currency,
            int usesGranted, int usesConsumed, Instant startsAt, Instant expiresAt,
            EntitlementStatus status, int version) {

        public int usesRemaining() {
            return Math.max(0, usesGranted - usesConsumed);
        }
    }

    public void insertEntitlement(EntitlementRow entitlement, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", entitlement.id());
        parameters.put("tenantId", entitlement.tenantId());
        parameters.put("brandId", entitlement.brandId());
        parameters.put("remedyId", entitlement.remedyId());
        parameters.put("customerAccountId", entitlement.customerAccountId());
        parameters.put("appliesTo", entitlement.appliesTo().name());
        parameters.put("benefit", entitlement.benefit().name());
        parameters.put("percentBp", entitlement.percentBasisPoints());
        parameters.put("amount", entitlement.amountMinor());
        parameters.put("maximum", entitlement.maximumMinor());
        parameters.put("currency", entitlement.currency());
        parameters.put("usesGranted", entitlement.usesGranted());
        parameters.put("startsAt", utc(entitlement.startsAt()));
        parameters.put("expiresAt", utc(entitlement.expiresAt()));
        parameters.put("status", entitlement.status().name());
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO payments.remedy_entitlements (
                    id, tenant_id, brand_id, remedy_id, customer_account_id, applies_to,
                    benefit_kind, percent_basis_points, amount_minor, maximum_minor, currency,
                    uses_granted, uses_consumed, starts_at, expires_at, status,
                    version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :brandId, :remedyId, :customerAccountId, :appliesTo,
                    :benefit, :percentBp, :amount, :maximum, :currency,
                    :usesGranted, 0, :startsAt, :expiresAt, :status,
                    1, :now, :now)
                """)
                .params(parameters)
                .update();
    }

    public Optional<EntitlementRow> findEntitlement(UUID tenantId, UUID entitlementId) {
        return jdbc.sql("""
                SELECT * FROM payments.remedy_entitlements
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", entitlementId)
                .query(JdbcRemedyStore::toEntitlement)
                .optional();
    }

    /**
     * The entitlements a customer could spend at this brand right now.
     *
     * <p>Brand and customer are both predicates, never one or the other: an
     * entitlement is a promise made by one brand to one person.
     */
    public List<EntitlementRow> spendableEntitlements(UUID tenantId, UUID brandId,
            UUID customerAccountId, Instant at) {
        return jdbc.sql("""
                SELECT * FROM payments.remedy_entitlements
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND customer_account_id = :customerAccountId
                   AND status = 'ACTIVE'
                   AND uses_consumed < uses_granted
                   AND starts_at <= :at AND expires_at > :at
                 ORDER BY expires_at
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .param("customerAccountId", customerAccountId).param("at", utc(at))
                .query(JdbcRemedyStore::toEntitlement)
                .list();
    }

    /**
     * Takes one use, refusing to go past what was granted.
     *
     * <p>The window is in the statement — expiry, status and the use count all
     * checked by the database — so a redemption cannot slip through between a read
     * and a write, and an entitlement cannot be spent an eleventh time by two
     * requests that both read ten.
     */
    public boolean consumeUse(UUID tenantId, UUID entitlementId, Instant at) {
        return jdbc.sql("""
                UPDATE payments.remedy_entitlements
                   SET uses_consumed = uses_consumed + 1,
                       status = CASE WHEN uses_consumed + 1 >= uses_granted
                                     THEN 'EXHAUSTED' ELSE status END,
                       version = version + 1,
                       updated_at = :at
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'ACTIVE'
                   AND uses_consumed < uses_granted
                   AND starts_at <= :at AND expires_at > :at
                """)
                .param("tenantId", tenantId).param("id", entitlementId).param("at", utc(at))
                .update() == 1;
    }

    /** Closes out grants whose window has passed. Scheduled, never on a request path. */
    public int expireLapsedEntitlements(Instant at) {
        return jdbc.sql("""
                UPDATE payments.remedy_entitlements
                   SET status = 'EXPIRED', version = version + 1, updated_at = :at
                 WHERE status = 'ACTIVE' AND expires_at <= :at
                """)
                .param("at", utc(at))
                .update();
    }

    // --------------------------------------------------------- redemptions

    public record RedemptionRow(UUID id, UUID tenantId, UUID entitlementId, UUID orderId,
            long subtotalDiscountMinor, long deliveryDiscountMinor, String currency,
            Instant redeemedAt) {
    }

    /**
     * Records the use. Append-only, and unique per order.
     *
     * @return false when this order has already redeemed this entitlement, which
     *         is a retry rather than a second use
     */
    public boolean insertRedemption(RedemptionRow redemption) {
        return jdbc.sql("""
                INSERT INTO payments.entitlement_redemptions (
                    id, tenant_id, entitlement_id, order_id,
                    subtotal_discount_minor, delivery_discount_minor, currency, redeemed_at)
                VALUES (
                    :id, :tenantId, :entitlementId, :orderId,
                    :subtotal, :delivery, :currency, :redeemedAt)
                ON CONFLICT ON CONSTRAINT uq_entitlement_redemption_order DO NOTHING
                """)
                .param("id", redemption.id()).param("tenantId", redemption.tenantId())
                .param("entitlementId", redemption.entitlementId())
                .param("orderId", redemption.orderId())
                .param("subtotal", redemption.subtotalDiscountMinor())
                .param("delivery", redemption.deliveryDiscountMinor())
                .param("currency", redemption.currency())
                .param("redeemedAt", utc(redemption.redeemedAt()))
                .update() == 1;
    }

    public List<RedemptionRow> redemptionsOf(UUID tenantId, UUID entitlementId) {
        return jdbc.sql("""
                SELECT * FROM payments.entitlement_redemptions
                 WHERE tenant_id = :tenantId AND entitlement_id = :entitlementId
                 ORDER BY redeemed_at
                """)
                .param("tenantId", tenantId).param("entitlementId", entitlementId)
                .query((row, number) -> new RedemptionRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("entitlement_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getLong("subtotal_discount_minor"),
                        row.getLong("delivery_discount_minor"),
                        row.getString("currency"),
                        instant(row, "redeemed_at")))
                .list();
    }

    // ------------------------------------------------------------- mapping

    private static RemedyRow toRemedy(ResultSet row, int number) throws SQLException {
        String channel = row.getString("execution_channel");
        Long feeBasis = row.getObject("delivery_fee_basis_minor", Long.class);
        return new RemedyRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("order_id", UUID.class),
                RemedyType.valueOf(row.getString("remedy_type")),
                row.getString("reason_code"),
                row.getString("reason"),
                row.getString("currency"),
                row.getLong("amount_minor"),
                row.getLong("attested_money_minor"),
                row.getLong("platform_settled_minor"),
                SettlementBasis.valueOf(row.getString("settlement_basis")),
                channel == null ? null : ExecutionChannel.valueOf(channel),
                row.getString("provider_reference"),
                row.getString("executed_by"),
                instant(row, "executed_at"),
                VerificationState.valueOf(row.getString("verification_state")),
                row.getString("verification_source"),
                instant(row, "verified_at"),
                feeBasis,
                row.getString("recorded_by"),
                instant(row, "recorded_at"),
                row.getObject("approval_request_id", UUID.class),
                row.getInt("version"));
    }

    private static EntitlementRow toEntitlement(ResultSet row, int number) throws SQLException {
        return new EntitlementRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("remedy_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                EntitlementScope.valueOf(row.getString("applies_to")),
                EntitlementBenefit.valueOf(row.getString("benefit_kind")),
                row.getObject("percent_basis_points", Integer.class),
                row.getObject("amount_minor", Long.class),
                row.getObject("maximum_minor", Long.class),
                row.getString("currency"),
                row.getInt("uses_granted"),
                row.getInt("uses_consumed"),
                instant(row, "starts_at"),
                instant(row, "expires_at"),
                EntitlementStatus.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
