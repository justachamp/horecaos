package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.domain.channel.ChannelHostname;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPresentation;
import uz.horecaos.platform.tenancy.domain.channel.HostnameChallenge;

/**
 * Row 10.5's hostname mapping and SEO presentation (migrations V0403/V0404).
 *
 * <p>Every statement carries the tenant predicate in the query, the same
 * discipline {@link JdbcSalesChannelStore} documents for the same reason: a
 * channel id arrives from a URL and is not evidence of anything by itself.
 *
 * <p>{@link #bumpVersion} is copied rather than shared with {@code
 * JdbcSalesChannelStore}'s own private method of the same name and shape:
 * both exist so that a write against one of this channel's satellite tables
 * still goes through the version the console showed the operator, the
 * convention {@code SalesChannelService}'s own doc explains for {@code
 * replaceSocialLinks} et al.
 */
@Repository
public class JdbcChannelSetupStore {

    private final JdbcClient jdbc;

    public JdbcChannelSetupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ hostname

    public Optional<ChannelHostname> hostnameFor(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT hostname, verified, updated_at
                FROM tenant.channel_hostnames
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> new ChannelHostname(
                        tenantId,
                        channelId,
                        rs.getString("hostname"),
                        rs.getBoolean("verified"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Looked up without a tenant predicate on purpose: this is what an edge
     * caller has instead of a tenant id — an incoming {@code Host} header, and
     * nothing else — the same reasoning {@code hostname}'s own {@code
     * uq_channel_hostname} constraint (V0403) exists to make safe: the column
     * is globally unique, so an exact match names at most one tenant.
     */
    public Optional<ChannelHostname> byHostname(String hostname) {
        return jdbc.sql("""
                SELECT tenant_id, channel_id, verified, updated_at
                FROM tenant.channel_hostnames
                WHERE hostname = :hostname
                """)
                .param("hostname", hostname)
                .query((rs, n) -> new ChannelHostname(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("channel_id", UUID.class),
                        hostname,
                        rs.getBoolean("verified"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Sets (or replaces) this channel's hostname and bumps the channel's own
     * version, in one statement each, both gated on {@code expectedVersion}.
     *
     * @param challengeToken the DNS-TXT challenge to store alongside a
     *         custom hostname ({@code verified=false}); {@code null} for a
     *         platform-issued subdomain ({@code verified=true}), which needs
     *         no challenge -- see {@code ChannelSetupService#write}
     * @return false when the channel's version has moved since it was read;
     *         the caller never sees a partial write either way, since both
     *         statements run in its own {@code @Transactional} method
     * @throws org.springframework.dao.DataIntegrityViolationException on
     *         {@code uq_channel_hostname} — another channel, this tenant's or
     *         another tenant's, already answers on this hostname
     */
    public boolean setHostname(
            UUID tenantId,
            UUID channelId,
            String hostname,
            boolean verified,
            @Nullable String challengeToken,
            @Nullable Instant challengeIssuedAt,
            int expectedVersion,
            Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames
                    (tenant_id, channel_id, hostname, verified, challenge_token, challenge_issued_at,
                     created_at, updated_at)
                VALUES (:tenantId, :channelId, :hostname, :verified, :challengeToken, :challengeIssuedAt, :now, :now)
                ON CONFLICT (tenant_id, channel_id)
                DO UPDATE SET
                    hostname = :hostname,
                    verified = :verified,
                    challenge_token = :challengeToken,
                    challenge_issued_at = :challengeIssuedAt,
                    updated_at = :now
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("hostname", hostname)
                .param("verified", verified)
                .param("challengeToken", challengeToken)
                .param("challengeIssuedAt", challengeIssuedAt == null ? null : timestamp(challengeIssuedAt))
                .param("now", timestamp(now))
                .update();
        return true;
    }

    public boolean markVerified(UUID tenantId, UUID channelId, int expectedVersion, Instant now) {
        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        int updated = jdbc.sql("""
                UPDATE tenant.channel_hostnames
                SET verified = true, updated_at = :now
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("now", timestamp(now))
                .update();
        if (updated == 0) {
            throw new IllegalStateException("This channel has no hostname to verify");
        }
        return true;
    }

    /**
     * The active DNS-TXT challenge for this channel's current hostname, if
     * it has one -- absent for a platform-issued subdomain (never issued
     * one) and for a channel with no hostname claimed (nothing to look up).
     */
    public Optional<HostnameChallenge> challengeFor(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT hostname, challenge_token, challenge_issued_at
                FROM tenant.channel_hostnames
                WHERE tenant_id = :tenantId AND channel_id = :channelId AND challenge_token IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> HostnameChallenge.of(
                        tenantId,
                        channelId,
                        rs.getString("hostname"),
                        rs.getString("challenge_token"),
                        rs.getObject("challenge_issued_at", OffsetDateTime.class)
                                .toInstant()))
                .optional();
    }

    /**
     * Replaces this channel's challenge token and un-verifies it in the same
     * write: the previous token is no longer the one an operator was asked
     * to publish, so a hostname must not keep resolving on a proof that no
     * longer applies to the token the console now shows.
     *
     * @return false when the channel's version has moved since it was read
     * @throws IllegalStateException when this channel has no hostname to
     *         rotate a challenge for (never claimed, or platform-issued)
     */
    public boolean rotateChallenge(UUID tenantId, UUID channelId, String token, int expectedVersion, Instant now) {
        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        int updated = jdbc.sql("""
                UPDATE tenant.channel_hostnames
                SET challenge_token = :token, challenge_issued_at = :now, verified = false, updated_at = :now
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("token", token)
                .param("now", timestamp(now))
                .update();
        if (updated == 0) {
            throw new IllegalStateException("This channel has no hostname to rotate a challenge for");
        }
        return true;
    }

    /**
     * Every currently-verified custom hostname, for {@code
     * ChannelHostnameVerificationSweeper}'s periodic re-check. {@code
     * challenge_token IS NOT NULL} is what excludes a platform-issued
     * subdomain -- {@link #setHostname} never stores one for those, so the
     * predicate alone tells custom from platform-issued without a second
     * base-domain comparison here.
     */
    public List<HostnameChallenge> verifiedCustomHostnames() {
        return jdbc.sql("""
                SELECT tenant_id, channel_id, hostname, challenge_token, challenge_issued_at
                FROM tenant.channel_hostnames
                WHERE verified = true AND challenge_token IS NOT NULL
                """)
                .query((rs, n) -> HostnameChallenge.of(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("channel_id", UUID.class),
                        rs.getString("hostname"),
                        rs.getString("challenge_token"),
                        rs.getObject("challenge_issued_at", OffsetDateTime.class)
                                .toInstant()))
                .list();
    }

    /**
     * Un-verifies a hostname whose DNS-TXT challenge no longer resolves
     * (the sweep's own doc explains when). Not gated on {@code
     * expectedVersion}: this is a system-driven correction, not a reaction to
     * an operator's own read, the same distinction {@code IdempotencyPurgeJob}
     * and the other unconditional sweeps in this codebase already draw. It
     * still bumps {@code sales_channels.version} so a console holding a
     * stale version finds out on its next write, exactly as every other
     * satellite write to a channel does.
     */
    public void unverify(UUID tenantId, UUID channelId, Instant now) {
        jdbc.sql("""
                UPDATE tenant.channel_hostnames
                SET verified = false, updated_at = :now
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("now", timestamp(now))
                .update();
        jdbc.sql("""
                UPDATE tenant.sales_channels
                SET version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("now", timestamp(now))
                .update();
    }

    public boolean clearHostname(UUID tenantId, UUID channelId, int expectedVersion, Instant now) {
        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("DELETE FROM tenant.channel_hostnames WHERE tenant_id = :tenantId AND channel_id = :channelId")
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .update();
        return true;
    }

    // --------------------------------------------------------- presentation

    public ChannelPresentation presentationFor(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT seo_title, seo_description, og_image_asset_id
                FROM tenant.channel_presentation
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> new ChannelPresentation(
                        tenantId,
                        channelId,
                        rs.getString("seo_title"),
                        rs.getString("seo_description"),
                        rs.getObject("og_image_asset_id", UUID.class)))
                .optional()
                .orElseGet(() -> ChannelPresentation.empty(tenantId, channelId));
    }

    public boolean setPresentation(
            UUID tenantId,
            UUID channelId,
            @Nullable String seoTitle,
            @Nullable String seoDescription,
            @Nullable UUID ogImageAssetId,
            int expectedVersion,
            Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO tenant.channel_presentation
                    (tenant_id, channel_id, seo_title, seo_description, og_image_asset_id, created_at, updated_at)
                VALUES (:tenantId, :channelId, :seoTitle, :seoDescription, :ogImageAssetId, :now, :now)
                ON CONFLICT (tenant_id, channel_id)
                DO UPDATE SET
                    seo_title = :seoTitle,
                    seo_description = :seoDescription,
                    og_image_asset_id = :ogImageAssetId,
                    updated_at = :now
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("seoTitle", seoTitle)
                .param("seoDescription", seoDescription)
                .param("ogImageAssetId", ogImageAssetId)
                .param("now", timestamp(now))
                .update();
        return true;
    }

    /** Copied from {@code JdbcSalesChannelStore#bumpVersion} — see this class's own doc for why it is not shared. */
    private boolean bumpVersion(UUID tenantId, UUID channelId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE tenant.sales_channels
                SET version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :channelId AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("channelId", channelId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
