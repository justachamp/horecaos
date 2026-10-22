package uz.horecaos.platform.ordering.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether an order, or an amendment of it, forces a payment method that can carry a marking code (ADR 0038
 * stage 6, ADR 0166 Decision 7, precondition P1 of ADR 0161).
 *
 * <p>{@link MarkedCartLines} answers it for a basket, while the customer is choosing; this answers it for an
 * order that exists, when its payment method is changed (ADR 0039 {@code CHANGE_PAYMENT_METHOD}). Without it
 * the exclusion has a second door: a marked basket is paid in cash, the method is changed to one whose
 * receipt cannot carry the codes, and the receipt builder is the first place that finds out.
 */
public interface MarkedOrderPayment {

    /**
     * @param addedVariantIds the variants the same amendment adds, so a batch that adds a marked good and
     *     changes the method is judged as the order it would leave behind
     * @return why the method may not be used, or empty when it may
     */
    Optional<String> refusalOf(
            UUID tenantId, UUID brandId, UUID orderId, String paymentMethodCode, Set<UUID> addedVariantIds);

    /** A deployment, or a suite, with nothing marked: every order may use every method. */
    MarkedOrderPayment NONE = (tenantId, brandId, orderId, paymentMethodCode, addedVariantIds) -> Optional.empty();
}
