package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a customer may do to their own orders from a chat (ADR 0075).
 *
 * <p>A port because the Telegram handler lives in {@code integration} and
 * cannot reach {@code ordering.application} at all under Spring Modulith — the
 * same reason {@link OrderDecisionPort} exists for the staff half. It is
 * deliberately not that interface widened: a staff tap decides somebody else's
 * order and re-earns an ADR 0025 capability at every tap, while a customer tap
 * spends the tapper's own money and carries no delegated authority whatsoever.
 * One interface serving both would make the difference invisible at exactly the
 * boundary where it matters most.
 *
 * <p><strong>Every method takes the customer account explicitly and resolves
 * nothing from ambient state.</strong> The caller has already proved, through
 * the ADR 0026 binding on a private chat, which account is on the other end;
 * this interface never accepts a Telegram id, a chat id or a principal, so
 * there is no path by which a bot bug becomes a read of somebody else's orders.
 *
 * <p>Nothing here is a second implementation of anything. Each method is a
 * short arrangement of the same application services the storefront controller
 * calls, and ADR 0074's rule that there is exactly one way to build a cart is
 * why {@link #repeat} is here rather than in the bot: the loop over
 * {@code CartService.putLine} moved inside the process, not outside the service.
 */
public interface CustomerBotOrderingPort {

    /**
     * The customer's most recent order at this brand, or empty when they have
     * never ordered.
     *
     * <p>One order, not a page. A chat has no room for a history and no way to
     * page one, and every action this port offers is about the latest order.
     */
    Optional<OrderCard> latestOrder(UUID tenantId, UUID brandId, UUID customerAccountId);

    /**
     * Builds a cart holding exactly what one past order held.
     *
     * <p>Refuses unless ADR 0074's plan says {@code READY} <em>at the moment
     * this runs</em> — the plan read when the button was rendered is not the
     * plan when it is tapped, and a dish 86'd in between must stop the repeat
     * rather than half-build it.
     *
     * <p>One transaction. A repeat that added six lines of eight and then failed
     * would leave a cart the customer never asked for and would have to be told
     * about; there is nothing sensible to say about half a basket.
     */
    Repeat repeat(UUID tenantId, UUID brandId, UUID customerAccountId, UUID orderId);

    /** The customer's open cart at this brand, priced where it can be. */
    Optional<CartCard> currentCart(UUID tenantId, UUID brandId, UUID customerAccountId);

    /**
     * Prices and checks out an open cart, for cash only.
     *
     * <p>Cash only because ADR 0075 forbids money in the chat: a provider method
     * ends in a link to the provider's own page, and a bot that pretended to
     * take a card would be the single worst thing on this surface.
     *
     * @param idempotencyKey the tapped button's own token, so the same physical
     *     button tapped twice is one checkout and not two orders
     */
    Checkout checkoutForCash(UUID tenantId, UUID brandId, UUID customerAccountId, UUID cartId, String idempotencyKey);

    /**
     * One order, as a chat message can show it.
     *
     * @param live whether the order is still moving — the difference between
     *     "where is it" and "would you like it again"
     * @param repeatable ADR 0074's verdict was {@code READY} when this was read.
     *     Advisory only: {@link #repeat} re-checks, because a button lives longer
     *     than a plan
     * @param completed reached COMPLETED, so ADR 0071 will accept a rating.
     *     Whether one already exists is reviews' knowledge and deliberately not
     *     answered here — ordering does not read that module, and a caller
     *     offering a rating button asks {@code reviews.api} itself
     */
    record OrderCard(
            UUID orderId,
            String publicOrderNumber,
            String status,
            String currency,
            long totalMinor,
            @Nullable Instant promisedAt,
            boolean live,
            boolean repeatable,
            boolean completed) {}

    /**
     * @param blockedDishes the product names, as the order snapshotted them,
     *     that stopped the repeat. Populated only on {@link Repeat.Result#NOT_READY},
     *     because a bot with no screen to grey out has to say which dish
     */
    record Repeat(Result result, @Nullable UUID cartId, int lineCount, List<String> blockedDishes) {

        public Repeat {
            blockedDishes = blockedDishes == null ? List.of() : List.copyOf(blockedDishes);
        }

        public enum Result {
            /** A cart exists holding the same variants, modifiers and quantities. */
            BUILT,
            /** ADR 0074's plan is not READY right now; {@code blockedDishes} says which. */
            NOT_READY,
            /** No such order for this customer — the same answer as one that never existed. */
            NO_SUCH_ORDER,
            /**
             * The cart could not be built for a reason ordering states, such as a
             * blacklisted customer or a channel that stopped selling.
             */
            REFUSED
        }
    }

    /**
     * An open cart, as a chat message can show it.
     *
     * @param totalMinor null when the cart cannot be priced right now, which a
     *     client must render as "we cannot total this" and never as free
     * @param blockedReason a stable code naming what stops a checkout, or null
     *     when nothing does — {@code NEEDS_DESTINATION} for a delivery cart with
     *     nowhere to go, {@code NO_CASH_METHOD} where this channel takes none
     */
    record CartCard(
            UUID cartId,
            List<Item> items,
            String currency,
            @Nullable Long totalMinor,
            boolean checkoutable,
            @Nullable String blockedReason) {

        public CartCard {
            items = items == null ? List.of() : List.copyOf(items);
        }

        /** A line as the customer bought it, never a catalog lookup. */
        public record Item(String name, int quantity) {}
    }

    /** @param refusalCode ordering's own stable code when a checkout was refused */
    record Checkout(
            Result result,
            @Nullable String publicOrderNumber,
            @Nullable String refusalCode) {

        public enum Result {
            PLACED,
            /** A delivery cart with no destination. The app is where one is chosen. */
            NEEDS_DESTINATION,
            /** Nothing in the cart, or no open cart at all. */
            EMPTY,
            /** This channel offers no cash method; the customer pays in the app. */
            NO_CASH_METHOD,
            /** Refused for a business reason; {@code refusalCode} names it. */
            REFUSED
        }
    }
}
