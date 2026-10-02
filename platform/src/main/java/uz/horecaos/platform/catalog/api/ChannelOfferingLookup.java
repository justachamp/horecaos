package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the catalog says about whether a channel may sell a variant at a branch
 * (ADR 0016, ADR 0036, ADR 0141), for inventory's {@code AvailabilityResolver}.
 *
 * <p>The sibling of {@link MenuAvailabilityLookup} in the other direction: that
 * one is declared by catalog and implemented by inventory; this one is declared
 * by catalog, the owner of the answers, and implemented by catalog, and consumed
 * by inventory — so inventory never reads {@code catalog.branch_menu_bindings},
 * {@code catalog.location_offerings} or {@code catalog.channel_offering_exclusions}
 * itself, and the "offered" half of ADR 0141's {@code sellable()} stays catalog's.
 */
public interface ChannelOfferingLookup {

    /**
     * The menu {@code (location, channel)} publishes from: the channel-specific
     * binding, else the branch's default binding, else empty (ADR 0141,
     * Specification: Resolution). A {@code MENU} stop covers exactly the
     * {@code (location, channel)} pairs for which this answers its menu.
     *
     * @param channelId null reads the branch's default binding alone
     */
    Optional<UUID> menuBoundTo(UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId);

    /**
     * Every menu bound at this branch, the default and each channel's own — what the
     * stop list needs to say which {@code MENU} stops touch this branch at all.
     */
    Set<UUID> menusBoundAt(UUID tenantId, UUID brandId, UUID locationId);

    /**
     * Which of {@code variantIds} the catalog lets this channel sell at this
     * branch at {@code at}: the offering is {@code AVAILABLE} (the bound menu's
     * membership when the branch has one, else {@code location_offerings}), the
     * variant is not excluded from the channel, and it is inside its per-item
     * sale window. Read live, never from a publication.
     */
    Set<UUID> offeredVariants(
            UUID tenantId, UUID brandId, UUID locationId, UUID channelId, Set<UUID> variantIds, Instant at);

    /** The variants of a product, for a product-level stop written as a group of variant stops. */
    List<UUID> variantIdsOfProduct(UUID tenantId, UUID brandId, UUID productId);
}
