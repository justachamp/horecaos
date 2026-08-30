package uz.qoida.platform.payments.settlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.ApprovalOutcome;
import uz.qoida.platform.audit.api.ApprovalAction;
import uz.qoida.platform.audit.api.ApprovalParameters;
import uz.qoida.platform.audit.api.ApprovalRequestCommand;
import uz.qoida.platform.audit.api.ApprovalService;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.ordering.api.OrderDirectory;
import uz.qoida.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.qoida.platform.payments.api.EntitlementBenefit;
import uz.qoida.platform.payments.api.EntitlementScope;
import uz.qoida.platform.payments.application.DeliveryFeeBasisPort;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Refunds and service-recovery remedies, as bookkeeping rather than as payment
 * operations (ADR 0013, amended by the owner's decision of 2026-08-25).
 *
 * <p><strong>Nothing in this class calls a payment provider, and that is the
 * decision rather than a gap.</strong> Staff refund in Click's or Payme's own
 * cabinet, and Qoida records what they did so the order, the settlement and the
 * analytics stay whole. There is therefore no reversal call here, no uncertainty
 * window, and no {@code MANUAL_ACTION_REQUIRED} state waiting for a machine that
 * will never act.
 *
 * <p><strong>What that costs is a reconciliation gap, and the gap is written down
 * rather than implied.</strong> A recorded refund asserts that money left a
 * merchant account Qoida cannot see, on the word of one person. Three things keep
 * that from silently becoming a fact:
 *
 * <ul>
 *   <li>the money is split at the moment of recording into
 *       {@code attested_money_minor} — asserted, unobserved — and
 *       {@code platform_settled_minor}, which is the part Qoida genuinely
 *       performed in its own ledger (today, a points reversal). The two columns
 *       sum to the amount by check constraint, so no report can produce one
 *       figure without having discarded the distinction on purpose;</li>
 *   <li>every attested row is born {@code UNVERIFIED} with a source of null, and
 *       nothing in this build can move it, because ADR 0013's settlement import
 *       does not exist. {@link JdbcRemedyStore#unverifiedAttestations} is the
 *       worklist that says so, aged oldest-first;</li>
 *   <li>who asserted it is not who recorded it. {@code executed_by} and
 *       {@code executed_at} are the operator's own claim about the cabinet;
 *       {@code recorded_by} and {@code recorded_at} are Qoida's observation of
 *       the claim being made.</li>
 * </ul>
 *
 * <p><strong>The cumulative cap is not reimplemented here.</strong>
 * {@link OrderSettlementService#refund} already unwinds tenders in reverse
 * settlement order against {@code tenders.refunded_minor} (V0048) and refuses to
 * return more than was settled, and its money-last ordering is what stops a
 * points-settled order refunding as cash. A second apportionment in this class
 * would be a second opinion about the same money, and the two would diverge on
 * the first split-tender partial refund.
 *
 * <p>A delivery-fee reimbursement goes through that same call, because the fee was
 * part of the order total and was settled by the same tenders. What separates it
 * is the {@link RemedyType} it is recorded under and the second, narrower ceiling
 * of the fee actually charged — see {@link DeliveryFeeBasisPort}.
 *
 * <p><strong>An order with no settlement can take a goodwill remedy and cannot
 * take a money one, and that asymmetry is the whole of the answer for an order
 * nobody paid for.</strong> A hundred-percent-off aggregator push is a real order
 * that reaches a real kitchen and that the customer paid nothing for. It used to
 * be given a settlement anyway, tendered against the value of the promotion,
 * because {@code ck_order_settlement_total} forbids a settlement of zero and the
 * promotion's value was argued to be "the most a goodwill remedy could be worth".
 * Nothing downstream read it as a goodwill ceiling. A promotion tender is not
 * {@code settles_from_balance}, so {@link OrderSettlementService#refund} counted
 * the whole of it as money, and {@link #recordRefund} — which is not a goodwill
 * remedy — would have written a cash refund of the promotion's value, attested
 * and unverifiable, to a customer who handed over nothing.
 *
 * <p>The remedy model already had the distinction that fixes it, so no second
 * ceiling was invented. {@link RemedyType#ORDER_REFUND} and
 * {@link RemedyType#DELIVERY_FEE_REIMBURSEMENT} return money and are bounded by
 * what the tenders settled; an order with no tenders settled nothing, and
 * {@link OrderSettlementService#refund} refuses. {@link RemedyType#FUTURE_DISCOUNT}
 * is the goodwill remedy, it makes no settlement call at all, and it is therefore
 * grantable on such an order exactly as on any other — which is what
 * {@link #grantFutureDiscount} has always done and what the check constraints
 * already say, since {@code ck_remedy_money_remedy_moves_money} and
 * {@code ck_remedy_basis_matches_split} together make a money remedy with
 * {@code NOT_MONEY} basis unrepresentable.
 */
@Service
public class OrderRemedyService {

    /**
     * The most uses one future-discount grant may carry.
     *
     * <p>Bounded for the reason an unbounded manual credit is bounded in
     * {@code LoyaltyAdjustmentService}: an operator apologising for a cold pizza
     * with fifty free deliveries is a liability nobody priced, created by one
     * console click. Ten is generous for an apology and small enough that the
     * exposure stays inside the approval threshold's reach.
     */
    public static final int MAXIMUM_GRANTED_USES = 10;

    private final JdbcRemedyStore remedies;
    private final OrderSettlementService settlements;
    private final OrderDirectory orders;
    private final DeliveryFeeBasisPort deliveryFees;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;
    private final long approvalThresholdMinor;

    public OrderRemedyService(JdbcRemedyStore remedies, OrderSettlementService settlements,
            OrderDirectory orders, DeliveryFeeBasisPort deliveryFees, ApprovalService approvals,
            AuditRecorder audit, Clock clock,
            @Value("${qoida.payments.remedy-approval-threshold-minor:200000}")
            long approvalThresholdMinor) {
        this.remedies = remedies;
        this.settlements = settlements;
        this.orders = orders;
        this.deliveryFees = deliveryFees;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
        this.approvalThresholdMinor = approvalThresholdMinor;
    }

    /**
     * @param amountMinor       whole som (ADR 0018). Full and partial are the same
     *                          command: "full" is the amount that happens to equal
     *                          what is left, and a separate full-refund entry point
     *                          would be a second place for the cap to be got wrong
     * @param executedBy        the person who pressed the button in the provider's
     *                          cabinet, as stated. Not necessarily {@code actor}
     * @param executedAt        when they say they did it. Not the recording time
     * @param providerReference the reversal or cancellation identifier the cabinet
     *                          showed. Required on {@link ExecutionChannel#PROVIDER_CONSOLE}
     */
    public record RefundCommand(UUID tenantId, UUID orderId, long amountMinor, String currency,
            String reasonCode, String reason, ExecutionChannel channel, String providerReference,
            String executedBy, Instant executedAt, ActorRef actor, String idempotencyKey,
            String correlationId) {
    }

    /**
     * @param uses      how many future orders this is good for, at most
     *                  {@link #MAXIMUM_GRANTED_USES}
     * @param validFor  the window from now. An entitlement with no end is a
     *                  liability that never leaves the balance sheet
     */
    public record FutureDiscountCommand(UUID tenantId, UUID orderId, EntitlementScope appliesTo,
            EntitlementBenefit benefit, Integer percentBasisPoints, Long amountMinor,
            Long maximumMinor, int uses, Duration validFor, String reasonCode, String reason,
            ActorRef actor, String idempotencyKey, String correlationId) {
    }

    /**
     * @param remedy null when {@code approval} says a second pair of eyes is
     *               needed, in which case nothing was written and nothing moved
     */
    public record RemedyOutcome(ApprovalOutcome approval, JdbcRemedyStore.RemedyRow remedy) {

        public boolean recorded() {
            return remedy != null;
        }
    }

    // ------------------------------------------------------------- refunds

    /** Records a refund of the order, full or partial. */
    @Transactional
    public RemedyOutcome recordRefund(RefundCommand command) {
        return recordMoneyRemedy(command, RemedyType.ORDER_REFUND);
    }

    /**
     * Records a reimbursement of the delivery fee, full or partial.
     *
     * <p>Its own entry point and its own {@link RemedyType} because it is not a
     * refund of goods: it answers a different question on a report, is frequently
     * borne by a different party, and is capped by the fee rather than by the
     * order.
     */
    @Transactional
    public RemedyOutcome recordDeliveryFeeReimbursement(RefundCommand command) {
        return recordMoneyRemedy(command, RemedyType.DELIVERY_FEE_REIMBURSEMENT);
    }

    private RemedyOutcome recordMoneyRemedy(RefundCommand command, RemedyType type) {
        if (command.amountMinor() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A remedy returns a positive amount");
        }
        OrderSummary order = requireOrder(command.tenantId(), command.orderId());
        requireSameCurrency(order, command.currency());

        Long feeBasis = null;
        if (type == RemedyType.DELIVERY_FEE_REIMBURSEMENT) {
            feeBasis = checkDeliveryFeeCeiling(command);
        }

        // Weighed before anything is written, and weighed against everything this
        // order has already given back. A control that only looked at the command
        // in front of it is walked around by anyone who can count.
        long weighed = Math.addExact(command.amountMinor(),
                remedies.moneyRemediedMinor(command.tenantId(), command.orderId()));
        ApprovalOutcome approval = approvalFor(order, type, command, weighed);
        if (!approval.mayProceed()) {
            return new RemedyOutcome(approval, null);
        }
        // One signature, one refund. Spent in this transaction, so the refusal in
        // requireAttestation below — which rolls everything back — leaves the
        // approval usable rather than destroying it for an action that did not
        // happen.
        approval.consume();

        // The cap, the tender ordering and the points reversal all live here. What
        // comes back is the part the money tenders absorbed -- and that part is
        // exactly the part Qoida did not perform.
        long attested = settlements.refund(command.tenantId(), command.orderId(),
                command.amountMinor(), command.reasonCode(), command.actor().subject());
        long platformSettled = Math.subtractExact(command.amountMinor(), attested);

        // Checked after the apportionment rather than before it, because what
        // evidence is needed depends on how the tenders absorbed the amount, and
        // only the settlement service knows that. Predicting it here would be the
        // second apportionment this class exists not to have. The refusal rolls
        // the whole transaction back, tender headroom included.
        requireAttestation(command, attested);

        Instant now = clock.instant();
        JdbcRemedyStore.RemedyRow remedy = new JdbcRemedyStore.RemedyRow(
                UUID.randomUUID(), command.tenantId(), order.brandId(), command.orderId(), type,
                command.reasonCode(), command.reason(), command.currency(), command.amountMinor(),
                attested, platformSettled, basisOf(attested, platformSettled),
                attested > 0 ? command.channel() : null,
                attested > 0 ? command.providerReference() : null,
                attested > 0 ? command.executedBy() : null,
                attested > 0 ? command.executedAt() : null,
                VerificationState.UNVERIFIED, null, null, feeBasis,
                command.actor().subject(), now, approvalIdOf(approval), 1);

        remedies.insertRemedy(remedy, command.idempotencyKey(), now);
        recordAudit("payments.remedy.record", order, remedy, command.reason(), command.actor(),
                approvalIdOf(approval), command.correlationId(), Map.of(
                        "remedyType", type.name(),
                        "amountMinor", command.amountMinor(),
                        "attestedMoneyMinor", attested,
                        "platformSettledMinor", platformSettled,
                        "settlementBasis", remedy.settlementBasis().name(),
                        "reasonCode", command.reasonCode()),
                now);

        return new RemedyOutcome(approval, remedy);
    }

    /**
     * The narrower of the two ceilings on a delivery-fee reimbursement.
     *
     * @return the fee the ceiling was checked against, or null when the port could
     *         not supply one — stored as null on the remedy so a later
     *         reconciliation can find every reimbursement that was never bounded
     */
    private Long checkDeliveryFeeCeiling(RefundCommand command) {
        OptionalLong charged = deliveryFees.deliveryFeeMinor(command.tenantId(),
                command.orderId());
        if (charged.isEmpty()) {
            return null;
        }
        long fee = charged.getAsLong();
        long already = remedies.reimbursedDeliveryFeeMinor(command.tenantId(), command.orderId());
        if (Math.addExact(already, command.amountMinor()) > fee) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The delivery fee on this order was " + fee + " and " + already
                            + " has already been reimbursed against it");
        }
        return fee;
    }

    /**
     * Evidence is demanded only for money Qoida did not move.
     *
     * <p>A refund that came back entirely as points was performed here, in this
     * transaction, against the lots that were spent — there is no cabinet, no
     * operator and no reference, and asking for one produces a field somebody
     * fills in with a plausible string.
     */
    private static void requireAttestation(RefundCommand command, long attested) {
        if (attested <= 0) {
            return;
        }
        if (command.channel() == null || command.executedBy() == null
                || command.executedBy().isBlank() || command.executedAt() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Money this platform did not move is recorded only with who moved it, when, "
                            + "and through which channel");
        }
        if (command.channel() == ExecutionChannel.PROVIDER_CONSOLE
                && (command.providerReference() == null || command.providerReference().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A refund made in a provider cabinet is recorded with the reference the "
                            + "cabinet showed: without it nothing can ever match a settlement line");
        }
    }

    private static SettlementBasis basisOf(long attested, long platformSettled) {
        if (attested > 0 && platformSettled > 0) {
            return SettlementBasis.MIXED;
        }
        return attested > 0 ? SettlementBasis.OPERATOR_ATTESTED : SettlementBasis.PLATFORM_SETTLED;
    }

    // ---------------------------------------------------- future discounts

    /**
     * Grants an entitlement worth N future uses.
     *
     * <p>No settlement call, because nothing is being returned: this remedy costs
     * the tenant nothing today and may cost nothing ever. It is therefore not
     * bounded by the tender cap, and the only bound that means anything is the
     * exposure — uses times the per-use maximum — which is what the approval
     * threshold is applied to.
     */
    @Transactional
    public RemedyOutcome grantFutureDiscount(FutureDiscountCommand command) {
        OrderSummary order = requireOrder(command.tenantId(), command.orderId());
        if (order.customerAccountId() == null) {
            // A guest order has nobody to grant to. Inventing an identity here is
            // how a remedy ends up spendable by whoever next uses the device.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A future discount is granted to a customer account, and this order has none");
        }
        validate(command);

        Instant now = clock.instant();
        long exposure = exposureOf(command);
        // Weighed on the exposure, bound to the grant. Those are two different
        // questions and using one answer for both is what let a ten-use capped
        // percentage ride in on a one-use fixed amount's signature.
        ApprovalOutcome approval = approvals(order, ApprovalAction.PAYMENTS_REMEDY_FUTURE_DISCOUNT.code(),
                futureDiscountApprovalHash(command),
                command.actor(), command.reason(), exposure);
        if (!approval.mayProceed()) {
            return new RemedyOutcome(approval, null);
        }
        approval.consume();

        JdbcRemedyStore.RemedyRow remedy = new JdbcRemedyStore.RemedyRow(
                UUID.randomUUID(), command.tenantId(), order.brandId(), command.orderId(),
                RemedyType.FUTURE_DISCOUNT, command.reasonCode(), command.reason(),
                order.currency(),
                // No money columns at all. A future discount cannot be added into a
                // refund figure by a query that forgot to filter, because there is
                // nothing on the row to add.
                0L, 0L, 0L, SettlementBasis.NOT_MONEY, null, null, null, null,
                VerificationState.UNVERIFIED, null, null, null,
                command.actor().subject(), now, approvalIdOf(approval), 1);

        JdbcRemedyStore.EntitlementRow entitlement = new JdbcRemedyStore.EntitlementRow(
                UUID.randomUUID(), command.tenantId(), order.brandId(), remedy.id(),
                order.customerAccountId(), command.appliesTo(), command.benefit(),
                command.percentBasisPoints(), command.amountMinor(), command.maximumMinor(),
                order.currency(), command.uses(), 0, now, now.plus(command.validFor()),
                EntitlementStatus.ACTIVE, 1);

        remedies.insertRemedy(remedy, command.idempotencyKey(), now);
        remedies.insertEntitlement(entitlement, now);

        recordAudit("payments.remedy.future-discount", order, remedy, command.reason(),
                command.actor(), approvalIdOf(approval), command.correlationId(), Map.of(
                        "appliesTo", command.appliesTo().name(),
                        "benefit", command.benefit().name(),
                        "uses", command.uses(),
                        "exposureMinor", exposure,
                        "expiresAt", entitlement.expiresAt().toString(),
                        "reasonCode", command.reasonCode()),
                now);

        return new RemedyOutcome(approval, remedy);
    }

    /**
     * The most a grant can ever cost, which is what an approver is actually
     * deciding on.
     *
     * <p>Not zero, which is what it costs today. A remedy weighed by its immediate
     * cash cost would put every ten-use grant under the threshold, which is the
     * one shape of this remedy worth a second pair of eyes.
     */
    private static long exposureOf(FutureDiscountCommand command) {
        long perUse = command.benefit() == EntitlementBenefit.FIXED_AMOUNT
                ? command.amountMinor()
                : command.maximumMinor();
        return Math.multiplyExact(perUse, command.uses());
    }

    private static void validate(FutureDiscountCommand command) {
        if (command.uses() < 1 || command.uses() > MAXIMUM_GRANTED_USES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A future discount is good for between 1 and " + MAXIMUM_GRANTED_USES
                            + " uses");
        }
        if (command.validFor() == null || command.validFor().isNegative()
                || command.validFor().isZero()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A future discount expires: an entitlement with no end never leaves the "
                            + "liability report");
        }
        switch (command.benefit()) {
            case PERCENT -> {
                if (command.percentBasisPoints() == null || command.percentBasisPoints() < 1
                        || command.percentBasisPoints() > 10_000) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A percentage discount is between 1 and 10 000 basis points");
                }
                if (command.maximumMinor() == null || command.maximumMinor() <= 0) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A percentage discount carries a per-use maximum: without one, 20% "
                                    + "off is 2 000 som on a delivery fee and 400 000 on a "
                                    + "catering order");
                }
                if (command.amountMinor() != null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A percentage discount has no fixed amount");
                }
            }
            case FIXED_AMOUNT -> {
                if (command.amountMinor() == null || command.amountMinor() <= 0) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A fixed discount is worth a positive amount");
                }
                if (command.percentBasisPoints() != null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A fixed discount has no percentage");
                }
            }
        }
    }

    // ------------------------------------------------------ reconciliation

    /**
     * Records that something outside Qoida corroborated — or contradicted — an
     * attestation.
     *
     * <p>Nothing in this build calls it on its own. ADR 0013's settlement import
     * is the machine that would, and it does not exist, so today this is a finance
     * user closing one row by hand against a statement they are looking at. That
     * is worth having before the import ships: without it the worklist only ever
     * grows, and a control nobody can discharge is a control people learn to
     * ignore.
     */
    @Transactional
    public boolean recordVerification(UUID tenantId, UUID remedyId, VerificationState state,
            String source, ActorRef actor, String reason, String correlationId) {
        if (state == VerificationState.UNVERIFIED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A verification records what a source said, which is never 'unverified'");
        }
        if (source == null || source.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A verification names the source that corroborated it");
        }
        JdbcRemedyStore.RemedyRow remedy = remedies.findRemedy(tenantId, remedyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such remedy"));

        Instant now = clock.instant();
        if (!remedies.recordVerification(tenantId, remedyId, state, source, now)) {
            return false;
        }
        audit.record(AuditFact.of("payments.remedy.verify", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, remedy.brandId()))
                .target("payments.order_remedy", remedyId)
                .because(reason)
                .changed(Map.of("verificationState", state.name(), "source", source,
                        "attestedMoneyMinor", remedy.attestedMoneyMinor()))
                .correlatedBy(correlationId == null ? remedyId.toString() : correlationId)
                .occurredAt(now)
                .build());
        return true;
    }

    @Transactional(readOnly = true)
    public List<JdbcRemedyStore.RemedyRow> remediesOfOrder(UUID tenantId, UUID orderId) {
        requireOrder(tenantId, orderId);
        return remedies.remediesOfOrder(tenantId, orderId);
    }

    /**
     * The gap, as a list.
     *
     * @param settlingPeriod how long an attestation is given before it is worth
     *                       looking at. A refund recorded an hour ago has had no
     *                       chance to appear in anybody's settlement file
     */
    @Transactional(readOnly = true)
    public List<JdbcRemedyStore.RemedyRow> unverifiedAttestations(UUID tenantId,
            Duration settlingPeriod, int limit) {
        return remedies.unverifiedAttestations(tenantId,
                clock.instant().minus(settlingPeriod), limit);
    }

    @Transactional(readOnly = true)
    public List<JdbcRemedyStore.RemedyTotals> totalsByType(UUID tenantId, Instant from,
            Instant to) {
        return remedies.totalsByType(tenantId, from, to);
    }

    // ------------------------------------------------------------ helpers

    /**
     * The order, constrained on the tenant the caller was authorised against.
     *
     * <p>Never keyed on the order id alone: {@code OrderDirectory} answers empty
     * for another tenant's order, which is the same answer as "does not exist" and
     * deliberately so.
     */
    private OrderSummary requireOrder(UUID tenantId, UUID orderId) {
        return orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
    }

    private static void requireSameCurrency(OrderSummary order, String currency) {
        if (!order.currency().equals(currency)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The order is in " + order.currency() + " and the remedy is in " + currency);
        }
    }

    private ApprovalOutcome approvalFor(OrderSummary order, RemedyType type, RefundCommand command,
            long weighedMinor) {
        return approvals(order, ApprovalAction.PAYMENTS_REMEDY_RECORD.code(), refundApprovalHash(command, type),
                command.actor(), command.reason(), weighedMinor);
    }

    /**
     * What a signature on a money remedy actually covers.
     *
     * <p><strong>The attestation is inside it, and it was not.</strong> The hash
     * was {@code tenantId|orderId|type|amountMinor|reasonCode} — everything about
     * how much and nothing about where it went. But when the money tenders absorb
     * the amount, {@link #requireAttestation} demands a channel, an executor, a
     * time and, in a provider cabinet, a reference; all four are written onto the
     * remedy row and all four were outside the hash. So one signature on a 500 000
     * refund covered both "returned through CLICK, reference CLICK-88213, by the
     * gateway at 14:02" and "handed over in cash by me, no reference" — two
     * irreconcilable claims about where a customer's money went, and on the
     * checker's console the two requests are the same action code and the same
     * hash. Nothing about the second is reconcilable against any settlement file,
     * which is the entire point of demanding the attestation in the first place.
     *
     * <p>Now every component of {@link RefundCommand} is covered unless it is
     * named below, so a field added to the command is covered by default rather
     * than by somebody remembering. The exclusions, and why each is a decision
     * rather than an omission:
     *
     * <ul>
     *   <li>{@code actor} — the four-eyes rule governs who decides, not who
     *       executes (V0071). Binding the maker would mean a colleague picking up
     *       a shift-change handover has to fetch a second signature for the refund
     *       the checker already approved;</li>
     *   <li>{@code idempotencyKey} — a retry of the same submission carries a
     *       fresh key and is the same intended action. This is the one exclusion
     *       every call site already made deliberately, and it is why single use
     *       had to be enforced by consumption rather than by the hash;</li>
     *   <li>{@code correlationId} — a trace identifier. It changes nothing about
     *       the money, the record or the ledger.</li>
     * </ul>
     *
     * <p>{@code reason} is <em>not</em> excluded. It is the operator's stated
     * justification, it is what the checker read on the request, and it is stored
     * on the remedy row: a maker who retypes it after approval is recording
     * something the checker did not sign for.
     *
     * <p>{@code type} is not a component of the command — it is the entry point,
     * refund or delivery-fee reimbursement — so it is added by hand. Without it an
     * approval for a 300 000 refund is reusable as a 300 000 delivery
     * reimbursement on the same order.
     */
    static String refundApprovalHash(RefundCommand command, RemedyType type) {
        return ApprovalParameters.of(command)
                .excluding("actor", "idempotencyKey", "correlationId")
                .and("remedyType", type.name())
                .hash();
    }

    /**
     * What a signature on a future discount actually covers.
     *
     * <p><strong>It covered the product and neither factor.</strong> The hash was
     * built over {@link #exposureOf}, which is {@code perUse × uses}, so
     * {@code FIXED_AMOUNT 500 000 × 1 use, good for 7 days, on one order type} and
     * {@code PERCENT 10 000bp capped at 50 000 × 10 uses, good for 365 days, on
     * everything} produce the same 500 000 and the same hash. The checker signs
     * the first and the maker executes the second. Exposure is the right thing to
     * weigh against the threshold and the wrong thing to bind a signature to,
     * because arithmetic loses the factors on purpose.
     *
     * <p>Every component of {@link FutureDiscountCommand} is now covered —
     * {@code benefit}, {@code uses}, {@code percentBasisPoints},
     * {@code amountMinor}, {@code maximumMinor}, {@code appliesTo},
     * {@code validFor}, {@code reasonCode}, {@code reason} — except the three
     * excluded for the reasons given on {@link #refundApprovalHash}. Exposure is
     * deliberately not added as a segment: it is derived from covered components,
     * so it would only restate them.
     */
    static String futureDiscountApprovalHash(FutureDiscountCommand command) {
        return ApprovalParameters.of(command)
                .excluding("actor", "idempotencyKey", "correlationId")
                .and("remedyType", RemedyType.FUTURE_DISCOUNT.name())
                .hash();
    }

    private ApprovalOutcome approvals(OrderSummary order, String actionCode, String parametersHash,
            ActorRef actor, String reason, long weighedMinor) {
        if (weighedMinor < approvalThresholdMinor) {
            return new ApprovalOutcome.NotRequired();
        }
        return approvals.requireApproval(new ApprovalRequestCommand(actionCode, parametersHash,
                ResourceScope.brand(order.tenantId(), order.brandId()), actor, reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
    }

    private void recordAudit(String actionCode, OrderSummary order,
            JdbcRemedyStore.RemedyRow remedy, String reason, ActorRef actor, UUID approvalId,
            String correlationId, Map<String, Object> changes, Instant now) {

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(order.tenantId(), order.brandId()))
                .target("payments.order_remedy", remedy.id())
                .because(reason)
                .changed(changes)
                .underApproval(approvalId)
                .correlatedBy(correlationId == null ? order.orderId().toString() : correlationId)
                .occurredAt(now)
                .build());
    }

    private static UUID approvalIdOf(ApprovalOutcome outcome) {
        return outcome instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
    }

}
