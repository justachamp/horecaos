package uz.horecaos.platform.legal.infrastructure.persistence;

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
import org.springframework.stereotype.Component;
import uz.horecaos.platform.legal.domain.TermsVersion;
import uz.horecaos.platform.legal.domain.TermsVersionSummary;

/**
 * Persists and reads a brand's terms-of-service versions (ADR 0068).
 *
 * <p>Insert-only, matching the tables' own grants: nothing here issues an
 * {@code UPDATE} or a {@code DELETE} against either table, because a version
 * a customer may have accepted is evidence, not a draft.
 */
@Component
public class JdbcTermsStore {

    private final JdbcClient jdbc;

    public JdbcTermsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The next version number for this brand: 1 if it has never published. */
    public int nextVersion(UUID tenantId, UUID brandId) {
        // COALESCE rather than a null check on the Java side: MAX() over zero
        // rows still returns exactly one row, with a null column, and asking
        // the database to resolve that beats guessing what an unwrapped null
        // Integer means at the call site.
        int max = jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM legal.terms_versions "
                        + "WHERE tenant_id = :tenantId AND brand_id = :brandId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
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
     *         the caller resolves it, matching
     *         {@code ApprovalPolicyService.author}'s own handling of
     *         {@code uq_approval_policy_version}
     */
    public void insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            int version,
            String publishedBy,
            Instant publishedAt,
            Map<String, String> contentsByLocale) {

        jdbc.sql("""
                INSERT INTO legal.terms_versions (id, tenant_id, brand_id, version, published_by, published_at)
                VALUES (:id, :tenantId, :brandId, :version, :publishedBy, :publishedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("version", version)
                .param("publishedBy", publishedBy)
                .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .update();

        for (Map.Entry<String, String> content : contentsByLocale.entrySet()) {
            jdbc.sql("""
                    INSERT INTO legal.terms_version_contents (id, tenant_id, terms_version_id, locale, body)
                    VALUES (:id, :tenantId, :termsVersionId, :locale, :body)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("termsVersionId", id)
                    .param("locale", content.getKey())
                    .param("body", content.getValue())
                    .update();
        }
    }

    /** The highest-versioned row for this brand, with its content map, or empty if never published. */
    public Optional<TermsVersion> current(UUID tenantId, UUID brandId) {
        Optional<VersionRow> row = jdbc.sql("""
                SELECT id, version, published_by, published_at
                FROM legal.terms_versions
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY version DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((rs, n) -> new VersionRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version"),
                        rs.getString("published_by"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()))
                .optional();

        return row.map(v -> new TermsVersion(
                v.id(), tenantId, brandId, v.version(), contentsOf(v.id()), v.publishedBy(), v.publishedAt()));
    }

    /** One specific historical version, by its number. */
    public Optional<TermsVersion> version(UUID tenantId, UUID brandId, int version) {
        Optional<VersionRow> row = jdbc.sql("""
                SELECT id, version, published_by, published_at
                FROM legal.terms_versions
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND version = :version
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("version", version)
                .query((rs, n) -> new VersionRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version"),
                        rs.getString("published_by"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()))
                .optional();

        return row.map(v -> new TermsVersion(
                v.id(), tenantId, brandId, v.version(), contentsOf(v.id()), v.publishedBy(), v.publishedAt()));
    }

    /** Every version published for this brand, newest first, without bodies. */
    public List<TermsVersionSummary> history(UUID tenantId, UUID brandId) {
        record Row(UUID id, int version, String locale, String publishedBy, Instant publishedAt) {}

        List<Row> rows = jdbc.sql("""
                SELECT v.id, v.version, v.published_by, v.published_at, c.locale
                FROM legal.terms_versions v
                LEFT JOIN legal.terms_version_contents c ON c.terms_version_id = v.id
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                ORDER BY v.version DESC
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version"),
                        rs.getString("locale"),
                        rs.getString("published_by"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()))
                .list();

        record Accumulator(int version, String publishedBy, Instant publishedAt, Set<String> locales) {}

        Map<UUID, Accumulator> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            Accumulator accumulator = byId.computeIfAbsent(
                    row.id(),
                    id -> new Accumulator(row.version(), row.publishedBy(), row.publishedAt(), new LinkedHashSet<>()));
            if (row.locale() != null) {
                accumulator.locales().add(row.locale());
            }
        }
        return byId.entrySet().stream()
                .map(entry -> new TermsVersionSummary(
                        entry.getKey(),
                        entry.getValue().version(),
                        entry.getValue().locales(),
                        entry.getValue().publishedBy(),
                        entry.getValue().publishedAt()))
                .toList();
    }

    private Map<String, String> contentsOf(UUID termsVersionId) {
        record ContentRow(String locale, String body) {}
        List<ContentRow> rows = jdbc.sql(
                        "SELECT locale, body FROM legal.terms_version_contents WHERE terms_version_id = :id")
                .param("id", termsVersionId)
                .query((rs, n) -> new ContentRow(rs.getString("locale"), rs.getString("body")))
                .list();
        Map<String, String> contents = new LinkedHashMap<>();
        for (ContentRow row : rows) {
            contents.put(row.locale(), row.body());
        }
        return contents;
    }

    private record VersionRow(UUID id, int version, String publishedBy, Instant publishedAt) {}
}
