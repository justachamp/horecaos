package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A payment attempt reached {@code FAILED} (ADR 0013): declined by the
 * provider, or rejected outright, with money never moving.
 *
 * <p>Sibling to {@link uz.horecaos.platform.ordering.api.PaymentFailed},
 * published from the same call site in {@code PaymentAttemptService}. That
 * event exists only to keep ordering's own payment-status projection honest
 * and is deliberately narrow — no brand, no location, no reason — because a
 * rendering aid for an operations list needs none of them. This one exists
 * for the ADR 0058 operations Telegram trigger, which needs enough to route
 * the alert ({@code brandId}/{@code locationId}) and enough to say why (the
 * provider's own {@code failureCode}) without carrying anything about the
 * customer.
 *
 * <p>An in-process signal only, exactly like {@code PaymentFailed}: no ADR
 * 0032 catalogue entry, never appended to the outbox.
 */
public record PaymentAttemptFailed(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        UUID attemptId,
        @Nullable String reasonCode,
        Instant occurredAt) {

    public PaymentAttemptFailed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(attemptId, "Attempt ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
