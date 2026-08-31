package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An uncertain payment attempt's resolver reached {@code
 * UncertaintyResolver.OPERATIONS_EXCEPTION} (ADR 0013): the automated
 * resolution path has run out and a human owns the attempt now.
 *
 * <p>Deliberately narrower than every {@code UNCERTAIN} attempt.
 * {@code CLICK_STATUS_BY_MTI} and {@code PAYME_CHECK_TRANSACTION} are
 * retried automatically and publish nothing — an alert on every uncertain
 * attempt would be an alert on every provider hiccup, which is exactly the
 * "not on every retry" ADR 0058 asks this trigger to avoid. This event
 * exists only for the moment a human is genuinely needed: the resolver
 * itself has already decided that, and {@code reasonCode} carries why
 * (never a provider payload, per ADR 0029).
 *
 * <p>An in-process signal only, in the {@code payments.api.PaymentAttemptFailed}
 * genre: no ADR 0032 catalogue entry, never appended to the outbox.
 */
public record PaymentAttemptNeedsOperator(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        UUID attemptId,
        String reasonCode,
        Instant occurredAt) {

    /** The resolver gave up immediately: Telegram's reconciliation path is unspecified. */
    public static final String REASON_UNSUPPORTED_PROVIDER = "UNSUPPORTED_PROVIDER";

    /** The binding or provider port this attempt needs no longer resolves. */
    public static final String REASON_BINDING_UNAVAILABLE = "BINDING_UNAVAILABLE";

    /** Click/Payme's automated resolver ran for the full deadline with no definite answer. */
    public static final String REASON_DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";

    public PaymentAttemptNeedsOperator {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(attemptId, "Attempt ID is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
