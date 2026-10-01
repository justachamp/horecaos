package uz.horecaos.platform.pricing.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Questions {@code ordering} asks about a brand's promotions without learning how
 * they are stored (ADR 0140).
 */
public interface PromotionQueryPort {

    /**
     * Whether the brand has an active promotion with a {@code PAYMENT_METHOD}
     * condition, or the order already holds one.
     *
     * <p>Decides whether a payment-method change must reprice an amended order, and
     * whether switching method at checkout must force a re-quote ({@code
     * PRICE_CHANGED}), because only then does the method change what the total is.
     *
     * @param orderId the order being amended, or null for a cart
     */
    boolean paymentMethodChangesTheTotal(UUID tenantId, UUID brandId, @Nullable UUID orderId);
}
