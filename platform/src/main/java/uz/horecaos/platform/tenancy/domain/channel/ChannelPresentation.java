package uz.horecaos.platform.tenancy.domain.channel;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Row 10.5's SEO facet: a channel's own meta title/description and OG image
 * (migration V0404, {@code tenant.channel_presentation}).
 *
 * <p>{@code ogImageAssetId} names a {@code media.media_assets} row uploaded
 * under {@code MediaOwner.Scope.TENANT} — see {@code ChannelSetupService}'s
 * own doc for why this module takes no foreign key onto the media schema.
 */
public record ChannelPresentation(
        UUID tenantId,
        UUID channelId,
        @Nullable String seoTitle,
        @Nullable String seoDescription,
        @Nullable UUID ogImageAssetId) {

    public ChannelPresentation {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channelId, "A channel id is required");
    }

    public static ChannelPresentation empty(UUID tenantId, UUID channelId) {
        return new ChannelPresentation(tenantId, channelId, null, null, null);
    }
}
