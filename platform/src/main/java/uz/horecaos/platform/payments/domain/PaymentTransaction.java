package uz.horecaos.platform.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the provider said happened, recorded once (ADR 0013).
 *
 * <p>Append-only, and withheld UPDATE at the grant rather than merely documented:
 * these rows are what a settlement dispute is decided from six weeks later.
 *
 * <p>Two clocks are kept deliberately. {@link #occurredAt()} is the provider's and
 * is what a settlement file matches against; {@link #recordedAt()} is HorecaOS's, and
 * the gap between them is how a replayed callback is distinguished from a second
 * event.
 *
 * @param providerReference Click's {@code click_trans_id} or Payme's
 *                          {@code params.id}, and {@code LOCAL:{uuid}} for the one
 *                          event no provider originates — an expiry HorecaOS decided
 *                          on its own, which Click is never told about because
 *                          Click has no expiry state
 * @param protectedRequestReference  ADR 0029. A payment payload is full of personal
 *                                   data, so the body lives behind a protected
 *                                   reference and never in a column, an event, a
 *                                   log line, a trace, or a metric
 */
public record PaymentTransaction(
        UUID id,
        UUID tenantId,
        UUID intentId,
        UUID attemptId,
        PaymentTransactionType type,
        SomAmount amount,
        String providerReference,
        ProviderEvidence evidence,
        Instant occurredAt,
        Instant recordedAt,
        String protectedRequestReference,
        String protectedResponseReference) {

    private static final String LOCAL_PREFIX = "LOCAL:";

    public PaymentTransaction {
        Objects.requireNonNull(id, "A transaction id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(intentId, "An intent id is required");
        Objects.requireNonNull(attemptId, "An attempt id is required");
        Objects.requireNonNull(type, "A transaction type is required");
        Objects.requireNonNull(amount, "An amount is required");
        Objects.requireNonNull(
                providerReference, "A provider reference is required; a null one would defeat the replay uniqueness");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
    }

    /** The reference for an event HorecaOS decided and no provider originated. */
    public static String localReference(UUID id) {
        return LOCAL_PREFIX + id;
    }

    public boolean originatedLocally() {
        return providerReference.startsWith(LOCAL_PREFIX);
    }

    public Optional<ProviderEvidence> providerEvidence() {
        return Optional.ofNullable(evidence);
    }
}
