package uz.qoida.platform.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The provider's own vocabulary, kept verbatim beside Qoida's state (ADR 0013).
 *
 * <p>Never the source of a transition. Payme's signed numeric state carries
 * "cancelled" in its sign and how far the transaction got in its magnitude, and
 * Click has no equivalent, no expiry state, and no provider-side reservation
 * state at all — so adopting either vocabulary as the platform's would leave half
 * of the other provider unrepresentable.
 *
 * <p>Held as text rather than as an enum for the same reason. These are things a
 * provider said; a new value appearing is a fact to record and investigate, not a
 * deserialization failure on the path that credits an order.
 *
 * @param state      Payme's {@code State} as text, or Click's {@code payment_status}.
 *                   Test {@code state < 0} for cancelled on Payme and never
 *                   {@code state == -1}: the magnitude records how far the
 *                   transaction got, not which kind of cancellation it was
 * @param reason     Payme's {@code Reason}; null for a transaction that was never
 *                   cancelled, and left null rather than zeroed
 * @param recordedAt when Qoida observed this, which is not when it happened
 */
public record ProviderEvidence(String state, String reason, Instant recordedAt) {

    public ProviderEvidence {
        Objects.requireNonNull(state, "A provider state is required");
        Objects.requireNonNull(recordedAt, "An observation time is required");
    }

    public static ProviderEvidence of(String state, Instant recordedAt) {
        return new ProviderEvidence(state, null, recordedAt);
    }

    public Optional<String> cancellationReason() {
        return Optional.ofNullable(reason);
    }
}
