package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettings;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettingsDirectory;

/**
 * A TELEGRAM channel's own settings (ADR 0183 D27, {@code V0674}): {@code tenant.telegram_channel_settings} and its two
 * per-language texts.
 *
 * <p>Every statement carries the tenant predicate in the query, the discipline {@link JdbcChannelSetupStore} documents
 * for the same reason. A write goes through the channel's own version exactly like the hostname and the SEO facet, so a
 * console that read the channel before another one wrote cannot overwrite it.
 */
@Repository
public class JdbcTelegramChannelSettingsStore implements TelegramChannelSettingsDirectory {

    static final String MENU_BUTTON_LABEL = "MENU_BUTTON_LABEL";
    static final String WELCOME = "WELCOME";

    private final JdbcClient jdbc;

    public JdbcTelegramChannelSettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TelegramChannelSettings> forChannel(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT version FROM tenant.sales_channels
                WHERE tenant_id = :tenantId AND id = :channelId AND system_type = 'TELEGRAM'
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query(Integer.class)
                .optional()
                .map(version -> read(tenantId, channelId, version));
    }

    @Override
    public Optional<TelegramChannelSettings> forInstallation(UUID tenantId, UUID installationId) {
        record Backing(UUID channelId, int version) {}
        java.util.List<Backing> channels = jdbc.sql("""
                SELECT id, version FROM tenant.sales_channels
                WHERE tenant_id = :tenantId AND provider_installation_id = :installationId
                  AND system_type = 'TELEGRAM' AND status = 'ACTIVE'
                ORDER BY id
                LIMIT 2
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query((row, number) -> new Backing(row.getObject("id", UUID.class), row.getInt("version")))
                .list();
        return channels.size() == 1
                ? Optional.of(read(
                        tenantId,
                        channels.getFirst().channelId(),
                        channels.getFirst().version()))
                : Optional.empty();
    }

    private TelegramChannelSettings read(UUID tenantId, UUID channelId, int channelVersion) {
        Optional<Row> row = jdbc.sql("""
                SELECT brand_id, mini_app_enabled, mini_app_short_name, public_offer_link
                FROM tenant.telegram_channel_settings
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> new Row(
                        rs.getObject("brand_id", UUID.class),
                        rs.getBoolean("mini_app_enabled"),
                        rs.getString("mini_app_short_name"),
                        rs.getBoolean("public_offer_link")))
                .optional();

        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> welcomes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT kind, locale, body FROM tenant.telegram_channel_texts
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                ORDER BY kind, locale
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> new Text(rs.getString("kind"), rs.getString("locale"), rs.getString("body")))
                .list()
                .forEach(text ->
                        (MENU_BUTTON_LABEL.equals(text.kind()) ? labels : welcomes).put(text.locale(), text.body()));

        return row.map(r -> new TelegramChannelSettings(
                        tenantId,
                        channelId,
                        r.brandId(),
                        r.miniAppEnabled(),
                        r.shortName(),
                        r.publicOfferLink(),
                        labels,
                        welcomes,
                        channelVersion))
                .orElseGet(() -> new TelegramChannelSettings(
                        tenantId, channelId, null, false, null, false, labels, welcomes, channelVersion));
    }

    /**
     * Replaces the channel's settings and both text sets, gated on the channel's version.
     *
     * @return false when the channel's version has moved since it was read; nothing was written
     */
    public boolean replace(
            UUID tenantId,
            UUID channelId,
            @Nullable UUID brandId,
            boolean miniAppEnabled,
            @Nullable String shortName,
            boolean publicOfferLink,
            Map<String, String> menuButtonLabels,
            Map<String, String> welcomeTexts,
            int expectedVersion,
            Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO tenant.telegram_channel_settings
                    (tenant_id, channel_id, brand_id, mini_app_enabled, mini_app_short_name, public_offer_link,
                     created_at, updated_at)
                VALUES (:tenantId, :channelId, :brandId, :miniAppEnabled, :shortName, :publicOfferLink, :now, :now)
                ON CONFLICT (tenant_id, channel_id)
                DO UPDATE SET
                    brand_id = :brandId,
                    mini_app_enabled = :miniAppEnabled,
                    mini_app_short_name = :shortName,
                    public_offer_link = :publicOfferLink,
                    updated_at = :now
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("brandId", brandId)
                .param("miniAppEnabled", miniAppEnabled)
                .param("shortName", shortName)
                .param("publicOfferLink", publicOfferLink)
                .param("now", timestamp(now))
                .update();

        jdbc.sql("DELETE FROM tenant.telegram_channel_texts WHERE tenant_id = :tenantId AND channel_id = :channelId")
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .update();
        insertTexts(tenantId, channelId, MENU_BUTTON_LABEL, menuButtonLabels, now);
        insertTexts(tenantId, channelId, WELCOME, welcomeTexts, now);
        return true;
    }

    private void insertTexts(UUID tenantId, UUID channelId, String kind, Map<String, String> byLocale, Instant now) {
        for (Map.Entry<String, String> text : byLocale.entrySet()) {
            jdbc.sql("""
                    INSERT INTO tenant.telegram_channel_texts (tenant_id, channel_id, kind, locale, body, updated_at)
                    VALUES (:tenantId, :channelId, :kind, :locale, :body, :now)
                    """)
                    .param("tenantId", tenantId)
                    .param("channelId", channelId)
                    .param("kind", kind)
                    .param("locale", text.getKey())
                    .param("body", text.getValue())
                    .param("now", timestamp(now))
                    .update();
        }
    }

    /** Copied from {@code JdbcChannelSetupStore#bumpVersion}; see that class for why it is not shared. */
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

    private record Text(String kind, String locale, String body) {}

    private record Row(
            @Nullable UUID brandId,
            boolean miniAppEnabled,
            @Nullable String shortName,
            boolean publicOfferLink) {}
}
