package uz.horecaos.platform.ordering.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.ordering.domain.OrderStatus;

/**
 * The checkout transaction (ADR 0019).
 *
 * <p>One database transaction calling only local module ports backed by the same
 * PostgreSQL instance. No provider, Kafka, Keycloak, POS or delivery call happens
 * inside it: those hold network latency inside a transaction that holds row
 * locks, and any partial failure leaves state nobody can reconstruct. Everything
 * external is a consequence of the events this transaction writes to the outbox.
 *
 * <h2>Order of operations, and why</h2>
 *
 * <p>ADR 0019 says "business rejection rolls back all steps". Rolling back would
 * also roll back the idempotency record, and a retry would then run the whole
 * sequence again against a cart that has since changed — so instead this method
 * does every read-only validation before it mutates anything, and compensates
 * explicitly for the two mutations that can precede a refusal. The effect the ADR
 * asks for is preserved: a refused checkout leaves no order, no accepted quote,
 * no committed reservation and no capacity slot.
 *
 * <ol>
 *   <li>claim the idempotency record, or return the settled result;</li>
 *   <li>lock and validate the cart, the channel, serviceability, the publication
 *       and the quote — all reads;</li>
 *   <li>consume a promo-code redemption if the quote carries one (ADR 0072),
 *       compensated on refusal;</li>
 *   <li>hold inventory (idempotent per quote, compensated on refusal);</li>
 *   <li>claim a kitchen slot under the order id that is about to exist;</li>
 *   <li>accept the quote by context hash — the point of no return;</li>
 *   <li>create the order and its immutable snapshots;</li>
 *   <li>create the provider-neutral payment intent (ADR 0013), local rows only;</li>
 *   <li>advance the state machine, arm the approval timer, enqueue the inventory
 *       process, and write the outbox events;</li>
 *   <li>convert the cart and settle the idempotency record.</li>
 * </ol>
 *
 * <p>This method is the one place that reads as that sequence; each step's own
 * "why" now lives with the package-private collaborator that carries it out:
 * {@link CheckoutAttemptLedger} (1, 10), {@link CheckoutEligibilityGuard} (2),
 * {@link CheckoutReservationStep} (3-6), {@link CheckoutOrderWriter} (7),
 * {@link CheckoutSettlementStep} (8) and {@link CheckoutProgressionStep} (9). No
 * collaborator opens a transaction of its own — every one of them runs as a plain
 * method call inside the transaction this method starts, so the boundary is
 * exactly what it was before the split.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** The one timer type this release arms. */
    public static final String APPROVAL_TIMER = "APPROVAL_DEADLINE";

    private final CheckoutAttemptLedger ledger;
    private final CheckoutEligibilityGuard eligibility;
    private final CheckoutReservationStep reservation;
    private final CheckoutOrderWriter orderWriter;
    private final CheckoutSettlementStep settlementStep;
    private final CheckoutProgressionStep progression;
    private final Clock clock;

    public CheckoutService(
            CheckoutAttemptLedger ledger,
            CheckoutEligibilityGuard eligibility,
            CheckoutReservationStep reservation,
            CheckoutOrderWriter orderWriter,
            CheckoutSettlementStep settlementStep,
            CheckoutProgressionStep progression,
            Clock clock) {
        this.ledger = ledger;
        this.eligibility = eligibility;
        this.reservation = reservation;
        this.orderWriter = orderWriter;
        this.settlementStep = settlementStep;
        this.progression = progression;
        this.clock = clock;
    }

    @Transactional
    public CheckoutResult checkout(CheckoutCommand command) {
        Instant now = clock.instant();
        String fingerprint = command.fingerprint();

        // 1. The idempotency record, or whatever an earlier attempt under the same
        // key already settled.
        UUID attemptId = UUID.randomUUID();
        Optional<CheckoutResult> alreadySettled = ledger.claim(attemptId, command, fingerprint, now);
        if (alreadySettled.isPresent()) {
            return alreadySettled.get();
        }

        // 2. Every validation, before anything is written.
        var check = eligibility.check(command, now);
        // Checking the resolved value directly (rather than check.isEligible())
        // is what lets the compiler carry the non-null fact into the rest of
        // this method; CheckoutEligibilityGuard.Result guarantees the two
        // travel together.
        CheckoutEligibilityGuard.Eligible eligible = check.eligible();
        if (eligible == null) {
            // Result's own compact constructor guarantees rejectionCode is set
            // exactly when eligible is not.
            String rejectionCode = Objects.requireNonNull(check.rejectionCode());
            return ledger.settle(attemptId, null, rejectionCode, check.rejectionDetail(), now);
        }

        // 3-6. Consume a promo-code redemption if any, hold the stock, claim a
        // kitchen slot, and accept the quote — the point of no return. Every
        // refusal from here has already compensated whatever this step
        // committed before it.
        var reserved = reservation.reserve(command, eligible, now);
        if (reserved instanceof CheckoutReservationStep.ItemsUnavailable unavailable) {
            return ledger.settleUnavailable(attemptId, unavailable.decision(), now);
        }
        if (reserved instanceof CheckoutReservationStep.Refused refused) {
            return ledger.settle(attemptId, null, refused.code(), refused.detail(), now);
        }
        var reservedOrder = (CheckoutReservationStep.Reserved) reserved;
        UUID orderId = reservedOrder.orderId();

        // 7. The order, and everything it must remember for ever.
        var written = orderWriter.create(
                command, eligible, orderId, reservedOrder.quantities().keySet(), now);

        // 8. The settlement (ADR 0046) and the provider-neutral payment intent
        // (ADR 0013), both local rows only.
        settlementStep.planAndCreateIntent(command, eligible, orderId, written.paymentFirst());

        // 9. Advance. Payment intervenes first when ADR 0013's capture timing says
        // the money must arrive before the restaurant is asked: the order waits in
        // PAYMENT_AUTHORIZING rather than reaching a kitchen unpaid. Cash and every
        // method with no online payment take the acceptance-policy path unchanged.
        //
        // The attempt is opened and the checkout surface presented by ADR 0013's
        // own endpoint, POST /orders/{orderId}/payment-sessions, which the client
        // calls once this returns PAYMENT_AUTHORIZING. Deliberately not from here:
        // ADR 0019 keeps every external call out of the checkout transaction, and
        // ADR 0013 agrees for its own reason — a Click invoice pushed inside a
        // transaction that then rolls back is a payment request on a customer's
        // phone that no row remembers, and Click's MERCHANT API has no idempotency
        // key anywhere to recover from it. The order is durable and its intent
        // committed before anything reaches a provider, so a client that never
        // calls the endpoint leaves an unpresented order rather than an unrecorded
        // charge, and a client that calls it twice is re-presented the same attempt.
        OrderStatus finalStatus;
        if (written.paymentFirst()) {
            finalStatus = progression.awaitPayment(command, orderId, now);
        } else if (written.approvalRequired()) {
            // Written's own compact constructor guarantees approvalDeadline is
            // set exactly when approvalRequired is true.
            Instant approvalDeadline = Objects.requireNonNull(written.approvalDeadline());
            finalStatus = progression.awaitApproval(
                    command, eligible.cart(), orderId, written.policy().policy(), approvalDeadline, now);
        } else {
            finalStatus = progression.confirmImmediately(
                    command, eligible.cart(), orderId, written.policy().policy(), eligible.quote(), now);
        }

        // 10. Convert the cart and settle the idempotency record.
        ledger.completeAttempt(command, eligible.cart(), attemptId, orderId, now);

        log.info(
                "Order {} ({}) created at location {} in state {}",
                orderId,
                written.publicNumber(),
                eligible.cart().locationId(),
                finalStatus);

        return new CheckoutResult(
                CheckoutResult.Outcome.CREATED,
                orderId,
                written.publicNumber(),
                finalStatus,
                ledger.orderVersion(command.tenantId(), orderId),
                null,
                null,
                List.of(),
                ledger.warnings());
    }

    /**
     * What a checkout needs.
     *
     * @param expectedCartVersion ADR 0031's expected-version precondition. Without
     *                            it a checkout can be built on a basket the
     *                            customer has since edited on another device
     * @param contextHash         the hash the cart was priced at, proving the
     *                            basket is the one that produced the total
     * @param paymentMethodCode   how the order will be paid, or null/blank when the
     *                            caller named none. Effectively required: a checkout
     *                            that names none is refused with
     *                            {@code PAYMENT_METHOD_REQUIRED} by
     *                            {@link CheckoutEligibilityGuard}, because the
     *                            settlement is planned from it and an order with
     *                            no settlement can never be refunded
     * @param redeemFromBalanceMinor how much of the total the customer asked to
     *                            settle from their points balance, in whole som, or
     *                            zero. Ordering carries the figure and decides
     *                            nothing about it: whether the account holds it,
     *                            whether the brand allows it and what share of an
     *                            order it may cover are the loyalty module's rules,
     *                            checked inside the reserving transaction
     */
    public record CheckoutCommand(
            UUID tenantId,
            UUID brandId,
            UUID cartId,
            int expectedCartVersion,
            UUID quoteId,
            String contextHash,
            String idempotencyKey,
            @Nullable String paymentMethodCode,
            long redeemFromBalanceMinor,
            String actorType,
            @Nullable String actorId,
            @Nullable String correlationId) {

        /**
         * Everything that makes this request the request it is.
         *
         * <p>Compared against the stored fingerprint when a key is reused, so a
         * client sending a genuinely different checkout under an old key is told
         * so rather than being handed the earlier order.
         */
        public String fingerprint() {
            // The redemption is part of what makes this request this request. A
            // retry that quietly asks for a different number of points is a
            // different checkout, and handing it the first order would spend a
            // balance the customer did not agree to spend.
            String canonical = "%s|%s|%d|%s|%s|%d"
                    .formatted(
                            cartId,
                            quoteId,
                            expectedCartVersion,
                            contextHash,
                            paymentMethodCode == null ? "" : paymentMethodCode.toUpperCase(Locale.ROOT),
                            redeemFromBalanceMinor);
            // Hashed to a fixed width so the stored column stays bounded however
            // long a future field grows. Comparison is equality either way; the
            // canonical string is what defines the request, and the digest is only
            // how it is stored.
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required", impossible);
            }
        }
    }

    /**
     * @param outcome    CREATED on the first success, REPLAYED when an earlier
     *                   identical request had already created it, REJECTED for a
     *                   settled business refusal
     * @param orderId    null for a REJECTED outcome: no order was ever created
     * @param publicOrderNumber null for a REJECTED outcome, for the same reason
     * @param status     null for a REJECTED outcome, for the same reason
     * @param rejectionCode null on CREATED and REPLAYED, where there is no rejection
     * @param rejectionDetail null on CREATED and REPLAYED, for the same reason
     * @param warnings   platform gaps that apply to this order; carried on every
     *                   result so an unwired port is visible on a report rather
     *                   than only in a startup log
     */
    public record CheckoutResult(
            Outcome outcome,
            @Nullable UUID orderId,
            @Nullable String publicOrderNumber,
            @Nullable OrderStatus status,
            int orderVersion,
            @Nullable String rejectionCode,
            @Nullable String rejectionDetail,
            List<AvailabilityDecision.Unavailable> unavailableItems,
            List<String> warnings) {

        public enum Outcome {
            CREATED,
            REPLAYED,
            REJECTED
        }

        static CheckoutResult rejected(String code, @Nullable String detail, List<String> warnings) {
            return new CheckoutResult(Outcome.REJECTED, null, null, null, 0, code, detail, List.of(), warnings);
        }

        public boolean created() {
            return outcome == Outcome.CREATED || outcome == Outcome.REPLAYED;
        }
    }
}
