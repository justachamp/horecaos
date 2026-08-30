package uz.horecaos.platform.pricing.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pricing a cart, for the module that owns the cart (ADR 0018, ADR 0019).
 *
 * <p>Ordering owns the cart and therefore has to be the thing that asks for it to
 * be priced; it must not own the arithmetic. This port is that seam. Everything
 * the total depends on goes in, and a quote with its context hash comes back —
 * ordering never sees a price book, a tax profile, or the engine.
 */
public interface CartPricingPort {

    /**
     * Prices a cart and stores the quote with its evidence.
     *
     * <p>An idempotency key returns the existing quote rather than a second one,
     * so a customer cannot end up holding two quotes and two reservations for one
     * basket.
     *
     * @throws PricingRefusedException when the cart cannot be priced at all — an
     *         item with no active price, no live menu, no price book. A refusal
     *         rather than a zero: a cart silently priced at nothing is a free meal
     */
    QuoteSnapshot priceCart(PricingCommand command);

    /**
     * @param channelCode the ADR 0036 channel, which decides both the menu that is
     *                    priced and the price plane that prices it
     */
    record PricingCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID customerAccountId,
            String channelCode,
            List<Item> items,
            String idempotencyKey) {

        public PricingCommand {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(brandId, "A brand id is required");
            Objects.requireNonNull(locationId, "A location id is required");
            items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("A cart with no items has nothing to price");
            }
        }

        /**
         * @param lineKey stable within the cart, so a re-quote can be compared line
         *                by line rather than by position
         */
        public record Item(String lineKey, UUID variantId, int quantity,
                List<UUID> modifierOptionIds) {

            public Item {
                modifierOptionIds = modifierOptionIds == null
                        ? List.of() : List.copyOf(modifierOptionIds);
            }
        }
    }

    /**
     * A cart that cannot be priced, with a stable code and the thing that caused
     * it.
     *
     * <p>Carries the offending id so a storefront can say which item, rather than
     * failing opaquely and leaving the customer to remove things one at a time.
     */
    class PricingRefusedException extends RuntimeException {

        private final String code;
        private final UUID subjectId;

        public PricingRefusedException(String code, UUID subjectId, String message) {
            super(message);
            this.code = code;
            this.subjectId = subjectId;
        }

        public String code() {
            return code;
        }

        public UUID subjectId() {
            return subjectId;
        }
    }
}
