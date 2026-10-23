package uz.horecaos.platform.notifications.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;

/**
 * Ends an erased customer's Web Push subscriptions (ADR 0029, ADR 0182).
 *
 * <p>A subscription is a capability that names a device, so an erased customer's must not outlive the
 * erasure: the ciphertext is cleared in the same statement that ends the row, and the endpoint stops
 * being found. Idempotent under retry, as {@link CustomerErasureParticipant} requires: a second call finds
 * nothing live.
 */
@Component
@ErasesCustomerData(
        name = "notifications-push",
        tables = {"notifications.push_subscriptions"})
public class PushSubscriptionErasureParticipant implements CustomerErasureParticipant {

    private final PushSubscriptionService subscriptions;

    public PushSubscriptionErasureParticipant(PushSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        return ErasureCounts.of("notifications.push_subscriptions", subscriptions.erase(tenantId, customerAccountId));
    }
}
