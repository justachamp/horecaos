package uz.horecaos.platform.catalog.api;

import java.util.Set;
import java.util.UUID;

/**
 * Which variants an active {@code FREE_ITEM} promotion gives away (ADR 0158).
 *
 * <p>An auto-add rule that injects a promotion's gift would charge the gift in full whenever the
 * promotion does not fire (a rule cannot read the money a promotion's condition reads), so the catalog
 * validator blocks it at publication. Catalog does not know what a promotion is; pricing owns the fact and
 * implements this port, which lives in catalog's public interface for the reason {@link
 * VariantPricingLookup} does: the consumer declaring the contract keeps the dependency pointing one way.
 */
public interface PromotionGiftLookup {

    /** The gift variants of this brand's active promotions. */
    Set<UUID> giftVariantIds(UUID tenantId, UUID brandId);

    /** For a caller with no pricing module to ask: no promotion gives anything away. */
    PromotionGiftLookup NONE = (tenantId, brandId) -> Set.of();
}
