package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a marketplace reconciler needs from inventory: whether each variant is
 * sellable on one channel at one location, evaluated at the caller's own
 * {@code at} (ADR 0141 Decision 7).
 *
 * <p>The same composition the storefront menu, the cart and checkout read —
 * offering, supply state, the covering stops and the channel type's threshold —
 * behind one call, so what a partner is told can never be a second, drifting
 * notion of "available". The time is a parameter rather than the clock because
 * the resync sweep's correctness argument is that every time-dependent input (a
 * stop's {@code ends_at} above all) is evaluated at the sweep's own {@code now}.
 */
public interface ChannelAvailabilityPort {

    /**
     * One batched read; one entry per requested variant.
     *
     * @param channelId the {@code tenant.sales_channels} row whose {@code
     *     provider_installation_id} is the binding's installation
     */
    Map<UUID, ChannelAvailability> resolve(
            UUID tenantId, UUID brandId, UUID locationId, UUID channelId, Set<UUID> variantIds, Instant at);

    /**
     * @param sellable whether the variant may be sold on this channel at this location now
     * @param reasons  stable codes for why not ({@code ON_STOP}, {@code SOLD_OUT}, {@code
     *     CHANNEL_STOPPED}, {@code NOT_STOCKED_AT_LOCATION}, {@code NOT_OFFERED}); empty when sellable
     */
    record ChannelAvailability(boolean sellable, List<String> reasons) {

        public ChannelAvailability {
            reasons = List.copyOf(reasons);
        }

        public static ChannelAvailability ok() {
            return new ChannelAvailability(true, List.of());
        }

        public static ChannelAvailability blocked(List<String> reasons) {
            return new ChannelAvailability(false, reasons);
        }
    }
}
