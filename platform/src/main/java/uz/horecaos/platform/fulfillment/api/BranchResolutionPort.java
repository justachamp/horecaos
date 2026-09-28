package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * "Which of this brand's branches serve this address" (ADR 0037, gap map row
 * {@code 1.3}), separate from {@link DeliveryFeePort} on purpose.
 *
 * <p>{@link DeliveryFeePort#resolve} deliberately never re-homes an address to
 * a branch that does cover it — see that class's own step 2 doc — because a
 * substitution made silently inside a pricing call changes the menu, the
 * prices and the preparation time under a customer who never agreed to any of
 * that. This port is the explicit, human-facing search {@link
 * DeliveryFeePort}'s own doc names as the correct place for that question to
 * live: a New Order operator asking "who can even take this" before a branch
 * is chosen at all, never inside a checkout that has already committed to one.
 *
 * <p>{@link #deliveryCandidates} reuses the identical {@code ST_Covers}
 * containment test and the identical three-key ranking (priority descending,
 * then smaller area, then zone id) {@link DeliveryFeePort}'s own resolver
 * runs — ADR 0037 has one zone algorithm, and this port is a second entry
 * point into it, never a second implementation of it.
 */
public interface BranchResolutionPort {

    /**
     * Every {@code ACTIVE} location of this brand with a live {@code DELIVERY}
     * zone bound to it that covers the point, one row per location — its own
     * best-matching zone, by the same ranking {@link DeliveryFeePort} would
     * apply if checkout later priced this exact address at this exact branch.
     *
     * @return unordered; a caller ranks by {@link DeliveryBranchMatch#zonePriority()}
     *         descending, then {@link DeliveryBranchMatch#zoneAreaSquareMeters()}
     *         ascending, then {@link DeliveryBranchMatch#zoneId()} ascending —
     *         {@code uz.horecaos.platform.fulfillment.domain.zone.ZoneCandidate#RANKING}'s
     *         own three keys, restated here because this DTO crosses the module
     *         boundary and cannot carry that domain type across it
     */
    List<DeliveryBranchMatch> deliveryCandidates(UUID tenantId, UUID brandId, GeoPoint point, Instant at);

    /**
     * Every {@code ACTIVE} location of this brand — a {@code PICKUP} order has
     * no address for a zone to contain, so there is no zone match to rank by;
     * every open branch is a candidate and the caller ranks them some other
     * way (current load, name).
     */
    List<PickupBranchCandidate> pickupCandidates(UUID tenantId, UUID brandId);

    /**
     * @param zoneId               the winning {@code DELIVERY} zone's lineage id
     * @param zoneVersion          that zone's live version number
     * @param zonePriority         that version's authored priority — ADR 0037's
     *                             first ranking key
     * @param zoneAreaSquareMeters that version's polygon area — ADR 0037's second
     *                             ranking key, the tiebreak for two zones of
     *                             equal priority
     */
    record DeliveryBranchMatch(
            UUID locationId,
            String displayName,
            UUID zoneId,
            int zoneVersion,
            int zonePriority,
            double zoneAreaSquareMeters) {}

    record PickupBranchCandidate(UUID locationId, String displayName) {}
}
