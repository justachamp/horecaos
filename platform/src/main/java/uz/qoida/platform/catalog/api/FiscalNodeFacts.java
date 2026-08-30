package uz.qoida.platform.catalog.api;

import java.util.Set;
import java.util.UUID;

/**
 * What the catalog states about a priceable node that other modules must obey
 * (ADR 0038).
 *
 * <p>This exists for one specific rule and is shaped by it. Click's
 * {@code Items[]} carries a {@code Labels} array of marking codes; Payme's
 * {@code detail.items[]} has no marking field of any kind. A marked good
 * therefore cannot be lawfully fiscalized through Payme, so a cart containing
 * one must not offer Payme as a payment method — the same mechanism by which a
 * location with no fiscal terminal does not offer cash.
 *
 * <p>The rule could have been written inside the Payme adapter, and that is the
 * version that fails. An adapter learns about the cart at the moment it is asked
 * to take money, which is after the customer chose how to pay; refusing then is
 * a failed checkout rather than a button that was never shown. Marking is a fact
 * the catalog knows about an item, and it has to be readable while the cart is
 * still being priced.
 *
 * <p>Unlike {@code VariantPricingLookup}, which catalog declares and pricing
 * implements, this is implemented by catalog and read by payments: the
 * dependency runs the other way because the fact belongs here.
 */
public interface FiscalNodeFacts {

    /**
     * Which of these nodes may only be fiscalized by a provider that carries
     * marking codes.
     *
     * <p>The offending nodes rather than a boolean, because a customer whose
     * payment options shrank is owed the reason, and "one of the items in your
     * basket" is not one.
     *
     * @param priceableIds variant, modifier option, and fee identifiers from the
     *                     cart, mixed freely — they are distinct identifiers and
     *                     the classification table is keyed on all three
     */
    Set<UUID> markedNodes(UUID tenantId, UUID brandId, Set<UUID> priceableIds);

    /**
     * Whether this cart forces a marking-capable payment method.
     *
     * <p>Convenience over {@link #markedNodes}, for the caller that only needs
     * to filter the offered set.
     */
    default boolean requiresMarkingCapablePayment(UUID tenantId, UUID brandId,
            Set<UUID> priceableIds) {
        return !markedNodes(tenantId, brandId, priceableIds).isEmpty();
    }
}
