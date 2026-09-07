package uz.horecaos.platform.referral.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.referral.application.ReferralQualificationService.OrderOutcomeNotice;

/**
 * Turning ADR 0019's {@code OrderCompleted} fact into a qualifying event (ADR
 * 0067).
 *
 * <p>This is the caller {@code ReferralQualificationService.onOrderOutcome}
 * was missing. ADR 0067 names the gap in the same words ADR 0046 uses for its
 * own accrual: "nothing calls {@code onOrderOutcome} from a real order
 * completion event". Both gaps close in this wave, on the same {@code
 * OrderCompleted} fact and the same listener shape {@link
 * uz.horecaos.platform.notifications.application.OrderNotificationTrigger}
 * already established — two small, independent {@code @TransactionalEventListener}
 * beans, one per module, rather than one class reaching into another
 * module's {@code application} package. {@code OrderNotificationTrigger} and
 * {@code uz.horecaos.platform.integration.outbox.OrderingOutboxEventListener}
 * are the existing proof that this event already has more than one in-process
 * subscriber, and referral and loyalty joining that same seam is the "one
 * seam" this closes, not a merge of the two into a single class loyalty and
 * referral would then both have to import each other's internals to build.
 *
 * <p><strong>Only {@code OrderCompleted}.</strong> A cancelled or rejected
 * order leaves a referee's redemption exactly where it was — {@code PENDING}
 * and open for a real completion later, per ADR 0067's own testing section —
 * and {@code onOrderOutcome} does nothing at all for a status it is never
 * called with, so there is no case for those events below.
 *
 * <p><strong>The referee, resolved the same way {@code OrderNotificationTrigger}
 * resolves the customer notifications need:</strong> {@link OrderCompleted}
 * carries no customer (ADR 0029, ADR 0032), so this
 * asks {@link OrderDirectory#summary}, the published read another module asks
 * for exactly this. A guest order resolves to no account and is treated the
 * same way {@code onOrderOutcome} already treats one -- nothing to credit.
 *
 * <p><strong>Idempotent by inheritance.</strong> {@code onOrderOutcome} locks
 * the redemption row for the length of its own transaction and only ever
 * grants for the transaction that wins that lock; this class adds nothing of
 * its own to deduplicate; it only translates one event into one call.
 */
@Component
public class ReferralOrderCompletionTrigger {

    private final ReferralQualificationService qualification;
    private final OrderDirectory orders;

    public ReferralOrderCompletionTrigger(ReferralQualificationService qualification, OrderDirectory orders) {
        this.qualification = qualification;
        this.orders = orders;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderingEvent(OrderingEvent event) {
        if (event instanceof OrderCompleted completed) {
            qualify(completed);
        }
    }

    private void qualify(OrderCompleted completed) {
        UUID tenantId = completed.tenantId().value();

        UUID refereeCustomerAccountId = orders.summary(tenantId, completed.orderId())
                .map(OrderDirectory.OrderSummary::customerAccountId)
                .orElse(null);

        // onOrderOutcome already refuses a null customer (a guest order earns
        // no referral reward for the same reason it earns no loyalty accrual:
        // there is no account to credit), so this is not a second check --
        // it only avoids building a notice this transaction cannot use.
        if (refereeCustomerAccountId == null) {
            return;
        }

        qualification.onOrderOutcome(new OrderOutcomeNotice(
                tenantId,
                completed.brandId(),
                refereeCustomerAccountId,
                completed.orderId(),
                "COMPLETED",
                completed.completedAt()));
    }
}
