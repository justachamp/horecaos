package uz.qoida.platform.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.migration.api.ExternalEffect;
import uz.qoida.platform.migration.api.ImportSuppression;
import uz.qoida.platform.ordering.api.OrderDirectory;
import uz.qoida.platform.ordering.api.PaymentIntentPort;
import uz.qoida.platform.payments.domain.PaymentIntent;
import uz.qoida.platform.payments.domain.PaymentIntentStatus;
import uz.qoida.platform.payments.domain.PaymentMethod;
import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.PaymentTender;
import uz.qoida.platform.payments.domain.SomAmount;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * Creates the provider-neutral intent an order refers to (ADR 0013, ADR 0019).
 *
 * <p>This is the implementation of the port ordering has been calling a stand-in
 * for. That stand-in answered "no payment is ever required", which meant every
 * order took the offline path; the moment this bean exists the
 * {@code @ConditionalOnMissingBean} in {@code OrderPaymentConfiguration} steps
 * aside and no ordering code changes.
 *
 * <p><strong>No provider is called from here.</strong> ADR 0019 is explicit that
 * nothing external happens inside the checkout transaction, and ADR 0013 has its
 * own reason to agree: an outbound call inside a transaction that may be rolled
 * back is how a charge exists that no row remembers. Only local rows are written;
 * initiation starts after commit, with its own idempotency and its own
 * reconciliation.
 *
 * <p>Every method here must be safe to answer for an order that may not exist, a
 * method code this build does not implement, and a tenant that has no merchant
 * account at all. In each case the answer is the conservative one — no payment is
 * taken and the checkout says so — rather than an exception on the checkout path.
 */
@Service
public class PaymentIntentService implements PaymentIntentPort {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);

    private final JdbcPaymentIntentStore intents;
    private final OrderDirectory orders;
    private final PaymentLegalEntityResolver legalEntities;
    private final PaymentBindingResolver bindings;
    private final PaymentBusinessCalendar calendar;
    private final PaymentFiscalService fiscal;
    private final Clock clock;

    public PaymentIntentService(JdbcPaymentIntentStore intents, OrderDirectory orders,
            PaymentLegalEntityResolver legalEntities, PaymentBindingResolver bindings,
            PaymentBusinessCalendar calendar, PaymentFiscalService fiscal, Clock clock) {
        this.intents = intents;
        this.orders = orders;
        this.legalEntities = legalEntities;
        this.bindings = bindings;
        this.calendar = calendar;
        this.fiscal = fiscal;
        this.clock = clock;
    }

    /**
     * Whether this order must be paid before the restaurant is asked to confirm.
     *
     * <p>Answered from the tender and nothing else. Cash is confirmed first and
     * collected at handover; a provider tender must clear first, because a kitchen
     * that starts cooking against a payment which never arrives has already lost
     * the food.
     *
     * <p>An unrecognised method code answers false rather than throwing. The
     * alternative would turn a channel misconfiguration into a checkout outage,
     * and the mismatch is visible instead in the log line here and in the absence
     * of an intent on the order.
     */
    @Override
    public boolean paymentRequiredBeforeConfirmation(UUID tenantId, UUID orderId,
            String paymentMethodCode) {
        return PaymentMethod.fromCode(paymentMethodCode)
                .map(method -> method.captureTiming().requiredBeforeConfirmation())
                .orElseGet(() -> {
                    log.warn("Order {} names payment method {}, which this build does not "
                            + "implement. Treating it as requiring no payment before confirmation.",
                            orderId, paymentMethodCode);
                    return false;
                });
    }

    /**
     * Creates the intent, and — for cash — the fiscal document that records why
     * there will be no receipt.
     *
     * <p>Both in one transaction. A cash order whose intent committed and whose
     * {@code NOT_APPLICABLE} document did not would be indistinguishable from an
     * order whose fiscalization is merely late, and the entire purpose of the
     * decision is that the two are different.
     *
     * <p><strong>{@code amountMinor} is taken, never derived.</strong> It is the
     * order's money leg — what a provider or a courier is actually to collect —
     * and ADR 0046's settlement is the one place it is worked out. This class
     * used to be handed the order total, which is the same number only on an order
     * that redeems nothing, and every attempt opened against the intent inherits
     * this figure ({@code PaymentAttemptService.open} copies {@code intent.amount()}
     * onto the attempt, and the adapters present the attempt), so a wrong amount
     * here is the sum a customer is genuinely charged.
     *
     * @param amountMinor whole som to collect, from the settlement's money tenders
     * @return the intent id, or null when no payment is required — the contract
     *         the port's Javadoc states, and the answer for an unrecognised method
     */
    @Override
    @Transactional
    public UUID createIntent(UUID tenantId, UUID orderId, long amountMinor, String currency,
            String paymentMethodCode, String idempotencyKey) {
        // ADR 0024. An intent is an open request for money: it moves to
        // AUTHORIZING, an attempt is opened against it, and the provider is
        // presented to a customer. A historical order was paid years ago and its
        // payment is imported as the settled fact it already is, so an import that
        // arrives here has run the live checkout path.
        //
        // Refused rather than skipped, and the null return is why. The contract
        // says null means "no payment is required", and answering that during an
        // import would tell the caller a paid order was free — a lie the money
        // reconciliation would then compare against and find wrong on both sides.
        ImportSuppression.refuse(ExternalEffect.PAYMENT_COLLECTION, "createIntent");

        // ADR 0031's idempotency, checked before anything is built. A repeated
        // checkout must return the intent it created the first time; creating a
        // second one against the same order is a second charge waiting for a
        // customer to press a button twice.
        Optional<PaymentIntent> replay = intents.findByIdempotencyKey(tenantId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get().id();
        }

        Optional<PaymentMethod> method = PaymentMethod.fromCode(paymentMethodCode);
        if (method.isEmpty()) {
            log.warn("No payment intent created for order {}: payment method {} is not implemented.",
                    orderId, paymentMethodCode);
            return null;
        }

        Optional<OrderDirectory.OrderSummary> order = orders.summary(tenantId, orderId);
        if (order.isEmpty()) {
            // Empty means "no such order for this tenant", which is the same answer
            // as "it does not exist" and deliberately so. Fabricating an intent for
            // it would put a payable row under an order nobody can find.
            log.warn("No payment intent created: order {} is not readable for tenant {}.",
                    orderId, tenantId);
            return null;
        }

        Instant now = clock.instant();
        UUID locationId = order.get().locationId();
        LocalDate businessDate = calendar.businessDateFor(tenantId, locationId, now);
        UUID legalEntityId = legalEntities.sellerFor(tenantId, locationId, businessDate)
                .orElse(null);

        PaymentIntent intent = new PaymentIntent(
                UUID.randomUUID(), tenantId, orderId,
                order.get().brandId(), locationId,
                null,
                legalEntityId,
                method.get().tender(),
                method.get(),
                method.get().provider().orElse(null),
                new SomAmount(amountMinor, currency),
                PaymentIntentStatus.PENDING,
                method.get().captureTiming(),
                idempotencyKey,
                1, now, null);

        intents.insert(intent);

        if (intent.tender() == PaymentTender.CASH) {
            fiscal.recordCashNotApplicable(intent, now);
        }

        return intent.id();
    }

    /**
     * The port's form of the question, on today's business date.
     *
     * <p>Ordering asks without a date because it has none to give: the business
     * date is a payments and fiscal concept resolved from the location's calendar,
     * and a caller passing its own would be the second authority over it.
     */
    @Override
    public boolean canAcceptPayment(UUID tenantId, UUID locationId, String paymentMethodCode) {
        Instant now = clock.instant();
        return canAcceptPayment(tenantId, locationId, paymentMethodCode,
                calendar.businessDateFor(tenantId, locationId, now));
    }

    /**
     * Whether a merchant account exists that could actually take this payment.
     *
     * <p>Asked before a channel offers the method, not after the customer has
     * chosen it. If no binding exists for the resolved legal entity, the method is
     * not offered on any channel serving that location — a serviceability
     * precondition, the same shape ADR 0038 uses for cash at a location with no
     * fiscal terminal, rather than a failure the customer meets at the payment
     * step.
     */
    public boolean canAcceptPayment(UUID tenantId, UUID locationId, String paymentMethodCode,
            LocalDate businessDate) {
        Optional<PaymentMethod> method = PaymentMethod.fromCode(paymentMethodCode);
        if (method.isEmpty()) {
            return false;
        }
        // Cash needs no merchant account, which is exactly why it is the majority
        // tender and why it is also the one no provider can fiscalize.
        if (method.get().tender() == PaymentTender.CASH) {
            return true;
        }

        Optional<UUID> seller = legalEntities.sellerFor(tenantId, locationId, businessDate);
        if (seller.isEmpty()) {
            return false;
        }

        Optional<PaymentProviderType> provider = method.get().provider();
        if (provider.isEmpty()) {
            // A non-cash method with no provider behind it: a marketplace tender,
            // which arrives already collected by the aggregator and is never chosen
            // at a Qoida checkout. There is no merchant account to check and
            // nothing here could take the payment, so the honest answer is no —
            // and it is an answer rather than the NoSuchElementException an
            // orElseThrow would raise inside a checkout.
            return false;
        }
        return bindings.resolve(tenantId, seller.get(), provider.get(), businessDate).isPresent();
    }
}
