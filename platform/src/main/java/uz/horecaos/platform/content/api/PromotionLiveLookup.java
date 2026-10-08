package uz.horecaos.platform.content.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Whether the promotions a promo card is bound to are in front of customers right now (ADR 0157,
 * Decision 5), declared here and implemented by {@code pricing}.
 *
 * <p>"The IA's «ads cannot drift out of sync with pricing» made a property, not a habit": a card
 * bound to a promotion is visible only while that promotion is live, and nothing consumes
 * {@code PromotionActivated}/{@code PromotionSuspended} to keep a copy, because this is one indexed
 * query ({@code ix_promotions_active}) answered when the response is built.
 */
public interface PromotionLiveLookup {

    /**
     * The subset of these promotions that is {@code ACTIVE} and inside its window at {@code at}
     * (start inclusive, end exclusive).
     */
    Set<UUID> live(UUID tenantId, UUID brandId, Collection<UUID> promotionIds, Instant at);

    /**
     * The subset that exists for this brand at all, in any status: what the authoring gate asks
     * before a card is bound to one. Additive to the lookup ADR 0157 names.
     */
    Set<UUID> existing(UUID tenantId, UUID brandId, Collection<UUID> promotionIds);
}
