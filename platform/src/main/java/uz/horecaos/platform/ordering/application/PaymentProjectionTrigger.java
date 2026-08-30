package uz.horecaos.platform.ordering.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.PaymentCaptured;
import uz.horecaos.platform.ordering.api.PaymentFailed;
import uz.horecaos.platform.ordering.api.PaymentRefunded;
import uz.horecaos.platform.ordering.api.PaymentVoided;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;

/**
 * Keeps {@code ordering.orders.payment_status_projection} mirroring the payment
 * aggregate (V0022, ADR 0013).
 *
 * <p>Deliberately its own class, separate from {@link PaymentCaptureConfirmationTrigger}.
 * That class draws a real consequence from {@link PaymentCaptured} — it decides
 * whether the order confirms or waits for restaurant approval, which is an order
 * state transition. This class draws none: every method here does exactly one
 * thing, an unconditional {@code SET} of a rendering column, whatever state the
 * order is in and whatever else is also reacting to the same fact. Folding the
 * two together would make it easy for a future edit to the state-machine
 * consequence to also, accidentally, change what the projection does — or the
 * other way round — and the V0022 migration comment is explicit that this column
 * is never allowed to decide anything.
 *
 * <p>Four facts, four listeners, one write each:
 *
 * <ul>
 *   <li>{@link PaymentCaptured} — {@code payments.application.PaymentAttemptService
 *       .applyToIntent}'s {@code CAPTURED} case;</li>
 *   <li>{@link PaymentFailed} — the same method's {@code FAILED} case;</li>
 *   <li>{@link PaymentVoided} — the same method's {@code CANCELLED} case, which is
 *       what {@code TerminalOrderPaymentVoid} produces when it closes the
 *       provider's side of an order that ended unpaid;</li>
 *   <li>{@link PaymentRefunded} — the same method's {@code REVERSED} case
 *       (a provider-reported reversal), and {@code OrderRemedyService
 *       .recordMoneyRemedy} (an ADR 0048 console refund or delivery-fee
 *       reimbursement).</li>
 * </ul>
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, the same phase every other
 * payments-to-ordering listener in this package uses: the write is local rows
 * only, so there is no reason to let it commit separately from the payment fact
 * that caused it.
 *
 * <p>Every write goes through {@link JdbcOrderStore#updatePaymentProjection},
 * whose own Javadoc carries the two invariants that make this class safe to be
 * this thin: the update is a plain {@code SET} (idempotent — a redelivered fact
 * converges on the same value rather than erroring), and it refuses to move a
 * {@code NOT_REQUIRED} projection at all, which is what keeps a cash order's
 * column untouched by every listener below, including a genuine refund recorded
 * against it.
 */
@Component
public class PaymentProjectionTrigger {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjectionTrigger.class);

    private final JdbcOrderStore orders;

    public PaymentProjectionTrigger(JdbcOrderStore orders) {
        this.orders = orders;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentCaptured(PaymentCaptured event) {
        apply(event.tenantId().value(), event.orderId(), "CAPTURED");
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentFailed(PaymentFailed event) {
        apply(event.tenantId().value(), event.orderId(), "FAILED");
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentVoided(PaymentVoided event) {
        apply(event.tenantId().value(), event.orderId(), "VOIDED");
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentRefunded(PaymentRefunded event) {
        apply(event.tenantId().value(), event.orderId(), "REFUNDED");
    }

    private void apply(UUID tenantId, UUID orderId, String projection) {
        boolean moved = orders.updatePaymentProjection(tenantId, orderId, projection);
        if (!moved) {
            // Not an error: a NOT_REQUIRED order refuses by design, and an order
            // this tenant no longer has (a hard delete outside this platform's own
            // lifecycle, which does not otherwise exist) is not a reason to fail a
            // payment fact that already happened.
            log.debug(
                    "Payment projection {} for order {} applied nothing (unknown order, or the "
                            + "order's projection is NOT_REQUIRED and stays that way)",
                    projection,
                    orderId);
        }
    }
}
