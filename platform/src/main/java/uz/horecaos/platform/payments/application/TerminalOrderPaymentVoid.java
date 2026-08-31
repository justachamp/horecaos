package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderExpired;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStateMachine;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTransactionType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderOutcome;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * Tries to close the provider's side of an order that ended without being paid
 * (ADR 0013, ADR 0007, ADR 0019).
 *
 * <p><strong>Nothing in {@code payments} listened for an order ending, and the
 * consequence was money the platform could neither stop nor give back.</strong>
 * Cancel or expire an order in {@code PAYMENT_AUTHORIZING} and ordering does the
 * whole of its job: the settlement is failed and the points hold released. The
 * payment is not touched. The customer's Payme redirect is still live — Payme
 * gives a transaction twelve hours — and completing it captures real money
 * against an order the platform has written off.
 *
 * <p>This is the half of the answer that tries to prevent it. The other half is
 * {@link CapturedMoneyPort}, which makes a capture that lands anyway refundable
 * rather than stranded, and it is the half that actually has to work, because
 * <em>neither provider offers a void</em>:
 *
 * <ul>
 *   <li>Payme's Merchant API is inbound only. HorecaOS cannot call
 *       {@code CancelTransaction}; Payme calls it on HorecaOS. The adapter says so
 *       by answering {@code REJECTED} rather than by pretending, and the real
 *       lever is {@code CheckPerformTransaction}, which refuses a
 *       {@code CreateTransaction} for an order that is no longer payable.</li>
 *   <li>Click's only reversal reaches a payment that has already been captured
 *       and needs a {@code payment_id} that an uncaptured attempt does not have.
 *       It also requires the binding to be configured for reversals at all.</li>
 * </ul>
 *
 * <p>So this asks once, records what it was told, and stops. It does not
 * <em>ever</em> ask twice.
 *
 * <h2>ADR 0007, which is the part that is easy to get wrong</h2>
 *
 * <p>A void is a mutating call. A void whose response was lost may have voided
 * the transaction or may not, and repeating it is not idempotent on either
 * provider — Click carries no idempotency key on any call. So an uncertain answer
 * marks the attempt {@code UNCERTAIN} with its named resolver and a deadline, and
 * the resolver, which only ever reads, is the single thing allowed to follow it.
 * An attempt that is <em>already</em> {@code UNCERTAIN} when the order ends is
 * left completely alone for the same reason: it is already somebody's question,
 * and a void issued on top of it would be exactly the blind retry the state
 * exists to forbid.
 *
 * <p><strong>A rejected or unsupported void leaves the attempt open on purpose.</strong>
 * Failing it locally would feel tidier and would be a lie with a cost: the
 * provider is still holding a live transaction, and an attempt marked
 * {@code FAILED} here is one the operations console stops showing while the
 * customer can still pay it. Left open, the capture that arrives is recorded
 * normally, reaches {@link CapturedMoneyPort}, and the money is refundable.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT}, unlike the settlement triggers beside
 * it, because this calls a provider over HTTP and ADR 0019 keeps every provider
 * call out of a business transaction. The order's cancellation is already durable
 * when this runs; if this then fails, the order is still cancelled and the
 * payment is still findable through the uncertain-attempt worklist.
 */
@Component
public class TerminalOrderPaymentVoid {

    private static final Logger log = LoggerFactory.getLogger(TerminalOrderPaymentVoid.class);

    private static final String ACTOR = "order-ended";

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final PaymentAttemptService attemptService;
    private final PaymentBindingResolver bindings;
    private final Map<PaymentProviderType, PaymentProviderPort> providers;
    private final Clock clock;

    public TerminalOrderPaymentVoid(
            JdbcPaymentIntentStore intents,
            JdbcPaymentAttemptStore attempts,
            PaymentAttemptService attemptService,
            PaymentBindingResolver bindings,
            List<PaymentProviderPort> providerPorts,
            Clock clock) {
        this.intents = intents;
        this.attempts = attempts;
        this.attemptService = attemptService;
        this.bindings = bindings;
        this.providers =
                providerPorts.stream().collect(Collectors.toMap(PaymentProviderPort::providerType, port -> port));
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelled(OrderCancelled event) {
        // A cancellation is not required to carry a reason code (unlike expiry,
        // which always means the same thing); fall back to a generic one so the
        // void still records something rather than requiring a non-null value
        // the event genuinely may not have.
        String reasonCode = event.reasonCode() == null ? "ORDER_CANCELLED" : event.reasonCode();
        voidAnyLivePayment(event.tenantId().value(), event.orderId(), reasonCode);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderExpired(OrderExpired event) {
        voidAnyLivePayment(event.tenantId().value(), event.orderId(), "ORDER_EXPIRED");
    }

    /**
     * Ids and codes only. A payment is full of personal data (ADR 0029) and none
     * of it appears in a log line, an exception message or a metric here.
     */
    public void voidAnyLivePayment(UUID tenantId, UUID orderId, String reasonCode) {
        Optional<PaymentIntent> live = intents.findLiveForOrder(tenantId, orderId);
        if (live.isEmpty()) {
            return;
        }
        PaymentIntent intent = live.get();
        if (intent.status() == PaymentIntentStatus.PAID) {
            // The money is in and the settlement holds it. Giving it back is a
            // remedy, decided by an operator with a reason, and never a side
            // effect of a status change.
            log.info(
                    "Order {} ended with intent {} already PAID; the money stays on the "
                            + "settlement and comes back through the remedy path.",
                    orderId,
                    intent.id());
            return;
        }

        Optional<PaymentAttempt> open = attempts.findOpenForIntent(tenantId, intent.id());
        if (open.isEmpty()) {
            cancelIntent(intent);
            return;
        }
        PaymentAttempt attempt = open.get();

        if (attempt.status() == PaymentAttemptStatus.UNCERTAIN) {
            // Already a question about money, already owned by a named resolver.
            // ADR 0007: the resolver is the only thing that may follow uncertainty.
            log.warn(
                    "Order {} ended while attempt {} was UNCERTAIN; leaving it to its resolver "
                            + "rather than issuing a void on top of a call whose answer was lost.",
                    orderId,
                    attempt.id());
            return;
        }

        Optional<UUID> seller = intent.legalEntity();
        Optional<ProviderBinding> binding = seller.isEmpty()
                ? Optional.empty()
                : bindings.resolve(tenantId, seller.get(), attempt.providerType(), attempt.businessDate());
        PaymentProviderPort provider = providers.get(attempt.providerType());
        if (binding.isEmpty() || provider == null || !binding.get().supportsReversal()) {
            unvoidable(orderId, attempt, "no void surface is configured for this binding");
            return;
        }

        ProviderOutcome outcome = provider.reverse(attempt, binding.get(), reasonCode);
        switch (outcome.classification()) {
            case SUCCESS -> recordVoided(attempt, outcome);
            case UNCERTAIN -> {
                // The transaction may or may not be void. Never sent again; the
                // resolver reads, and reading is the only safe thing left.
                attemptService.markUncertain(attempt, outcome.failureCode());
                log.warn(
                        "The void of attempt {} for ended order {} was not answered; it is "
                                + "UNCERTAIN and will be resolved by query, never by a second void.",
                        attempt.id(),
                        orderId);
            }
            case REJECTED, RETRYABLE -> unvoidable(
                    orderId,
                    attempt,
                    outcome.failureCode() == null ? "provider gave no failure code" : outcome.failureCode());
        }
    }

    /**
     * Records a void the provider confirmed.
     *
     * <p>{@code CANCEL} and not {@code REVERSE}: nothing was captured, so nothing
     * moved back, and recording a reversal here would put a refund in the
     * settlement reconciliation that no money ever matched. The attempt state
     * machine agrees — {@code RESERVED} may reach {@code CANCELLED} and may not
     * reach {@code REVERSED} — and a status it refuses is left alone rather than
     * forced.
     */
    private void recordVoided(PaymentAttempt attempt, ProviderOutcome outcome) {
        if (!PaymentAttemptStateMachine.permits(attempt.status(), PaymentAttemptStatus.CANCELLED)) {
            log.warn(
                    "Attempt {} was voided at the provider but cannot move from {} to CANCELLED; "
                            + "recording nothing rather than forcing a state.",
                    attempt.id(),
                    attempt.status());
            return;
        }
        attemptService.recordProviderEvent(
                attempt,
                PaymentTransactionType.CANCEL,
                PaymentAttemptStatus.CANCELLED,
                attempt.amount(),
                outcome.externalPaymentId(),
                outcome.evidence(),
                outcome.externalPaymentId(),
                null,
                clock.instant(),
                null,
                null);
    }

    /**
     * Says out loud that a live payment survived the order it belonged to.
     *
     * <p>Deliberately loud and deliberately not a local failure. The customer can
     * still pay; the platform simply cannot stop them. What makes that survivable
     * rather than a loss is that the capture is now recorded against the
     * settlement ({@link CapturedMoneyPort}) and is refundable, which is what this
     * line tells whoever reads it to go and do.
     */
    private void unvoidable(UUID orderId, PaymentAttempt attempt, String why) {
        log.warn(
                "Order {} ended with payment attempt {} on {} still live and no way to void it "
                        + "({}). The attempt is left open on purpose: a capture that lands will be "
                        + "recorded against the settlement and is refundable through a remedy.",
                orderId,
                attempt.id(),
                attempt.providerType(),
                why);
    }

    private void cancelIntent(PaymentIntent intent) {
        if (!intent.status().open()) {
            return;
        }
        intents.transition(
                intent.tenantId(),
                intent.id(),
                intent.status(),
                PaymentIntentStatus.CANCELLED,
                intent.version(),
                clock.instant());
        log.debug(
                "Intent {} for ended order {} had no open attempt and was cancelled by {}.",
                intent.id(),
                intent.orderId(),
                ACTOR);
    }
}
