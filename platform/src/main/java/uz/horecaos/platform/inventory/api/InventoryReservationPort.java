package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What checkout and the ADR 0019 inventory process manager need from inventory
 * (ADR 0017).
 *
 * <p>Deliberately three verbs. Ordering may take a hold, turn it into a sale, or
 * give it back; it may not read or write the movement ledger, which is the
 * evidence of what actually happened and is appended by inventory alone.
 *
 * <p>Everything here is keyed by the quote a hold was taken for rather than by a
 * reservation id, so a retried checkout and a restarted process manager both
 * name the same hold without having to remember an identifier they may never
 * have received.
 */
public interface InventoryReservationPort {

    /**
     * Checks availability and takes the hold in one transaction, so a dish marked
     * sold out between the check and the hold cannot slip through.
     *
     * <p>Repeating the call for the same quote returns the existing hold rather
     * than taking a second one.
     *
     * @param quoteExpiresAt the specific quote's own stored expiry (ADR 0018).
     *                       The reservation this call takes is never granted an
     *                       expiry earlier than this, regardless of how {@code
     *                       inventory.reservation_ttl_seconds} is configured
     *                       (ADR 0030) — a hold that outlived its own configured
     *                       TTL but expired before the quote would release stock
     *                       while the price on it was still acceptable at
     *                       checkout. See {@code
     *                       uz.horecaos.platform.inventory.domain.ReservationExpiry}.
     */
    ReservationResult reserveForQuote(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID quoteId,
            Instant quoteExpiresAt,
            Map<UUID, Integer> quantitiesByVariant);

    /**
     * {@link #reserveForQuote(UUID, UUID, UUID, UUID, Instant, Map)} on a named
     * channel (ADR 0141 Decision 6): the same hold, additionally refused with
     * {@code ON_STOP} where an active stop covers a line on that channel at that
     * location.
     *
     * <p>A default method that ignores the channel, so an implementation or a
     * test double written before stops existed keeps compiling and behaves as it
     * did; {@code InventoryService} overrides it, and the six-argument form above
     * is then the "no channel known" case.
     *
     * @param channelId {@code tenant.sales_channels.id} of the cart's or order's
     *     channel; null for no channel, in which case only the stops covering
     *     every channel at the location apply
     */
    default ReservationResult reserveForQuote(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID quoteId,
            Instant quoteExpiresAt,
            Map<UUID, Integer> quantitiesByVariant,
            @org.jspecify.annotations.Nullable UUID channelId) {
        return reserveForQuote(tenantId, brandId, locationId, quoteId, quoteExpiresAt, quantitiesByVariant);
    }

    /**
     * Turns a hold into a committed sale when an order is confirmed.
     *
     * @return false when there was no hold to commit, or it had already been
     *         released or expired — the status predicate is inside the UPDATE, so
     *         a late commit cannot revive stock that was already given back
     */
    boolean commit(UUID tenantId, UUID quoteId);

    /** Frees a hold when a checkout fails, an order is rejected, or a cart is abandoned. */
    boolean release(UUID tenantId, UUID quoteId);

    /**
     * The same check {@link #reserveForQuote} takes atomically with its hold,
     * without taking one (ADR 0017, ADR 0008).
     *
     * <p>For a caller that needs to know whether stock would allow a sale right
     * now and must not hold any of it while finding out — the ADR 0008
     * {@code ACTIVATION_SMOKE_TEST} onboarding step is the one today. Reusing
     * this rather than a bespoke read is the whole point: onboarding readiness
     * and a real checkout must agree about what "available" means, or a tenant
     * can pass the smoke test under a rule its own customers never see.
     */
    AvailabilityDecision checkAvailability(UUID tenantId, UUID locationId, Set<UUID> variantIds);

    /**
     * {@link #checkAvailability} on a named channel: what the cart asks before it
     * lets a dish in (ADR 0141 Decision 6), so its refusal agrees with what
     * checkout will do. A stop covering the channel refuses with {@code ON_STOP};
     * a per-channel-type threshold still does not — it only ever hides.
     *
     * <p>Ignores the channel by default, for the reason the channel-aware {@code
     * reserveForQuote} above does.
     */
    default AvailabilityDecision checkAvailabilityOnChannel(
            UUID tenantId, UUID locationId, Set<UUID> variantIds, @org.jspecify.annotations.Nullable UUID channelId) {
        return checkAvailability(tenantId, locationId, variantIds);
    }
}
