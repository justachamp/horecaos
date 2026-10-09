package uz.horecaos.platform.content.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Which products and categories a brand's menu actually holds (ADR 0157, Decision 5), declared here
 * and implemented by {@code catalog}.
 *
 * <p>A link, a manual product group and a by-category group all name catalogue ids, and the
 * content read and the menu read must not disagree about existence: an id absent from the brand's
 * <em>active publication</em> for the channel is dropped from a group and degrades a link to
 * {@code NONE}. Availability (a stopped dish) stays the menu's business and is not folded into this
 * answer.
 *
 * <p>Tenant- and brand-scoped in every implementation: an id of another tenant's is simply absent
 * from the answer, the same shape as one that does not exist.
 */
public interface ContentTargetLookup {

    /**
     * The subset of these ids that the brand's active publication for this channel holds.
     *
     * @param channelCode the tenant's sales channel code, as the menu read takes it; a channel with
     *                    no active publication answers empty for everything
     */
    Existing existing(
            UUID tenantId, UUID brandId, String channelCode, Collection<UUID> productIds, Collection<UUID> categoryIds);

    /** What was found. */
    record Existing(Set<UUID> productIds, Set<UUID> categoryIds) {

        public Existing {
            productIds = Set.copyOf(productIds);
            categoryIds = Set.copyOf(categoryIds);
        }

        public static Existing none() {
            return new Existing(Set.of(), Set.of());
        }
    }
}
