package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageSlug;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersion;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersionSummary;

/**
 * Persists and reads a channel's static pages (migration V0404).
 *
 * <p>Insert-only, matching the tables' own grants and copying {@code
 * legal.infrastructure.persistence.JdbcTermsStore} field for field: nothing
 * here issues an {@code UPDATE} or {@code DELETE}.
 */
@Repository
public class JdbcChannelPageStore {

    private final JdbcClient jdbc;

    public JdbcChannelPageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The next version number for this channel's slug: 1 if it has never published. */
    public int nextVersion(UUID tenantId, UUID channelId, ChannelPageSlug slug) {
        int max = jdbc.sql("""
                SELECT COALESCE(MAX(version), 0) FROM tenant.channel_pages
                WHERE tenant_id = :tenantId AND channel_id = :channelId AND slug = :slug
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("slug", slug.slug())
                .query(Integer.class)
                .single();
        return max + 1;
    }

    /**
     * Inserts a new version and its per-locale content, in the caller's own
     * transaction.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException on a
     *         concurrent publish racing for the same {@code version} number —
     *         the caller resolves it, matching {@code
     *         TermsPublishingService.publish}'s own handling
     */
    public void insert(
            UUID id,
            UUID tenantId,
            UUID channelId,
            ChannelPageSlug slug,
            int version,
            String publishedBy,
            Instant publishedAt,
            Map<String, String> contentsByLocale) {

        jdbc.sql("""
                INSERT INTO tenant.channel_pages (id, tenant_id, channel_id, slug, version, published_by, published_at)
                VALUES (:id, :tenantId, :channelId, :slug, :version, :publishedBy, :publishedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("slug", slug.slug())
                .param("version", version)
                .param("publishedBy", publishedBy)
                .param("publishedAt", timestamp(publishedAt))
                .update();

        for (Map.Entry<String, String> content : contentsByLocale.entrySet()) {
            jdbc.sql("""
                    INSERT INTO tenant.channel_page_contents (id, tenant_id, channel_page_id, locale, body)
                    VALUES (:id, :tenantId, :pageId, :locale, :body)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("pageId", id)
                    .param("locale", content.getKey())
                    .param("body", content.getValue())
                    .update();
        }
    }

    /** The highest-versioned row for this channel's slug, with its content map, or empty if never published. */
    public Optional<ChannelPageVersion> current(UUID tenantId, UUID channelId, ChannelPageSlug slug) {
        Optional<VersionRow> row = jdbc.sql("""
                SELECT id, version, published_by, published_at
                FROM tenant.channel_pages
                WHERE tenant_id = :tenantId AND channel_id = :channelId AND slug = :slug
                ORDER BY version DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("slug", slug.slug())
                .query(JdbcChannelPageStore::toVersionRow)
                .optional();

        return row.map(v -> toVersion(v, tenantId, channelId, slug));
    }

    /** One specific historical version, by its number. */
    public Optional<ChannelPageVersion> version(UUID tenantId, UUID channelId, ChannelPageSlug slug, int version) {
        Optional<VersionRow> row = jdbc.sql("""
                SELECT id, version, published_by, published_at
                FROM tenant.channel_pages
                WHERE tenant_id = :tenantId AND channel_id = :channelId AND slug = :slug AND version = :version
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("slug", slug.slug())
                .param("version", version)
                .query(JdbcChannelPageStore::toVersionRow)
                .optional();

        return row.map(v -> toVersion(v, tenantId, channelId, slug));
    }

    /** Every version published for every slug of this channel, newest first, without bodies. */
    public List<ChannelPageVersionSummary> history(UUID tenantId, UUID channelId) {
        record Row(UUID id, String slug, int version, String locale, String publishedBy, Instant publishedAt) {}

        List<Row> rows = jdbc.sql("""
                SELECT p.id, p.slug, p.version, p.published_by, p.published_at, c.locale
                FROM tenant.channel_pages p
                LEFT JOIN tenant.channel_page_contents c ON c.channel_page_id = p.id
                WHERE p.tenant_id = :tenantId AND p.channel_id = :channelId
                ORDER BY p.slug, p.version DESC
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getInt("version"),
                        rs.getString("locale"),
                        rs.getString("published_by"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()))
                .list();

        record Accumulator(String slug, int version, String publishedBy, Instant publishedAt, Set<String> locales) {}

        Map<UUID, Accumulator> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            Accumulator accumulator = byId.computeIfAbsent(
                    row.id(),
                    id -> new Accumulator(
                            row.slug(), row.version(), row.publishedBy(), row.publishedAt(), new LinkedHashSet<>()));
            if (row.locale() != null) {
                accumulator.locales().add(row.locale());
            }
        }
        return byId.entrySet().stream()
                .map(entry -> new ChannelPageVersionSummary(
                        entry.getKey(),
                        ChannelPageSlug.require(entry.getValue().slug()),
                        entry.getValue().version(),
                        entry.getValue().locales(),
                        entry.getValue().publishedBy(),
                        entry.getValue().publishedAt()))
                .toList();
    }

    private ChannelPageVersion toVersion(VersionRow row, UUID tenantId, UUID channelId, ChannelPageSlug slug) {
        return new ChannelPageVersion(
                row.id(),
                tenantId,
                channelId,
                slug,
                row.version(),
                contentsOf(row.id()),
                row.publishedBy(),
                row.publishedAt());
    }

    private Map<String, String> contentsOf(UUID channelPageId) {
        record ContentRow(String locale, String body) {}
        List<ContentRow> rows = jdbc.sql(
                        "SELECT locale, body FROM tenant.channel_page_contents WHERE channel_page_id = :id")
                .param("id", channelPageId)
                .query((rs, n) -> new ContentRow(rs.getString("locale"), rs.getString("body")))
                .list();
        Map<String, String> contents = new LinkedHashMap<>();
        for (ContentRow row : rows) {
            contents.put(row.locale(), row.body());
        }
        return contents;
    }

    private static VersionRow toVersionRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new VersionRow(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getString("published_by"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record VersionRow(UUID id, int version, String publishedBy, Instant publishedAt) {}
}
