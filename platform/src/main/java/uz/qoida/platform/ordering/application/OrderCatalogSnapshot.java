package uz.qoida.platform.ordering.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What ordering needs from the catalog in order to take a snapshot (ADR 0016,
 * ADR 0019).
 *
 * <p>A port rather than a dependency on the catalog module, following the same
 * pattern {@code CatalogPricingContext} sets in pricing: ordering depends on four
 * facts, not on a menu model it has no business reading.
 *
 * <p>Everything returned here is copied onto the order and never read again. The
 * order does not join back to these rows, so a product renamed or a modifier
 * withdrawn next month cannot change a receipt.
 */
public interface OrderCatalogSnapshot {

    /**
     * The brand's live publication on one channel.
     *
     * <p>Checkout re-reads this inside its transaction and refuses if it is not
     * the publication the quote was priced against. A menu republished between
     * pricing and checkout changes what a dish is, and honouring the old price
     * for the new dish is the wrong answer in both directions.
     */
    Optional<UUID> activePublicationId(UUID tenantId, UUID brandId, String channelCode);

    /** Names and SKUs for the ordered variants, in the brand's default locale. */
    Map<UUID, VariantDescriptor> variants(UUID tenantId, UUID brandId, Set<UUID> variantIds);

    /** Names for the chosen modifier options, with the group each belongs to. */
    Map<UUID, ModifierDescriptor> modifierOptions(UUID tenantId, UUID brandId, Set<UUID> optionIds);

    /**
     * The slowest per-item preparation override among these variants at this
     * branch, if any of them carries one (ADR 0036).
     *
     * <p>A maximum rather than a sum, because a kitchen cooks in parallel: an
     * order is ready when its slowest dish is. Summing would quote two hours for
     * a table of six and make the platform unusable for exactly the orders worth
     * the most.
     *
     * <p>Empty means no ordered item overrides the branch's band, which is the
     * common case — the override exists for the lamb that takes forty minutes,
     * not for the menu.
     */
    Optional<Duration> longestPreparationOverride(UUID tenantId, UUID brandId, UUID locationId,
            Set<UUID> variantIds);

    /**
     * @param productName the dish as the customer saw it
     * @param variantName null when the variant has no name of its own, which is
     *                    the common case: most variants are "regular" or "large"
     *                    and the customer-facing name is the dish
     */
    record VariantDescriptor(UUID productId, String productName, String variantName, String sku) { }

    record ModifierDescriptor(UUID groupId, String groupName, String optionName) { }
}
