package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.domain.channel.ChannelHostname;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPresentation;

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
     * @return false when the channel's version has moved since it was read;
     *         the caller never sees a partial write either way, since both
     *         statements run in its own {@code @Transactional} method
     * @throws org.springframework.dao.DataIntegrityViolationException on
     *         {@code uq_channel_hostname} — another channel, this tenant's or
     *         another tenant's, already answers on this hostname
     */
    public boolean setHostname(
            UUID tenantId, UUID channelId, String hostname, boolean verified, int expectedVersion, Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified, created_at, updated_at)
                VALUES (:tenantId, :channelId, :hostname, :verified, :now, :now)
                ON CONFLICT (tenant_id, channel_id)
                DO UPDATE SET hostname = :hostname, verified = :verified, updated_at = :now
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("hostname", hostname)
                .param("verified", verified)
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
