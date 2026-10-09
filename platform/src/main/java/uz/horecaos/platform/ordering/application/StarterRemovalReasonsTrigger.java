package uz.horecaos.platform.ordering.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.tenancy.api.TenantActivated;

/**
 * Gives a tenant its starter line-removal reasons when it goes live (ADR 0169).
 *
 * <p>A tenant with no active removal reason cannot take a line off a confirmed order, and an empty list on the
 * day a dispatcher first needs it is the wrong time to find out. V0541 seeded every tenant that existed; this
 * seeds the ones activated after it, in the activating transaction, and only if the tenant has none of its own.
 */
@Component
public class StarterRemovalReasonsTrigger {

    private final OrderOutcomeReasonService reasons;

    public StarterRemovalReasonsTrigger(OrderOutcomeReasonService reasons) {
        this.reasons = reasons;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTenantActivated(TenantActivated event) {
        reasons.seedStarterRemovalReasons(event.tenantId().value());
    }
}
