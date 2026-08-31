package uz.horecaos.platform.payments.settlement;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.payments.application.CapturedMoneyPort;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.MethodRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.SettlementRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.TenderRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Turns a checkout into the settlement that discharges it (ADR 0046, ADR 0019).
 *
 * <p>This is the caller {@link OrderSettlementService#plan} was written for and
 * never had. Without it {@code payments.order_settlements} is populated only by
 * test setup, and every refund, delivery-fee reimbursement and courier cash
 * figure for a real order resolves to "the order has no settlement".
 *
 * <h2>Where the method rows come from</h2>
 *
 * <p>A tender names a row in the tenant-scoped ADR 0038 registry rather than
 * carrying a tender-type enum, and V0042 states plainly that the registry is
 * seeded per tenant by the application because it is tenant-scoped. Nothing was
 * seeding it. This class does, from {@link PaymentMethod} — the code-owned set of
 * methods the build understands — the first time a tenant tenders against one.
 * {@code registerMethod} inserts on conflict do nothing, so two concurrent
 * checkouts converge on one row rather than one each.
 *
 * <p><strong>The registry's {@code responsibility} is stated per method, never
 * derived.</strong> It was derived, from whether the method had a
 * {@code PaymentProviderType} behind it, and that produced a fiscal lie: ADR 0040's
 * {@code MARKETPLACE} method deliberately has no provider — HorecaOS holds no merchant
 * account behind an aggregator — so the derivation registered it as
 * {@code OPERATOR} and recorded the tenant as fiscally responsible for money the
 * aggregator collected, which is the opposite of what ADR 0038's own table says.
 * {@link #responsibilityOf} is now an exhaustive switch over {@link PaymentMethod}:
 * a method added to that enum without a fiscal answer fails to compile rather than
 * registering silently against the wrong party. A row a tenant has since DISABLED
 * is refused rather than reactivated — disabling a method is a decision, and
 * quietly undoing it here would make the registry unenforceable.
 *
 * <h2>When a tender becomes money</h2>
 *
 * <p>Planning writes {@code PLANNED} tenders; a refund can only unwind what
 * settled. The two moments money arrives are the two capture timings, and a
 * settlement crosses from planned to settled in one step at whichever applies:
 *
 * <ul>
 *   <li>{@link CaptureTiming#BEFORE_CONFIRMATION} — the provider has credited the
 *       order before the restaurant is asked, so the confirmation is the platform's
 *       own evidence that the money is in ({@link OrderConfirmedSettlementTrigger});</li>
 *   <li>{@link CaptureTiming#ON_HANDOVER} — cash, which becomes money when the
 *       food is handed over and not before.</li>
 * </ul>
 *
 * <p>All at once, and that is deliberate: V0042 records that
 * {@code PARTIALLY_SETTLED} never rests across a checkout boundary. A balance
 * tender therefore settles with the money tender it accompanies rather than
 * ahead of it, which also keeps the points hold releasable for as long as the
 * money is genuinely outstanding.
 *
 * <p>There is exactly one case in which it does rest, and it is named rather
 * than left to be discovered: a balance leg whose hold was resolved before the
 * money arrived cannot settle, and the settlement closes for the money that did
 * arrive. See {@link #settleWhenMoneyArrives}. The status is the record that the
 * order is short, {@link JdbcSettlementStore#settlementsRestingPartiallySettled}
 * is where an operator finds it, and the alternatives — throwing out of a
 * {@code BEFORE_COMMIT} listener, or closing {@code SETTLED} over a leg that
 * never settled — are both worse.
 */
@Service
public class CheckoutSettlementPlanner implements OrderSettlementPort, CapturedMoneyPort {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSettlementPlanner.class);

    /**
     * The one balance-backed method ADR 0046 contributes to the registry.
     *
     * <p>Not in {@link PaymentMethod}, and not by omission: that enum is the set a
     * channel may offer as <em>the</em> payment method of an order, and points can
     * never be that — an order settled entirely from points has no fiscal path.
     * Points are only ever a second tender beside one of those.
     */
    static final String POINTS_METHOD_CODE = "LOYALTY_POINTS";

    private static final String POINTS_DISPLAY_NAME = "Balance";

    /** ADR 0038: the provider's merchant account issues the receipt. */
    private static final String PARTNER_RESPONSIBILITY = "PARTNER";

    /**
     * ADR 0038: the aggregator issues it, where contracted as fiscal agent, and
     * HorecaOS records the obligation as not required with the contract reference as
     * evidence. ADR 0040 names this value explicitly for an aggregator-settled
     * order.
     */
    private static final String MARKETPLACE_RESPONSIBILITY = "MARKETPLACE";

    /**
     * ADR 0038: no external party discharges the obligation for this method.
     *
     * <p>Required by {@code ck_payment_method_balance_is_not_a_fiscal_path} for a
     * balance-settled method, which settles nothing externally and can never be the
     * party that issues a receipt.
     */
    private static final String OPERATOR_RESPONSIBILITY = "OPERATOR";

    private final JdbcSettlementStore store;
    private final OrderSettlementService settlements;
    private final Clock clock;

    public CheckoutSettlementPlanner(JdbcSettlementStore store, OrderSettlementService settlements, Clock clock) {
        this.store = store;
        this.settlements = settlements;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<PlannedSettlement> planSettlement(SettlementRequest request) {
        // Idempotent on the order rather than on the key, because the order is what
        // a settlement is unique by (uq_order_settlement_order) and because the
        // three ways this can be reached twice — a replayed checkout, a retried
        // command, a redelivered event — agree on the order id and need not agree
        // on anything else. Checked first so a repeat never reaches plan(), whose
        // insert would raise a duplicate key and whose points hold would be a
        // second hold on the same balance.
        //
        // A replay answers with the money leg of the settlement that already
        // exists, read from its rows. It has to: the caller creates the payment
        // intent from this answer, and a replay that answered with a figure
        // recomputed from the request would be the second authority all over again.
        Optional<SettlementRow> existing = store.findSettlement(request.tenantId(), request.orderId());
        if (existing.isPresent()) {
            return Optional.of(plannedFrom(request.tenantId(), existing.get()));
        }

        String requestedCode = request.paymentMethodCode() == null
                ? ""
                : request.paymentMethodCode().strip().toUpperCase(Locale.ROOT);

        Optional<PaymentMethod> method = PaymentMethod.fromCode(requestedCode);
        if (method.isEmpty()) {
            // The same conservative answer createIntent gives for an unimplemented
            // code: no row, and a warning where somebody looks. Fabricating a cash
            // tender for an order nobody said would be paid in cash would put a
            // figure in front of a courier that no customer ever agreed to.
            log.warn(
                    "Order {} planned no settlement: payment method {} is not one this build " + "can tender against.",
                    request.orderId(),
                    request.paymentMethodCode());
            return Optional.empty();
        }

        long redeemed = request.redeemFromBalanceMinor();
        if (redeemed < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A redemption settles a positive amount, or none");
        }

        List<OrderSettlementService.PlannedTender> tenders = new ArrayList<>();
        if (redeemed > 0) {
            // Named first only for readability; plan() re-orders by
            // settles_from_balance so the reservation is taken before anything
            // external is initiated whatever order a caller passes.
            tenders.add(new OrderSettlementService.PlannedTender(
                    registryIdOf(
                            request.tenantId(), POINTS_METHOD_CODE, POINTS_DISPLAY_NAME, OPERATOR_RESPONSIBILITY, true),
                    redeemed));
        }
        // Exactly the remainder, so the tenders sum to the order total. plan()
        // refuses anything else, and the refusal is the point: it happens before a
        // provider is called, so a settlement cannot be half-executed against Click.
        tenders.add(new OrderSettlementService.PlannedTender(
                registryIdOf(
                        request.tenantId(),
                        method.get().code(),
                        method.get().code(),
                        responsibilityOf(method.get()),
                        false),
                Math.subtractExact(request.totalMinor(), redeemed)));

        // A settlement request from an unattended flow (e.g. an expiry sweep)
        // carries no actor; the plan and the points reservation it may trigger
        // still need one to attribute against.
        String actor = request.actor() == null ? "system:checkout" : request.actor();
        SettlementRow settlement = settlements.plan(new OrderSettlementService.SettlementPlan(
                request.tenantId(),
                request.brandId(),
                request.orderId(),
                request.customerAccountId(),
                request.currency(),
                request.totalMinor(),
                tenders,
                request.idempotencyKey(),
                actor));

        return Optional.of(plannedFrom(request.tenantId(), settlement));
    }

    /**
     * The plan as the caller is told it, with the money leg read back from the
     * tender rows.
     *
     * <p>Read back rather than returned from the arithmetic above, and that is the
     * whole mechanism. The payment intent is created for this figure, so if it were
     * computed a second time from {@code totalMinor - redeemed} then the intent and
     * the tender would once again be two derivations of one number that nothing
     * forces to agree — which is exactly how a customer came to be charged the full
     * price of an order they had part-paid in points. What comes back here is the
     * sum of the rows the settlement actually holds: {@code plan} has already
     * refused a set that does not sum to the order total, carries no more than one
     * balance tender, and leaves at least one som of money, so this is both the
     * amount due and the amount recorded.
     */
    private PlannedSettlement plannedFrom(UUID tenantId, SettlementRow settlement) {
        long moneyDueMinor = store.tendersOf(tenantId, settlement.id()).stream()
                .filter(tender -> !tender.settlesFromBalance())
                .mapToLong(TenderRow::amountMinor)
                .reduce(0L, Math::addExact);
        return new PlannedSettlement(settlement.id(), moneyDueMinor);
    }

    @Override
    @Transactional
    public void recordHandover(UUID tenantId, UUID orderId, String actor) {
        settleWhenMoneyArrives(tenantId, orderId, CaptureTiming.ON_HANDOVER, actor);
    }

    /**
     * Unwinds the settlement of an order that ended without a handover.
     *
     * <p>{@link OrderSettlementService#fail} is the whole of the work; what is
     * here is the two cases in which it must not be called.
     *
     * <p>An order with no settlement — placed before this seam existed, or naming
     * a method this build cannot tender against — has nothing to unwind, and
     * refusing here would turn an operator's cancellation into an error they
     * cannot clear.
     *
     * <p>An order whose settlement has already settled has money against it. That
     * is a refund, decided by an operator with a reason and a remedy record, and
     * quietly failing the settlement instead would leave the tenders {@code
     * SETTLED} beneath a {@code FAILED} settlement and make the money
     * unaccountable. So this leaves it alone and the remedy path stays the only
     * way money comes back.
     *
     * <p>The same is true of a settlement that has settled <em>some</em> money.
     * That used to be unreachable — {@code PARTIALLY_SETTLED} never rested across
     * a checkout boundary — and is reachable now that a settlement can close short
     * when a released hold leaves its balance leg unsettled
     * ({@link #settleWhenMoneyArrives}). Failing it here would zero
     * {@code settled_minor} over money a provider genuinely captured, and the
     * refund that money is owed through would then find nothing to give back. So
     * the test is what has settled rather than only which status it settled into.
     */
    @Override
    @Transactional
    public void recordTerminalOutcome(UUID tenantId, UUID orderId, String reasonCode, String actor) {

        Optional<SettlementRow> found = store.findSettlement(tenantId, orderId);
        if (found.isEmpty()) {
            log.debug("Order {} has no settlement to unwind", orderId);
            return;
        }
        SettlementStatus status = found.get().status();
        if (status != SettlementStatus.PLANNED && status != SettlementStatus.PARTIALLY_SETTLED) {
            log.debug(
                    "Order {} ended with its settlement already {}; leaving it to the remedy " + "path",
                    orderId,
                    status);
            return;
        }
        if (found.get().settledMinor() > 0) {
            log.warn(
                    "Order {} ended while its settlement had {} of {} settled; leaving it to "
                            + "the remedy path rather than failing money that was collected",
                    orderId,
                    found.get().settledMinor(),
                    found.get().totalDueMinor());
            return;
        }
        settlements.fail(tenantId, orderId, reasonCode, actor);
    }

    /**
     * Settles a provider-tendered order on its confirmation.
     *
     * <p>A {@code BEFORE_CONFIRMATION} method's money is in before the restaurant
     * is asked — that is what the timing means and what holds the order in
     * {@code PAYMENT_AUTHORIZING} until it is true. The confirmation is therefore
     * the platform's own record that the tender settled, and it is a record that
     * exists for both confirmation paths rather than only the one at checkout.
     */
    @Transactional
    public void recordConfirmation(UUID tenantId, UUID orderId, String actor) {
        settleWhenMoneyArrives(tenantId, orderId, CaptureTiming.BEFORE_CONFIRMATION, actor);
    }

    /**
     * Settles a provider-tendered order the instant its money actually lands,
     * whatever has become of the order.
     *
     * <p>The same work as {@link #recordConfirmation} and a different trigger, and
     * the difference is the whole point. A confirmation is the platform's
     * <em>inference</em> that the money is in; a capture is the money being in. On
     * the happy path they coincide and this simply gets there first, after which
     * the confirmation finds every tender settled and does nothing.
     *
     * <p>Off the happy path they do not coincide at all, and the gap is where money
     * went missing. Cancel an order in {@code PAYMENT_AUTHORIZING} — an operator
     * gives up on a customer who has wandered off, or the approval times out — and
     * {@code recordTerminalOutcome} fails the settlement and drives the money
     * tender {@code PLANNED -> FAILED}. Nothing voids the provider transaction,
     * because neither adapter has a void to call, so the customer's Payme redirect
     * completes inside its twelve-hour window and captures. That capture used to
     * reach nothing: {@link #recordConfirmation} would never fire again, the tender
     * stayed {@code FAILED}, and a refund answered "a refund cannot exceed what the
     * tenders settled" for money the tenant was genuinely holding. This is the path
     * that money now takes back.
     */
    @Override
    @Transactional
    public void recordCapture(UUID tenantId, UUID orderId, String actor) {
        settleWhenMoneyArrives(tenantId, orderId, CaptureTiming.BEFORE_CONFIRMATION, actor);
    }

    /**
     * Settles every tender of the order, but only when this is the moment its
     * money tenders arrive.
     *
     * <p>A cash order is confirmed with nothing collected and must not settle
     * here; a card order is handed over long after it was paid and must not settle
     * twice. Both are decided from the registry row's capture timing rather than
     * from the order's status, so a method added to the registry later is settled
     * at the right moment without a change here.
     *
     * <h2>When a leg is already gone</h2>
     *
     * <p>A balance tender whose hold was released before the money landed is
     * {@code RELEASED} rather than {@code RESERVED} — the sweep and
     * {@link HeldTenderProgress} record it together — so it is simply not among
     * the tenders settled below, and the settlement closes
     * {@code PARTIALLY_SETTLED} for the money that genuinely arrived.
     *
     * <p>That is the deliberate answer to the third bad option. Throwing, which is
     * what {@code points.settle} used to do here, happens inside a
     * {@code BEFORE_COMMIT} listener: it rolls the confirmation back, every retry
     * of the provider callback rolls it back identically because
     * {@link #planSettlement} is idempotent and never re-reserves, and the order
     * stays in {@code PAYMENT_AUTHORIZING} for ever with the customer's money
     * captured. Settling short in silence is worse still, because a settlement
     * that closes {@code SETTLED} for the full total while a leg never settled is
     * indistinguishable from a healthy order in every report and every refund
     * calculation. Closing short and <em>saying so</em> is neither: the order
     * confirms and the kitchen proceeds, {@code settled_minor} names exactly the
     * money the platform has, the refund ceiling is that money and not a som more,
     * and the settlement rests in a status that
     * {@link JdbcSettlementStore#settlementsRestingPartiallySettled} lists for an
     * operator to resolve with the customer.
     */
    private void settleWhenMoneyArrives(UUID tenantId, UUID orderId, CaptureTiming arrivesAt, String actor) {

        Optional<SettlementRow> found = store.findSettlement(tenantId, orderId);
        if (found.isEmpty()) {
            // Not an error and not silent. An order placed before this seam existed,
            // or one that named a method this build cannot tender against, has no
            // settlement to close, and refusing here would turn that into a
            // completion the branch cannot record.
            log.debug("Order {} has no settlement to settle at {}", orderId, arrivesAt);
            return;
        }
        SettlementRow settlement = found.get();
        List<TenderRow> tenders = store.tendersOf(tenantId, settlement.id());

        List<TenderRow> money =
                tenders.stream().filter(tender -> !tender.settlesFromBalance()).toList();
        if (money.isEmpty() || !money.stream().allMatch(tender -> arrivesAt == timingOf(tenantId, tender))) {
            return;
        }

        for (TenderRow tender : tenders) {
            if (settleable(tender)) {
                settlements.recordTenderSettled(tenantId, orderId, tender.id(), actor);
            }
        }

        reportAnyShortfall(tenantId, orderId, arrivesAt);
    }

    /**
     * Whether this tender can still take the money that has just arrived.
     *
     * <p>{@code PLANNED} and {@code RESERVED} are the ordinary two. The third is
     * the one that had to be added: a <strong>money</strong> tender in
     * {@code FAILED}.
     *
     * <p>{@code FAILED} reads as terminal and for a balance tender it is — points
     * that went back to a customer are theirs, and settling them out of
     * {@code RELEASED} or {@code FAILED} would spend a hold that no longer exists.
     * On a money tender it means something narrower and much weaker: <em>the
     * platform gave up</em>. {@link OrderSettlementService#fail} writes it when an
     * order ends, and it is a statement about HorecaOS's expectation, not about the
     * provider's behaviour. The provider was never told — neither Click nor Payme
     * exposes a void for an uncaptured transaction — so a capture may still land,
     * and when it does the money is real.
     *
     * <p>Refusing it here is what stranded the money. A capture with nowhere to be
     * recorded leaves {@code settled_minor} at zero over cash the tenant is
     * holding, and every refund path is bounded by {@code settled_minor}: the
     * customer cannot be given back what the platform will not admit it has.
     * Between "a status diagram with no arrow on it" and "money that cannot be
     * returned", the arrow is cheaper, and it is drawn only where the money is
     * genuinely outside HorecaOS's ledger.
     */
    private static boolean settleable(TenderRow tender) {
        return tender.status() == TenderStatus.PLANNED
                || tender.status() == TenderStatus.RESERVED
                || (tender.status() == TenderStatus.FAILED && !tender.settlesFromBalance());
    }

    /**
     * Says out loud that a settlement closed for less than the order is worth.
     *
     * <p>Read back rather than inferred from the loop above, because the amount
     * that matters is the one the database now holds. Ids and figures only: a
     * shortfall is a fact about money, and nothing here names the customer
     * (ADR 0029).
     */
    private void reportAnyShortfall(UUID tenantId, UUID orderId, CaptureTiming arrivesAt) {
        store.findSettlement(tenantId, orderId)
                .filter(settlement -> settlement.status() != SettlementStatus.SETTLED)
                .ifPresent(settlement -> log.error(
                        "Order {} settled short at {}: {} of {} {} is settled and {} is not. A "
                                + "tender's hold was resolved before its money arrived; this "
                                + "order needs an operator, and its refund ceiling is the "
                                + "settled figure.",
                        orderId,
                        arrivesAt,
                        settlement.settledMinor(),
                        settlement.totalDueMinor(),
                        settlement.currency(),
                        Math.subtractExact(settlement.totalDueMinor(), settlement.settledMinor())));
    }

    /**
     * The capture timing behind a tender, read through the registry row it names.
     *
     * <p>A registry code this build does not implement answers {@code null} rather
     * than throwing, which excludes the tender from automatic settlement and
     * leaves it for an operator. Guessing a timing here would either settle money
     * nobody collected or leave a paid order unrefundable.
     */
    private @Nullable CaptureTiming timingOf(UUID tenantId, TenderRow tender) {
        return store.findMethod(tenantId, tender.paymentMethodId())
                .flatMap(row -> PaymentMethod.fromCode(row.code()).map(PaymentMethod::captureTiming))
                .orElse(null);
    }

    /**
     * Who ADR 0038 says discharges the fiscal obligation behind this method.
     *
     * <p>An exhaustive switch and not a derivation. The derivation this replaced —
     * "has a provider, therefore {@code PARTNER}; has none, therefore
     * {@code OPERATOR}" — reads a payment fact as a fiscal one, and the two come
     * apart precisely where it matters: {@code MARKETPLACE} has no
     * {@code PaymentProviderType} because HorecaOS holds no merchant account behind an
     * aggregator, not because the tenant issues the receipt. ADR 0038's own table
     * assigns aggregator-settled orders to {@code MARKETPLACE} and ADR 0040 names
     * that value in as many words, so it is stated here.
     *
     * <p>There is no {@code default}. A payment method added to the enum without an
     * answer here breaks the build, which is the only way this file can stop
     * registering a party who never agreed to be liable.
     *
     * <p><strong>{@code CASH} is left as this file has always registered it, and it
     * is an open question rather than a settled one.</strong> ADR 0038's table puts
     * cash under {@code TERMINAL} — a fiscal-capable POS, courier terminal or kiosk
     * belonging to the entity — and that responsibility is specified and not built:
     * {@code fiscal.fiscal_terminals} does not exist in the schema, the activation
     * precondition ADR 0038 requires ("cash requires a fiscal-capable terminal bound
     * to the location") has nowhere to run, and declaring {@code TERMINAL} here
     * would assert equipment no tenant has yet registered. Changing the declared
     * fiscal agent for this market's dominant tender is a decision for whoever owns
     * ADR 0038's rollout, not a side effect of a marketplace fix — but the value
     * below is wrong in the other direction and should not be allowed to settle
     * quietly into the registry.
     */
    private static String responsibilityOf(PaymentMethod method) {
        return switch (method) {
            case CLICK, PAYME, TELEGRAM -> PARTNER_RESPONSIBILITY;
            case MARKETPLACE -> MARKETPLACE_RESPONSIBILITY;
            case CASH -> OPERATOR_RESPONSIBILITY;
        };
    }

    /**
     * The tenant's registry row for this code, registering it the first time it is
     * tendered against.
     */
    private UUID registryIdOf(
            UUID tenantId, String code, String displayName, String responsibility, boolean settlesFromBalance) {

        Optional<MethodRow> registered = store.findMethodByCode(tenantId, code);
        if (registered.isPresent()) {
            MethodRow row = registered.get();
            if (!"ACTIVE".equals(row.status())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "This tenant has disabled the payment method " + code);
            }
            if (row.settlesFromBalance() != settlesFromBalance) {
                // The tender's snapshot of the flag is tied to the registry row by
                // composite foreign key, so a disagreement here is a registry that
                // has been edited into a shape the platform's rules do not hold for.
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "The registered payment method " + code + " does not settle the way this "
                                + "platform tenders it");
            }
            return row.id();
        }
        return store.registerMethod(tenantId, code, displayName, responsibility, settlesFromBalance, clock.instant());
    }
}
