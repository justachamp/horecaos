package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.payments.domain.PaymentProviderType;

/**
 * A provider-tendered payment intent was created for an order (ADR 0013).
 *
 * <p>Published only when a provider will actually be asked for money —
 * {@code PaymentIntentService#createIntent} never raises this for a cash
 * intent, because {@code providerType} would be null and there would be
 * nothing for a listener gated on it to do. This is the earliest point an
 * order is known to need an online payment: {@code CheckoutService} has not
 * yet decided whether the order is auto-confirmed or held for approval, and
 * no attempt has been opened, no surface presented, and no provider called —
 * {@code PaymentIntentService}'s own class doc is explicit that nothing
 * external happens inside the transaction this publishes from.
 *
 * <p>Exists for gap map row {@code 10.9d}: {@code
 * payments.notifications.PaymentLinkAutoSendTrigger} is the one listener,
 * and it is the one place the registered {@code
 * notifications.payment_link_auto_send} configuration key is finally read.
 * Before this event nothing did, and the switch changed nothing.
 *
 * <p>An in-process signal only, in the {@code PaymentAttemptFailed} genre:
 * no ADR 0032 catalogue entry, never appended to the outbox.
 */
public record PaymentIntentCreated(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        UUID intentId,
        PaymentProviderType providerType,
        @Nullable UUID customerAccountId,
        Instant occurredAt) {

    public PaymentIntentCreated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(intentId, "Intent ID is required");
        Objects.requireNonNull(providerType, "A provider type is required: this event never fires for cash");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
