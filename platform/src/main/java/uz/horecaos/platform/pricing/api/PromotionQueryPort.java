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
     * <p>Decides whether a payment-method change must reprice an amended order,
     * because only then does the method change what the total is.
     *
     * @param orderId the order being amended, or null for a cart
     */
    boolean paymentMethodChangesTheTotal(UUID tenantId, UUID brandId, @Nullable UUID orderId);

    /**
     * Whether paying by {@code chosenMethodCode} instead of the method a cart was
     * priced under can change what the cart costs: true when an active promotion's
     * {@code PAYMENT_METHOD} condition names either of the two.
     *
     * <p>This is the question checkout asks before it refuses a method the quote was
     * not priced with ({@code PRICE_CHANGED}). It is narrower than {@link
     * #paymentMethodChangesTheTotal} on purpose: a brand that runs "5% off with Click"
     * must not refuse the customer who pays cash, because no promotion reads cash and
     * the quoted total is exactly what cash pays. A storefront cart that never selected
     * a method is priced under none ({@code pricedMethodCode} null), so only a method a
     * promotion reads can move the total away from the quote.
     *
     * @param pricedMethodCode the method the cart was priced under, or null when it
     *     carried none
     */
    boolean paymentMethodMovesTheTotal(
            UUID tenantId, UUID brandId, @Nullable String pricedMethodCode, String chosenMethodCode);
}
