package uz.qoida.platform.payments.settlement;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.loyalty.api.PointsRedemptionPort;
import uz.qoida.platform.payments.settlement.JdbcSettlementStore.MethodRow;
import uz.qoida.platform.payments.settlement.JdbcSettlementStore.SettlementRow;
import uz.qoida.platform.payments.settlement.JdbcSettlementStore.TenderRow;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * An order settled by an ordered set of tenders (ADR 0046).
 *
 * <p>This corrects the cardinality ADR 0013 proposed. One payment intent per
 * order with a single requested amount is right for a card payment and wrong for
 * an order paid 12 000 som from points and 82 000 in cash, which is a shape in
 * daily use in this market.
 *
 * <p>Five invariants, checked here, before any provider is called:
 *
 * <pre>
 * sum(tender amounts) == order total
 * every tender amount &gt; 0
 * at least one tender with settles_from_balance = false and amount &gt; 0
 * at most one balance tender per settlement
 * the balance tender reserves first; external tenders settle last
 * </pre>
 *
 * <p>The third is the structural form of "points cannot cover the whole order",
 * and it is the one that is not a product number. An order with no money tender
 * has no fiscal path at all — no Click payment to hang {@code submit_items} on,
 * no Payme receipt — and on a cash order it is a courier who collects nothing
 * while handing over food. The redemption cap can be raised to 90% without
 * argument; this cannot be raised at all.
 *
 * <p>The fifth is an ordering, not a preference. Releasing a points reservation
 * is a local write; reversing a captured card payment is a provider refund with
 * an uncertainty window. The other order produces a failed points debit after a
 * successful capture, which is the case where the customer has paid and the
 * order has not.
 *
 * <p>Nothing here keys on the method code. Every rule tests
 * {@code settles_from_balance}, so a second balance-backed method registered
 * later inherits all of them.
 */
@Service
public class OrderSettlementService {

    private final JdbcSettlementStore store;
    private final PointsRedemptionPort points;
    private final Clock clock;

    public OrderSettlementService(JdbcSettlementStore store, PointsRedemptionPort points,
            Clock clock) {
        this.store = store;
        this.points = points;
        this.clock = clock;
    }

    /** One line of a tender plan, as the checkout proposes it. */
    public record PlannedTender(UUID paymentMethodId, long amountMinor) {
    }

    /**
     * @param customerAccountId null for a guest checkout, which cannot include a
     *                          balance tender because there is no account for one
     *                          to draw on
     */
    public record SettlementPlan(UUID tenantId, UUID brandId, UUID orderId,
            UUID customerAccountId, String currency, long totalMinor,
            List<PlannedTender> tenders, String idempotencyKey, String actor) {
    }

    /**
     * Plans the settlement and takes the points hold.
     *
     * <p>The whole method is one transaction. If any part of it fails, the hold
     * is rolled back with it, which is why the reservation is taken here and not
     * by a caller that would have to compensate.
     */
    @Transactional
    public SettlementRow plan(SettlementPlan plan) {
        if (plan.tenders() == null || plan.tenders().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A settlement names at least one tender");
        }

        long sum = 0L;
        List<MethodRow> methods = new ArrayList<>();
        int balanceTenders = 0;
        long moneyMinor = 0L;

        for (PlannedTender tender : plan.tenders()) {
            if (tender.amountMinor() <= 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "A tender settles a positive amount");
            }
            MethodRow method = store.findMethod(plan.tenantId(), tender.paymentMethodId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                            "The tender names a payment method that is not registered"));
            if (!"ACTIVE".equals(method.status())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The tender names a payment method that is not enabled");
            }
            methods.add(method);
            sum = Math.addExact(sum, tender.amountMinor());
            if (method.settlesFromBalance()) {
                balanceTenders++;
            } else {
                moneyMinor = Math.addExact(moneyMinor, tender.amountMinor());
            }
        }

        if (sum != plan.totalMinor()) {
            // Checked before any provider call, so a plan that does not sum is
            // refused rather than half-executed against Click.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The tenders sum to " + sum + " and the order total is " + plan.totalMinor());
        }
        if (balanceTenders > 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A settlement carries at most one balance tender");
        }
        if (moneyMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An order settles at least partly with money: a zero-consideration sale "
                            + "has no fiscal path and no cash for a courier to collect");
        }

        Instant now = clock.instant();
        UUID settlementId = UUID.randomUUID();
        store.insertSettlement(new SettlementRow(settlementId, plan.tenantId(), plan.orderId(),
                plan.currency(), plan.totalMinor(), 0L, SettlementStatus.PLANNED, 1), now);

        // Balance tenders first. The sequence is the settlement order, and it is
        // what makes "reserve locally before initiating anything external" a
        // property of the stored plan rather than of whoever iterates it.
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < methods.size(); index++) {
            if (methods.get(index).settlesFromBalance()) {
                order.add(index);
            }
        }
        for (int index = 0; index < methods.size(); index++) {
            if (!methods.get(index).settlesFromBalance()) {
                order.add(index);
            }
        }

        int sequence = 1;
        for (int index : order) {
            MethodRow method = methods.get(index);
            PlannedTender planned = plan.tenders().get(index);
            UUID tenderId = UUID.randomUUID();

            store.insertTender(new TenderRow(tenderId, plan.tenantId(), settlementId, sequence,
                            method.id(), method.settlesFromBalance(), planned.amountMinor(),
                            plan.currency(), TenderStatus.PLANNED, null, null, 0L, 1),
                    plan.idempotencyKey() + ":" + sequence, now);

            if (method.settlesFromBalance()) {
                if (plan.customerAccountId() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A guest checkout cannot redeem points");
                }
                PointsRedemptionPort.PointsHold hold = points.reserve(
                        new PointsRedemptionPort.ReserveCommand(plan.tenantId(), plan.brandId(),
                                plan.customerAccountId(), plan.orderId(), tenderId,
                                planned.amountMinor(), plan.currency(),
                                plan.idempotencyKey() + ":points", plan.actor()));
                store.attachReservation(plan.tenantId(), tenderId, hold.reservationId(), now);
                store.transitionTender(plan.tenantId(), tenderId, TenderStatus.PLANNED,
                        TenderStatus.RESERVED, now);
            }
            sequence++;
        }

        return store.findSettlement(plan.tenantId(), plan.orderId()).orElseThrow();
    }

    /**
     * Records that one tender has settled, and closes the settlement when all have.
     *
     * <p><strong>A tender that cannot settle fails the whole call.</strong> The
     * balance leg is settled through {@link PointsRedemptionPort#settle}, which now
     * refuses a reservation that is no longer held rather than returning quietly,
     * and nothing here catches it: the exception rolls this transaction back, the
     * tender stays reserved, the settlement stays as it was, and — because this is
     * called inside the completion's own transaction — the handover refuses.
     *
     * <p>That is the deliberate answer to "what should happen when the points leg
     * cannot settle". The alternative is a settlement that closes {@code SETTLED}
     * for the full order total while one of its tenders never settled, which is
     * indistinguishable from a healthy order in every report, every refund
     * calculation and every reconciliation — and is exactly how this got out. An
     * operator meeting a refused handover has a problem they can see and escalate;
     * an operator meeting a silently short settlement has one they will meet again
     * a month later as an unexplained loss.
     *
     * <p><strong>A money tender settles out of whatever status it is in, and a
     * settlement is reopened out of {@code FAILED}.</strong> Not an oversight in
     * the state diagram: it is how money that arrives after the platform stopped
     * expecting it stays refundable. {@link #fail} writes {@code FAILED} on both
     * when an order ends, and that is a record of Qoida's expectation rather than
     * of the provider's behaviour — neither Click nor Payme is told, because
     * neither exposes a void for an uncaptured transaction, so a redirect the
     * customer completes an hour later captures real money against a settlement
     * that had given up on it. Refusing to record it would leave
     * {@code settled_minor} at zero over cash the tenant is holding, and every
     * refund ceiling in this class is {@code settled_minor}: the money would be
     * uncollectable by the customer and unexplainable by the tenant. Recording it
     * makes the settlement say what is true — this much arrived — and
     * {@link #refund} can then give it back.
     *
     * <p>The reopening is only ever upward. Nothing here can take a settlement
     * from {@code SETTLED} back to {@code FAILED}, and a balance tender is not
     * settled out of {@code RELEASED} or {@code FAILED} by anyone: those points
     * are the customer's again, and spending a hold that no longer exists is what
     * {@code PointsRedemptionPort.settle} now refuses outright.
     */
    @Transactional
    public SettlementStatus recordTenderSettled(UUID tenantId, UUID orderId, UUID tenderId,
            String actor) {
        Instant now = clock.instant();
        SettlementRow settlement = require(tenantId, orderId);

        List<TenderRow> tenders = store.tendersOf(tenantId, settlement.id());
        TenderRow tender = tenders.stream().filter(row -> row.id().equals(tenderId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such tender on this settlement"));

        TenderStatus from = tender.settlesFromBalance() ? TenderStatus.RESERVED : tender.status();
        if (!store.transitionTender(tenantId, tenderId, from, TenderStatus.SETTLED, now)) {
            return settlement.status();
        }
        if (tender.settlesFromBalance()) {
            points.settle(tenantId, tenderId);
        }

        long settled = tenders.stream()
                .mapToLong(row -> row.id().equals(tenderId) || row.status() == TenderStatus.SETTLED
                        ? row.amountMinor() : 0L)
                .sum();

        SettlementStatus next = settled == settlement.totalDueMinor()
                ? SettlementStatus.SETTLED
                : SettlementStatus.PARTIALLY_SETTLED;
        store.transitionSettlement(tenantId, settlement.id(), settlement.status(), next, settled,
                now);
        return next;
    }

    /**
     * Fails the settlement and gives every hold back.
     *
     * <p>A settlement never rests in {@code PARTIALLY_SETTLED} across a checkout
     * boundary, so this releases holds rather than leaving an order half-paid for
     * an operator to find.
     *
     * <p>Reached in production through
     * {@link uz.qoida.platform.ordering.api.OrderSettlementPort#recordTerminalOutcome},
     * which ordering calls on every terminal status except {@code COMPLETED}. It
     * had no caller at all, and the consequence was that a cancelled, rejected,
     * expired or payment-failed order left its settlement {@code PLANNED} and its
     * points {@code RESERVED} indefinitely — a leak that was invisible only
     * because a loyalty sweep was clearing it, and clearing live orders' holds
     * along with it.
     */
    @Transactional
    public void fail(UUID tenantId, UUID orderId, String reasonCode, String actor) {
        Instant now = clock.instant();
        SettlementRow settlement = require(tenantId, orderId);

        for (TenderRow tender : store.tendersOf(tenantId, settlement.id())) {
            if (tender.settlesFromBalance() && tender.status() == TenderStatus.RESERVED) {
                points.release(tenantId, tender.id(), reasonCode, actor);
                store.transitionTender(tenantId, tender.id(), TenderStatus.RESERVED,
                        TenderStatus.RELEASED, now);
            } else if (tender.status() == TenderStatus.PLANNED) {
                store.transitionTender(tenantId, tender.id(), TenderStatus.PLANNED,
                        TenderStatus.FAILED, now);
            }
        }
        store.transitionSettlement(tenantId, settlement.id(), settlement.status(),
                SettlementStatus.FAILED, 0L, now);
    }

    /**
     * Refunds, unwinding tenders in reverse order of settlement.
     *
     * <p>External money first, points last, and each tender refunds at most what
     * it settled. Returning points first leaves the customer with points and the
     * tenant with their cash; refunding more money than money was tendered
     * converts points to cash at par, which is the back door the whole not-money
     * argument exists to close. A customer refunded 10 000 som on a 94 000 order
     * settled 12 000 from points therefore receives 10 000 som and no points
     * back.
     *
     * @return how much of the refund the money tenders absorbed
     */
    @Transactional
    public long refund(UUID tenantId, UUID orderId, long amountMinor, String reasonCode,
            String actor) {
        Instant now = clock.instant();
        SettlementRow settlement = require(tenantId, orderId);

        List<TenderRow> tenders = new ArrayList<>(store.tendersOf(tenantId, settlement.id()));
        // Reverse of the settlement sequence, which put the balance tender first.
        tenders.sort((left, right) -> Integer.compare(right.sequence(), left.sequence()));

        long outstanding = amountMinor;
        long asMoney = 0L;
        for (TenderRow tender : tenders) {
            if (outstanding <= 0 || tender.status() != TenderStatus.SETTLED) {
                continue;
            }
            // What is LEFT on this tender, not what it originally settled. Reading
            // the original amount here made every partial refund the first one:
            // a 100 000 tender refunded 60 000 twice returned 120 000, and on a
            // points tender that is the points-to-cash conversion at par that the
            // tender ordering above exists to prevent.
            long refundable = Math.min(outstanding, tender.refundableMinor());
            if (refundable <= 0) {
                continue;
            }
            // Claim it before spending it. The bound is enforced in the statement,
            // so two concurrent refunds cannot both see the same headroom.
            if (!store.addRefunded(tenantId, tender.id(), refundable, now)) {
                throw new ApiException(ErrorCode.STALE_VERSION,
                        "This tender was refunded concurrently. Re-read the settlement and retry.");
            }
            if (tender.settlesFromBalance()) {
                points.reverse(tenantId, tender.id(), refundable, reasonCode, actor);
            } else {
                asMoney += refundable;
            }
            if (refundable == tender.refundableMinor()) {
                store.transitionTender(tenantId, tender.id(), TenderStatus.SETTLED,
                        TenderStatus.REVERSED, now);
            }
            outstanding -= refundable;
        }

        if (outstanding > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A refund cannot exceed what the tenders settled");
        }
        return asMoney;
    }

    /** The figure the courier app is shown, snapshotted onto the ADR 0014 assignment. */
    @Transactional(readOnly = true)
    public long cashDueMinor(UUID tenantId, UUID orderId, String cashMethodCode) {
        return store.cashDueMinor(tenantId, require(tenantId, orderId).id(), cashMethodCode);
    }

    private SettlementRow require(UUID tenantId, UUID orderId) {
        return store.findSettlement(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The order has no settlement"));
    }
}
