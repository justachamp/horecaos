package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The small read {@code marketing} needs about abandoned carts (gap-map rows
 * 1.4 and 6.5, ADR 0044's {@code CART_ABANDONED} trigger: "an ADR 0019 cart
 * with no order after a configured delay").
 *
 * <p>Deliberately not a window onto the cart itself. A trigger needs a cart
 * id (to guard "once per cart", ADR 0044) and the account it belongs to (to
 * message); the lines, the quote, and the customer's notes stay inside {@code
 * ordering} the same way {@link OrderDirectory}'s own doc draws that line for
 * an order.
 */
public interface AbandonedCartDirectory {

    /**
     * Carts, across every brand, with no converted order and no activity since
     * {@code olderThan} — the candidate set a sweep walks, cross-tenant, the
     * same shape {@code JdbcCampaignStore#sendingCampaigns} already gives its
     * own sweeper.
     *
     * @return only carts owned by an account. A guest cart has no {@code
     *         customer_account_id} for marketing to message and is excluded
     *         at the source
     */
    List<AbandonedCart> abandonedSince(Instant olderThan, int limit);

    record AbandonedCart(UUID tenantId, UUID brandId, UUID cartId, UUID customerAccountId, Instant abandonedAt) {}
}
