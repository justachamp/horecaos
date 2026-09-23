package uz.horecaos.platform.tenancy.domain.channel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Row 10.5: the hostname one channel answers on (migration V0403).
 *
 * <p>Never carries whether this hostname came from a platform-issued
 * subdomain or a tenant's own custom domain — that distinction matters only
 * at write time (see {@code ChannelSetupService#setSubdomain}/{@code
 * #setCustomHostname}) and would otherwise be one more field every reader has
 * to reconcile with {@link #verified()}.
 */
public record ChannelHostname(UUID tenantId, UUID channelId, String hostname, boolean verified, Instant updatedAt) {

    public ChannelHostname {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channelId, "A channel id is required");
        Objects.requireNonNull(hostname, "A hostname is required");
        Objects.requireNonNull(updatedAt, "An updated-at instant is required");
    }
}
