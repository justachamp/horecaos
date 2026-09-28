package uz.horecaos.platform.tenancy.domain.channel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Row 10.5's DNS-TXT ownership challenge for one channel's custom hostname
 * (migration V0428). {@link #recordName} is derived from {@link #hostname},
 * never stored separately -- see {@link HostnameChallenges#recordName}.
 *
 * <p>Never returned for a platform-issued subdomain: {@code
 * ChannelSetupService#setSubdomain} never issues a token for one, so {@code
 * JdbcChannelSetupStore#challengeFor} naturally has nothing to find.
 */
public record HostnameChallenge(
        UUID tenantId, UUID channelId, String hostname, String recordName, String token, Instant issuedAt) {

    public HostnameChallenge {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channelId, "A channel id is required");
        Objects.requireNonNull(hostname, "A hostname is required");
        Objects.requireNonNull(recordName, "A record name is required");
        Objects.requireNonNull(token, "A token is required");
        Objects.requireNonNull(issuedAt, "An issued-at instant is required");
    }

    public static HostnameChallenge of(UUID tenantId, UUID channelId, String hostname, String token, Instant issuedAt) {
        return new HostnameChallenge(
                tenantId, channelId, hostname, HostnameChallenges.recordName(hostname), token, issuedAt);
    }
}
