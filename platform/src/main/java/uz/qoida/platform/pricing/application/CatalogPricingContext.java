package uz.qoida.platform.pricing.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What pricing needs from the catalog (ADR 0018, ADR 0016).
 *
 * <p>A port, so pricing depends on two facts rather than on the catalog module.
 * Deliberately narrow: pricing must not be able to read a draft, and the
 * publication id it gets is what ties a quote to the exact menu it was priced
 * against.
 */
public interface CatalogPricingContext {

    /**
     * The brand's live publication on one channel, or empty if it has none.
     *
     * <p>Takes the channel code because ADR 0036 makes the channel the thing that
     * supplies {@code catalog.publications.channel}: a brand may publish a
     * different menu to its kiosk than to its storefront, and a lookup hardcoded
     * to {@code STOREFRONT} — which this was — priced every channel against the
     * storefront menu while the kiosk menu sat live and unused.
     */
    Optional<UUID> activePublicationId(UUID tenantId, UUID brandId, String channelCode);

    /** Display names for the quote's line snapshots, in the brand's default locale. */
    Map<UUID, String> descriptions(UUID tenantId, UUID brandId, Set<UUID> variantIds);

    /**
     * Whether this brand still has something worth pricing under that id.
     *
     * <p>{@code pricing.prices.priceable_id} carries no foreign key — it points at
     * two different tables depending on the row — so nothing but this stops a
     * mistyped identifier becoming a price that matches nothing, on a menu whose
     * real dish then fails publication for having no price. The brand is in the
     * predicate because a variant id from another brand would otherwise look
     * exactly like a valid one.
     *
     * <p>Draft counts. A dish is priced before it is published, and refusing a
     * price until the menu is live would invert the order operators actually work
     * in. Archived does not: pricing something withdrawn is always a mistake.
     */
    boolean priceableExists(UUID tenantId, UUID brandId, PriceableType type, UUID priceableId);
}
