package uz.qoida.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One partner's answer to "what would this journey cost" (ADR 0014).
 *
 * <p>Kept as evidence rather than as a working value. The row it becomes is
 * INSERT-only — the migration grants no UPDATE on {@code delivery_quotes} — so
 * this is what a disputed selection is answered from six weeks later, and every
 * field a score reads has to be on it.
 *
 * @param requestId          Qoida's own id for the ask, not the partner's.
 *                           Neither verified partner issues one, which is why
 *                           {@code uq_quote_request} is keyed on this
 * @param priceMinor         integer minor units, whole som for UZS, or null on a
 *                           refusal
 * @param expiresAt          when this answer stops being usable, or null
 * @param partnerSuppliedExpiry whether {@code expiresAt} is the partner's
 *                           guarantee or a TTL Qoida imposed. Today every
 *                           verified partner leaves it false, and the day one
 *                           does not, the difference is recorded rather than
 *                           remembered
 */
public record DeliveryQuote(
        UUID id,
        UUID bindingId,
        String providerType,
        UUID requestId,
        Long priceMinor,
        String currency,
        Integer pickupEtaSeconds,
        Integer deliveryEtaSeconds,
        Integer distanceMeters,
        Integer deadHeadMeters,
        Instant expiresAt,
        boolean partnerSuppliedExpiry,
        String failureCode,
        Instant receivedAt) {

    public DeliveryQuote {
        Objects.requireNonNull(id, "A quote id is required");
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(requestId, "A request id is required");
        Objects.requireNonNull(receivedAt, "A received instant is required");
    }

    public boolean priced() {
        return priceMinor != null;
    }

    /** RECEIVED or REFUSED, the two states an INSERT-only table can write. */
    public String status() {
        return priced() ? "RECEIVED" : "REFUSED";
    }

    public String validitySource() {
        return partnerSuppliedExpiry ? "PARTNER" : "QOIDA_POLICY";
    }

    public boolean usableAt(Instant now) {
        return priced() && (expiresAt == null || now.isBefore(expiresAt));
    }
}
