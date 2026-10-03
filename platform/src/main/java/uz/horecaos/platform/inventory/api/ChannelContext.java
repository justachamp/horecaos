package uz.horecaos.platform.inventory.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The channel an availability question is asked on (ADR 0141 Decision 1).
 *
 * <p>Two independent facts, either of which a caller may not have. The
 * {@code channelId} decides which stops cover the question ({@code CHANNEL} and
 * {@code MENU} scopes); the {@code systemType} decides which per-channel-type
 * remaining-quantity threshold applies (gap map row 4.4c, {@code V0407}). The
 * storefront menu has both; the cart and checkout have only the channel id and
 * deliberately no system type, because a threshold only ever hides a dish and
 * never refuses a hold.
 *
 * <p>With no channel id, only the stops that cover every channel at the location
 * ({@code LOCATION} and {@code BRAND}) apply: a {@code CHANNEL} or {@code MENU}
 * stop cannot be said to cover a question that names no channel.
 */
public record ChannelContext(
        @Nullable UUID channelId, @Nullable String systemType) {

    /** No channel at all: today's plain stock check, plus the location-wide stops. */
    public static ChannelContext none() {
        return new ChannelContext(null, null);
    }

    public static ChannelContext ofChannel(@Nullable UUID channelId) {
        return new ChannelContext(channelId, null);
    }

    public static ChannelContext ofSystemType(@Nullable String systemType) {
        return new ChannelContext(null, systemType);
    }
}
