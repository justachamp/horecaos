package uz.qoida.platform.tenancy.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the channel registry (ADR 0036).
 *
 * <p>Catalog, pricing and ordering all need to know what a channel is; owning
 * the registry in any one of them would make that module a dependency of the
 * other two, so it lives in {@code tenancy} and is reached through this port.
 *
 * <p>Every method takes the tenant id and every implementation puts it in the
 * query. A channel id is a UUID a caller may have received from anywhere, and a
 * lookup that matched on the id alone would happily return another tenant's
 * channel.
 */
public interface SalesChannelLookup {

    Optional<SalesChannel> byId(UUID tenantId, UUID channelId);

    Optional<SalesChannel> byCode(UUID tenantId, String code);

    /**
     * The payment-method codes this channel is configured to offer.
     *
     * <p>Enabled rows only. V0020 makes an absent row mean unavailable, so a
     * channel with no matrix offers nothing rather than everything, and a row
     * present with {@code enabled = false} is an operator's explicit no.
     *
     * <p>Configured is not the same as usable. Whether a merchant account exists
     * behind a code at a given branch is ADR 0013's question and is answered by
     * payments; this port answers only what the tenant chose to sell here.
     *
     * @return the codes, in a stable order, or empty for a channel that is not
     *         this tenant's
     */
    Set<String> enabledPaymentMethodCodes(UUID tenantId, UUID channelId);

    /**
     * The channel whose price-book assignments apply to {@code channelId}.
     *
     * <p>Follows {@code price_plane_channel_id} for exactly one hop. Empty when
     * the channel does not exist for this tenant, so a caller cannot fall back to
     * pricing an unknown channel at brand prices without noticing.
     */
    default Optional<UUID> pricingChannelId(UUID tenantId, UUID channelId) {
        return byId(tenantId, channelId).map(SalesChannel::pricingChannelId);
    }
}
