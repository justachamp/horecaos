package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.reviews.domain.TagCategory;
import uz.horecaos.platform.reviews.domain.TagSentiment;
import uz.horecaos.platform.reviews.domain.TagStatus;

/**
 * {@code reviews.feedback_tags} and {@code reviews.feedback_tag_translations} (V0543, ADR 0159): the
 * tenant's tag library.
 *
 * <p>Every statement names the tenant. A tag is retired and never deleted, so there is no delete here,
 * and the database refuses to change a tag's code, category or sentiment once a review carries it.
 */
@Repository
public class JdbcFeedbackTagStore {

    private final JdbcClient jdbc;

    public JdbcFeedbackTagStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One tag exactly as stored, without its labels. */
    public record TagRow(
            UUID id,
            UUID tenantId,
            String code,
            TagCategory category,
            TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            int sortOrder,
            TagStatus status,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    private static final String COLUMNS = """
            id, tenant_id, code, category, sentiment, icon_asset_id, sort_order, status, version,
            created_at, updated_at
            """;

    public List<TagRow> list(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM reviews.feedback_tags
                 WHERE tenant_id = :tenantId
                 ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** The tags a customer may be offered: active ones only, in the order the tenant set. */
    public List<TagRow> listActive(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM reviews.feedback_tags
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                 ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<TagRow> find(UUID tenantId, UUID tagId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM reviews.feedback_tags
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", tagId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The named tags that belong to this tenant; an id of another tenant is simply absent. */
    public List<TagRow> findAll(UUID tenantId, Collection<UUID> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM reviews.feedback_tags
                 WHERE tenant_id = :tenantId AND id IN (:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", tagIds)
                .query((rs, n) -> map(rs))
                .list();
    }

    public int countActive(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM reviews.feedback_tags WHERE tenant_id = :tenantId AND status = 'ACTIVE'")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
    }

    /** Whether any review carries this tag, which freezes its code, category and sentiment. */
    public boolean isUsed(UUID tenantId, UUID tagId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reviews.order_review_tags WHERE tenant_id = :tenantId AND tag_id = :tagId)
                """)
                .param("tenantId", tenantId)
                .param("tagId", tagId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Inserts a tag.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the code is already this tenant's
     */
    public void insert(TagRow row, String subject) {
        jdbc.sql("""
                INSERT INTO reviews.feedback_tags
                    (id, tenant_id, code, category, sentiment, icon_asset_id, sort_order, status, version,
                     created_at, updated_at, created_by_subject, updated_by_subject)
                VALUES (:id, :tenantId, :code, :category, :sentiment, :icon, :sortOrder, :status, 1,
                        :createdAt, :createdAt, :subject, :subject)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("code", row.code())
                .param("category", row.category().name())
                .param("sentiment", row.sentiment().name())
                .param("icon", row.iconAssetId())
                .param("sortOrder", row.sortOrder())
                .param("status", row.status().name())
                .param("createdAt", utc(row.createdAt()))
                .param("subject", subject)
                .update();
    }

    /**
     * Updates a tag under the version the editor read.
     *
     * @return the new version, or empty when the stored version is not {@code expectedVersion}
     */
    public Optional<Integer> update(
            UUID tenantId,
            UUID tagId,
            TagCategory category,
            TagSentiment sentiment,
            @Nullable UUID iconAssetId,
            int sortOrder,
            TagStatus status,
            int expectedVersion,
            String subject,
            Instant now) {
        return jdbc.sql("""
                UPDATE reviews.feedback_tags
                   SET category = :category, sentiment = :sentiment, icon_asset_id = :icon,
                       sort_order = :sortOrder, status = :status, version = version + 1,
                       updated_at = :now, updated_by_subject = :subject
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expected
                RETURNING version
                """)
                .param("category", category.name())
                .param("sentiment", sentiment.name())
                .param("icon", iconAssetId)
                .param("sortOrder", sortOrder)
                .param("status", status.name())
                .param("now", utc(now))
                .param("subject", subject)
                .param("tenantId", tenantId)
                .param("id", tagId)
                .param("expected", expectedVersion)
                .query(Integer.class)
                .optional();
    }

    // ------------------------------------------------------------------ labels

    /** Every label of every tag of the tenant: tag id -> locale -> label. */
    public Map<UUID, Map<String, String>> translationsForTenant(UUID tenantId) {
        Map<UUID, Map<String, String>> byTag = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT tag_id, locale, label FROM reviews.feedback_tag_translations
                 WHERE tenant_id = :tenantId ORDER BY tag_id, locale
                """).param("tenantId", tenantId).query((org.springframework.jdbc.core.RowCallbackHandler)
                rs -> byTag.computeIfAbsent(rs.getObject("tag_id", UUID.class), id -> new LinkedHashMap<>())
                        .put(rs.getString("locale"), rs.getString("label")));
        return byTag;
    }

    public Map<String, String> translationsFor(UUID tenantId, UUID tagId) {
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT locale, label FROM reviews.feedback_tag_translations
                 WHERE tenant_id = :tenantId AND tag_id = :tagId ORDER BY locale
                """)
                .param("tenantId", tenantId)
                .param("tagId", tagId)
                .query((org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> labels.put(rs.getString("locale"), rs.getString("label")));
        return labels;
    }

    /** Writes the named locales and leaves every other locale's wording alone: an edit never deletes a translation. */
    public void upsertTranslations(UUID tenantId, UUID tagId, Map<String, String> labels, Instant now) {
        for (Map.Entry<String, String> label : labels.entrySet()) {
            jdbc.sql("""
                    INSERT INTO reviews.feedback_tag_translations (tenant_id, tag_id, locale, label, created_at, updated_at)
                    VALUES (:tenantId, :tagId, :locale, :label, :now, :now)
                    ON CONFLICT (tag_id, locale) DO UPDATE SET label = EXCLUDED.label, updated_at = EXCLUDED.updated_at
                    """)
                    .param("tenantId", tenantId)
                    .param("tagId", tagId)
                    .param("locale", label.getKey())
                    .param("label", label.getValue())
                    .param("now", utc(now))
                    .update();
        }
    }

    private static TagRow map(ResultSet rs) throws SQLException {
        return new TagRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("code"),
                TagCategory.valueOf(rs.getString("category")),
                TagSentiment.valueOf(rs.getString("sentiment")),
                rs.getObject("icon_asset_id", UUID.class),
                rs.getInt("sort_order"),
                TagStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
