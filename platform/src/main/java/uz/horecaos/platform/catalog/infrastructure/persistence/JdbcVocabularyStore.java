package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.time.Instant;
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
import uz.horecaos.platform.catalog.domain.Vocabularies.Kind;

/**
 * Persistence for the tags and ingredients registries and their product assignments (ADR 0174).
 *
 * <p>One store for both kinds: the table names are constants of {@link Kind}, never request input, and the
 * columns the two registries share are everything but a tag's icon and its two switches. Every statement names
 * the tenant, and an assignment names the brand through the product, so a tenant cannot assign another tenant's
 * entry and an assignment cannot name another brand's product (the composite foreign keys say the same).
 */
@Repository
public class JdbcVocabularyStore {

    private final JdbcClient jdbc;

    public JdbcVocabularyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One registry entry. {@code iconKey}, {@code showAsBadge} and {@code showAsFilter} are a tag's; an
     * ingredient reads them as {@code null}, {@code false} and {@code false}.
     */
    public record EntryRow(
            UUID id,
            UUID tenantId,
            String code,
            @Nullable String iconKey,
            boolean showAsBadge,
            boolean showAsFilter,
            int sortOrder,
            String status,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** An entry's position in a product's ordered set. */
    public record AssignmentRow(UUID entryId, int sortOrder) {}

    private static String columns(Kind kind) {
        return kind.hasDisplayOptions()
                ? "id, tenant_id, code, icon_key, show_as_badge, show_as_filter, sort_order, status, version, created_at, updated_at"
                : "id, tenant_id, code, CAST(NULL AS varchar) AS icon_key, false AS show_as_badge,"
                        + " false AS show_as_filter, sort_order, status, version, created_at, updated_at";
    }

    private static EntryRow map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new EntryRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("code"),
                row.getString("icon_key"),
                row.getBoolean("show_as_badge"),
                row.getBoolean("show_as_filter"),
                row.getInt("sort_order"),
                row.getString("status"),
                row.getInt("version"),
                row.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
    }

    // --------------------------------------------------------------------------------- registry

    /** Every entry of the tenant, active and archived, in display order. */
    public List<EntryRow> list(Kind kind, UUID tenantId) {
        return jdbc.sql("SELECT " + columns(kind) + " FROM " + kind.registryTable()
                        + " WHERE tenant_id = :tenantId ORDER BY sort_order, code")
                .param("tenantId", tenantId)
                .query((row, n) -> map(row))
                .list();
    }

    public Optional<EntryRow> find(Kind kind, UUID tenantId, UUID id) {
        return jdbc.sql("SELECT " + columns(kind) + " FROM " + kind.registryTable()
                        + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query((row, n) -> map(row))
                .optional();
    }

    /** The entries among these ids that belong to the tenant, whatever their status. */
    public List<EntryRow> findAll(Kind kind, UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + columns(kind) + " FROM " + kind.registryTable()
                        + " WHERE tenant_id = :tenantId AND id = ANY(:ids)")
                .param("tenantId", tenantId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, n) -> map(row))
                .list();
    }

    public int count(Kind kind, UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM " + kind.registryTable() + " WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the code is already the tenant's */
    public void insert(Kind kind, EntryRow row) {
        if (kind.hasDisplayOptions()) {
            jdbc.sql("""
                    INSERT INTO catalog.tags (id, tenant_id, code, icon_key, show_as_badge, show_as_filter,
                        sort_order, status, version, created_at, updated_at)
                    VALUES (:id, :tenantId, :code, :icon, :badge, :filter, :sortOrder, :status, :version,
                        :now, :now)
                    """)
                    .param("icon", row.iconKey())
                    .param("badge", row.showAsBadge())
                    .param("filter", row.showAsFilter())
                    .param("id", row.id())
                    .param("tenantId", row.tenantId())
                    .param("code", row.code())
                    .param("sortOrder", row.sortOrder())
                    .param("status", row.status())
                    .param("version", row.version())
                    .param("now", utc(row.createdAt()))
                    .update();
            return;
        }
        jdbc.sql("""
                INSERT INTO catalog.ingredients (id, tenant_id, code, sort_order, status, version, created_at,
                    updated_at)
                VALUES (:id, :tenantId, :code, :sortOrder, :status, :version, :now, :now)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("code", row.code())
                .param("sortOrder", row.sortOrder())
                .param("status", row.status())
                .param("version", row.version())
                .param("now", utc(row.createdAt()))
                .update();
    }

    /**
     * Changes what an author may change (never the code) and bumps the version, if the version still matches.
     *
     * @return the new version, or empty when the entry moved since it was read
     */
    public Optional<Integer> update(
            Kind kind,
            UUID tenantId,
            UUID id,
            @Nullable String iconKey,
            boolean showAsBadge,
            boolean showAsFilter,
            int sortOrder,
            String status,
            int expectedVersion,
            Instant now) {
        if (kind.hasDisplayOptions()) {
            return jdbc.sql("""
                            UPDATE catalog.tags
                               SET icon_key = :icon, show_as_badge = :badge, show_as_filter = :filter,
                                   sort_order = :sortOrder, status = :status, version = version + 1,
                                   updated_at = :now
                             WHERE tenant_id = :tenantId AND id = :id AND version = :expected
                            RETURNING version
                            """)
                    .param("icon", iconKey)
                    .param("badge", showAsBadge)
                    .param("filter", showAsFilter)
                    .param("sortOrder", sortOrder)
                    .param("status", status)
                    .param("now", utc(now))
                    .param("tenantId", tenantId)
                    .param("id", id)
                    .param("expected", expectedVersion)
                    .query(Integer.class)
                    .optional();
        }
        return jdbc.sql("""
                        UPDATE catalog.ingredients
                           SET sort_order = :sortOrder, status = :status, version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND id = :id AND version = :expected
                        RETURNING version
                        """)
                .param("sortOrder", sortOrder)
                .param("status", status)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("id", id)
                .param("expected", expectedVersion)
                .query(Integer.class)
                .optional();
    }

    // ---------------------------------------------------------------------------------- wording

    /** Every entry's wording of the tenant: entry id to locale to name. */
    public Map<UUID, Map<String, String>> namesForTenant(Kind kind, UUID tenantId) {
        Map<UUID, Map<String, String>> names = new LinkedHashMap<>();
        jdbc.sql("SELECT " + kind.entryColumn() + " AS entry_id, locale, name FROM " + kind.translationTable()
                        + " WHERE tenant_id = :tenantId ORDER BY locale")
                .param("tenantId", tenantId)
                .query((row, n) -> {
                    names.computeIfAbsent(row.getObject("entry_id", UUID.class), id -> new LinkedHashMap<>())
                            .put(row.getString("locale"), row.getString("name"));
                    return n;
                })
                .list();
        return names;
    }

    public Map<String, String> namesOf(Kind kind, UUID tenantId, UUID id) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT locale, name FROM " + kind.translationTable() + " WHERE tenant_id = :tenantId AND "
                        + kind.entryColumn() + " = :id ORDER BY locale")
                .param("tenantId", tenantId)
                .param("id", id)
                .query((row, n) -> {
                    names.put(row.getString("locale"), row.getString("name"));
                    return n;
                })
                .list();
        return names;
    }

    /** Writes the supplied locales; a locale that is not supplied keeps its wording. */
    public void upsertNames(Kind kind, UUID tenantId, UUID id, Map<String, String> names, Instant now) {
        names.forEach((locale, name) -> jdbc.sql("INSERT INTO " + kind.translationTable() + " (tenant_id, "
                        + kind.entryColumn() + ", locale, name, created_at, updated_at)"
                        + " VALUES (:tenantId, :id, :locale, :name, :now, :now)"
                        + " ON CONFLICT (" + kind.entryColumn() + ", locale) DO UPDATE"
                        + " SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at")
                .param("tenantId", tenantId)
                .param("id", id)
                .param("locale", locale)
                .param("name", name)
                .param("now", utc(now))
                .update());
    }

    // ------------------------------------------------------------------------------ assignments

    /** A product's ordered set, whatever the status of the entries in it. */
    public List<AssignmentRow> assignments(Kind kind, UUID tenantId, UUID brandId, UUID productId) {
        return jdbc.sql("SELECT " + kind.entryColumn() + " AS entry_id, sort_order FROM " + kind.assignmentTable()
                        + " WHERE tenant_id = :tenantId AND brand_id = :brandId AND product_id = :productId"
                        + " ORDER BY sort_order, created_at, id")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .query((row, n) -> new AssignmentRow(row.getObject("entry_id", UUID.class), row.getInt("sort_order")))
                .list();
    }

    /**
     * Replaces a product's set with these entries in this order. One transaction with the caller's audit fact;
     * the rows are rewritten, not diffed, because the set is small and order is part of it.
     */
    public void replaceAssignments(
            Kind kind, UUID tenantId, UUID brandId, UUID productId, List<UUID> orderedEntryIds, Instant now) {
        jdbc.sql("DELETE FROM " + kind.assignmentTable()
                        + " WHERE tenant_id = :tenantId AND brand_id = :brandId AND product_id = :productId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
        int position = 0;
        for (UUID entryId : orderedEntryIds) {
            jdbc.sql("INSERT INTO " + kind.assignmentTable() + " (id, tenant_id, brand_id, product_id, "
                            + kind.entryColumn() + ", sort_order, created_at, updated_at)"
                            + " VALUES (:id, :tenantId, :brandId, :productId, :entryId, :sortOrder, :now, :now)")
                    .param("id", uz.horecaos.platform.configuration.Ids.newId())
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("productId", productId)
                    .param("entryId", entryId)
                    .param("sortOrder", position++)
                    .param("now", utc(now))
                    .update();
        }
    }

    /**
     * For publication: each product's ordered entries, ACTIVE ones only. An archived entry keeps its row on the
     * product (it still resolves there) and leaves the next publication.
     */
    public Map<UUID, List<UUID>> activeAssignmentsForProducts(
            Kind kind, UUID tenantId, UUID brandId, Collection<UUID> productIds) {
        Map<UUID, List<UUID>> byProduct = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return byProduct;
        }
        jdbc.sql("SELECT a.product_id, a." + kind.entryColumn() + " AS entry_id FROM " + kind.assignmentTable()
                        + " a JOIN " + kind.registryTable() + " r ON r.id = a." + kind.entryColumn()
                        + " AND r.tenant_id = a.tenant_id"
                        + " WHERE a.tenant_id = :tenantId AND a.brand_id = :brandId AND a.product_id = ANY(:productIds)"
                        + " AND r.status = 'ACTIVE' ORDER BY a.product_id, a.sort_order, a.created_at, a.id")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productIds", productIds.toArray(UUID[]::new))
                .query((row, n) -> {
                    byProduct
                            .computeIfAbsent(row.getObject("product_id", UUID.class), id -> new java.util.ArrayList<>())
                            .add(row.getObject("entry_id", UUID.class));
                    return n;
                })
                .list();
        return byProduct;
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
