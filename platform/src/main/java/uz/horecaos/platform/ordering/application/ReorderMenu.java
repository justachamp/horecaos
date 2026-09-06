package uz.horecaos.platform.ordering.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the published menu still offers, for the ids an old order names
 * (ADR 0074).
 *
 * <p>A port rather than a dependency on the catalog module, following
 * {@link CartMenuRules} and {@code OrderCatalogSnapshot}: ordering needs the
 * answer the menu gives, not a menu model it has no business reading.
 *
 * <p>The sibling of {@link CartMenuRules}, which asks whether a <em>selection</em>
 * obeys the published group rules. This asks the prior question — whether the
 * thing selected is still on the menu at all, and whether this location can
 * sell it right now — which the cart never has to ask, because a cart is
 * assembled from a menu the customer is looking at and an order is repeated
 * from one that may have changed underneath it.
 */
public interface ReorderMenu {

    /**
     * Resolve a set of historical variant ids against the menu as it stands now.
     *
     * @param channelCode the order's own channel. ADR 0036 makes the channel
     *     supply both the publication and the price plane, so resolving an order
     *     placed on one channel against another's menu answers a question nobody
     *     asked
     */
    Snapshot at(UUID tenantId, UUID brandId, UUID locationId, String channelCode, Set<UUID> variantIds);

    /**
     * What the menu says about each id that survived.
     *
     * <p><strong>Absent means withdrawn.</strong> A variant is missing from
     * {@code offers} when the live publication for this channel does not carry
     * it, when this location has no offering row for it, or when that row is
     * {@code HIDDEN}. Those three are one answer to a customer — the dish is not
     * on this menu — and separating them would leak which of a brand's dishes
     * exist but are hidden here.
     */
    record Snapshot(Map<UUID, VariantOffer> offers) {

        public Snapshot {
            offers = offers == null ? Map.of() : Map.copyOf(offers);
        }

        public Optional<VariantOffer> offerOf(UUID variantId) {
            return Optional.ofNullable(offers.get(variantId));
        }
    }

    /**
     * One surviving variant, as this location sells it today.
     *
     * <p>No name. The label a repeat screen shows is the order's own
     * {@code product_name_snapshot} — what the customer actually bought — and
     * carrying the menu's current name here would mean asking this port for a
     * locale it has no other use for.
     *
     * @param soldOut the location shows it and cannot sell it — {@code
     *     OfferingStatus.UNAVAILABLE}, the kitchen's own 86. Distinct from
     *     absent, because this one comes back
     * @param offeredOptionIds every modifier option the product's published
     *     groups still offer. A line's option ids must all be in here, or the
     *     repeat would quietly serve a plainer dish
     */
    record VariantOffer(UUID productId, boolean soldOut, Set<UUID> offeredOptionIds) {

        public VariantOffer {
            offeredOptionIds = offeredOptionIds == null ? Set.of() : Set.copyOf(offeredOptionIds);
        }
    }
}
