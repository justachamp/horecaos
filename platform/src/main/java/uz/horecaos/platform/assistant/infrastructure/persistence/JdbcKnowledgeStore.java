package uz.horecaos.platform.assistant.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code assistant.knowledge_entries} and {@code assistant.knowledge_entry_versions}
 * (V0511): append-only, so this class has no UPDATE and no DELETE and cannot grow
 * one without a migration granting it.
 *
 * <p>Every statement names the tenant. A retrieval for one tenant that could
 * return another's entry is a tenant-isolation defect and not a relevance bug
 * (ADR 0069), so the tenant is in the {@code WHERE} of every read and the entry
 * table's own key, not a filter applied afterwards.
 */
@Repository
public class JdbcKnowledgeStore {

    private static final String CURRENT = """
            SELECT e.id, e.scope_type, e.brand_id, e.location_id, e.locale, e.created_by, e.created_at,
                   v.version, v.status, v.question_form, v.answer_body, v.authored_by, v.reason, v.published_at
              FROM assistant.knowledge_entries e
              JOIN LATERAL (
                    SELECT version, status, question_form, answer_body, authored_by, reason, published_at
                      FROM assistant.knowledge_entry_versions
                     WHERE tenant_id = e.tenant_id AND entry_id = e.id
                     ORDER BY version DESC
                     LIMIT 1
                   ) v ON true
            """;

    private final JdbcClient jdbc;

    public JdbcKnowledgeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEntry(
            UUID id,
            UUID tenantId,
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String locale,
            String createdBy,
            Instant now) {
        jdbc.sql("""
                INSERT INTO assistant.knowledge_entries
                    (id, tenant_id, scope_type, brand_id, location_id, locale, created_by, created_at)
                VALUES (:id, :tenantId, :scopeType, :brandId, :locationId, :locale, :createdBy, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("scopeType", scopeType)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("locale", locale)
                .param("createdBy", createdBy)
                .param("now", utc(now))
                .update();
    }

    /**
     * Appends a version. The primary key {@code (tenant_id, entry_id, version)}
     * is the optimistic-concurrency check: a second writer racing for the same
     * next number collides here and is told the entry has moved.
     *
     * @throws org.springframework.dao.DuplicateKeyException another version with this number exists
     */
    public void insertVersion(
            UUID tenantId,
            UUID entryId,
            int version,
            String status,
            String questionForm,
            String answerBody,
            String authoredBy,
            String reason,
            Instant now) {
        jdbc.sql("""
                INSERT INTO assistant.knowledge_entry_versions
                    (tenant_id, entry_id, version, status, question_form, answer_body, authored_by, reason,
                     published_at)
                VALUES (:tenantId, :entryId, :version, :status, :questionForm, :answerBody, :authoredBy, :reason,
                        :now)
                """)
                .param("tenantId", tenantId)
                .param("entryId", entryId)
                .param("version", version)
                .param("status", status)
                .param("questionForm", questionForm)
                .param("answerBody", answerBody)
                .param("authoredBy", authoredBy)
                .param("reason", reason)
                .param("now", utc(now))
                .update();
    }

    /** One entry as it currently stands, or empty when it is not this tenant's. */
    public Optional<EntryRow> find(UUID tenantId, UUID entryId) {
        return jdbc.sql(CURRENT + " WHERE e.tenant_id = :tenantId AND e.id = :entryId")
                .param("tenantId", tenantId)
                .param("entryId", entryId)
                .query(JdbcKnowledgeStore::mapEntry)
                .optional();
    }

    /** The tenant-scope entries, newest first. */
    public List<EntryRow> listTenantScope(UUID tenantId, int limit) {
        return jdbc.sql(
                        CURRENT
                                + " WHERE e.tenant_id = :tenantId AND e.scope_type = 'TENANT' ORDER BY e.created_at DESC, e.id LIMIT :limit")
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(JdbcKnowledgeStore::mapEntry)
                .list();
    }

    /** One brand's own entries and its locations', newest first. */
    public List<EntryRow> listBrandScope(UUID tenantId, UUID brandId, int limit) {
        return jdbc.sql(
                        CURRENT
                                + " WHERE e.tenant_id = :tenantId AND e.brand_id = :brandId ORDER BY e.created_at DESC, e.id LIMIT :limit")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit)
                .query(JdbcKnowledgeStore::mapEntry)
                .list();
    }

    /**
     * Every entry a question about one brand may be answered from: the tenant's
     * own, the brand's, and those of the given locations, in the given languages,
     * whose current version is published. Bounded: an entry that does not fit in
     * the first {@code limit} is not retrieved, which an operator sees as the
     * entry not being used and is told about by the usage screen.
     */
    public List<EntryRow> retrievable(
            UUID tenantId, UUID brandId, Collection<UUID> locationIds, Collection<String> locales, int limit) {
        return jdbc.sql(CURRENT + """
                 WHERE e.tenant_id = :tenantId
                   AND e.locale = ANY(:locales)
                   AND (e.scope_type = 'TENANT'
                        OR (e.scope_type = 'BRAND' AND e.brand_id = :brandId)
                        OR (e.scope_type = 'LOCATION' AND e.brand_id = :brandId AND e.location_id = ANY(:locationIds)))
                   AND v.status = 'PUBLISHED'
                 ORDER BY e.created_at DESC, e.id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locales", locales.toArray(String[]::new))
                .param("locationIds", locationIds.toArray(UUID[]::new))
                .param("limit", limit)
                .query(JdbcKnowledgeStore::mapEntry)
                .list();
    }

    /** Every version of one entry, newest first. */
    public List<VersionRow> versions(UUID tenantId, UUID entryId) {
        return jdbc.sql("""
                SELECT version, status, question_form, answer_body, authored_by, reason, published_at
                  FROM assistant.knowledge_entry_versions
                 WHERE tenant_id = :tenantId AND entry_id = :entryId
                 ORDER BY version DESC
                """)
                .param("tenantId", tenantId)
                .param("entryId", entryId)
                .query((row, number) -> new VersionRow(
                        row.getInt("version"),
                        row.getString("status"),
                        row.getString("question_form"),
                        row.getString("answer_body"),
                        row.getString("authored_by"),
                        row.getString("reason"),
                        instant(row.getObject("published_at", OffsetDateTime.class))))
                .list();
    }

    /** How many of this tenant's entries currently answer a question, for the usage screen. */
    public long countPublished(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM (" + CURRENT
                        + " WHERE e.tenant_id = :tenantId) c WHERE c.status = 'PUBLISHED'")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    private static EntryRow mapEntry(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new EntryRow(
                row.getObject("id", UUID.class),
                row.getString("scope_type"),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("locale"),
                row.getString("created_by"),
                instant(row.getObject("created_at", OffsetDateTime.class)),
                new VersionRow(
                        row.getInt("version"),
                        row.getString("status"),
                        row.getString("question_form"),
                        row.getString("answer_body"),
                        row.getString("authored_by"),
                        row.getString("reason"),
                        instant(row.getObject("published_at", OffsetDateTime.class))));
    }

    private static Instant instant(@Nullable OffsetDateTime value) {
        return java.util.Objects.requireNonNull(value).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** An entry's identity with its current version. */
    public record EntryRow(
            UUID id,
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String locale,
            String createdBy,
            Instant createdAt,
            VersionRow current) {}

    /** One version of an entry. */
    public record VersionRow(
            int version,
            String status,
            String questionForm,
            String answerBody,
            String authoredBy,
            String reason,
            Instant publishedAt) {}

    /** Convenience for a caller that holds a set of ids. */
    public static Set<UUID> idsOf(Collection<EntryRow> rows) {
        return rows.stream().map(EntryRow::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
