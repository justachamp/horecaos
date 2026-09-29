package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;

/**
 * Preset product comments (row 2.1b, ADR 0016's product catalog) — a
 * tenant-wide coded kitchen-instruction vocabulary, and which of a product's
 * lines may carry which preset.
 *
 * <p>See {@code V0378__catalog_comment_presets.sql} for why this is
 * tenant-scoped rather than brand-scoped like almost everything else in
 * {@code catalog}, and for why it is built now despite gap map row 4.7's
 * neighbouring vocabularies (attributes, tags, ingredients) staying blocked
 * on an unanswered ADR 0016 question this table does not touch.
 */
@Repository
public class JdbcCommentPresetStore {

    private final JdbcClient jdbc;

    public JdbcCommentPresetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ presets

    public void insert(PresetRow preset) {
        jdbc.sql("""
                INSERT INTO catalog.comment_presets (
                    id, tenant_id, code, label_ru, label_uz, label_en, pos_modifier_code,
                    sort_order, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :labelRu, :labelUz, :labelEn, :posModifierCode,
                    :sortOrder, :status, 1, :now, :now)
                """)
                .param("id", preset.id())
                .param("tenantId", preset.tenantId())
                .param("code", preset.code())
                .param("labelRu", preset.labelRu())
                .param("labelUz", preset.labelUz())
                .param("labelEn", preset.labelEn())
                .param("posModifierCode", preset.posModifierCode())
                .param("sortOrder", preset.sortOrder())
                .param("status", preset.status())
                .param("now", utc(preset.createdAt()))
                .update();
    }

    public List<PresetRow> list(UUID tenantId) {
        return jdbc.sql(SELECT_PRESET + " WHERE tenant_id = :tenantId ORDER BY sort_order, code")
                .param("tenantId", tenantId)
                .query(JdbcCommentPresetStore::mapPreset)
                .list();
    }

    public Optional<PresetRow> find(UUID tenantId, UUID presetId) {
        return jdbc.sql(SELECT_PRESET + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", presetId)
                .query(JdbcCommentPresetStore::mapPreset)
                .optional();
    }

    /**
     * Every preset among {@code codes} that still exists for this tenant, by
     * its own stable code — what {@code CommentPresetLookupAdapter} resolves
     * a cart line's chosen codes through at checkout, so the label text
     * snapshotted onto the order is the tenant's current wording rather than
     * whatever was true when the line was first added.
     */
    public List<PresetRow> findByCodes(UUID tenantId, java.util.Set<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_PRESET + " WHERE tenant_id = :tenantId AND code = ANY(:codes)")
                .param("tenantId", tenantId)
                .param("codes", codes.toArray(String[]::new))
                .query(JdbcCommentPresetStore::mapPreset)
                .list();
    }

    /**
     * Corrects a preset's labels, POS mapping, sort order or status,
     * conditional on the version the caller last saw.
     *
     * <p>A null label leaves that column exactly as it was (row 10.12): an editor
     * that shows only the locales a tenant supports sends no wording for the others,
     * and a write that blanked or replaced them would delete a translation nobody
     * chose to delete.
     *
     * @return the new version, or empty when the row moved since it was read
     */
    public Optional<Integer> update(
            UUID tenantId,
            UUID presetId,
            @Nullable String labelRu,
            @Nullable String labelUz,
            @Nullable String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE catalog.comment_presets
                SET label_ru = COALESCE(:labelRu, label_ru), label_uz = COALESCE(:labelUz, label_uz),
                    label_en = COALESCE(:labelEn, label_en),
                    pos_modifier_code = :posModifierCode, sort_order = :sortOrder, status = :status,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", presetId)
                .param("labelRu", labelRu)
                .param("labelUz", labelUz)
                .param("labelEn", labelEn)
                .param("posModifierCode", posModifierCode)
                .param("sortOrder", sortOrder)
                .param("status", status)
                .param("expectedVersion", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    // ------------------------------------------------------------ translations

    /**
     * Every per-locale label row the tenant's presets carry, grouped by preset --
     * one query for a whole list read rather than one per preset.
     *
     * <p>Filtered on the tenant in the query, never after loading: a preset id is
     * a UUID a caller may have received from anywhere.
     */
    public Map<UUID, Map<String, String>> translationsForTenant(UUID tenantId) {
        Map<UUID, Map<String, String>> byPreset = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT preset_id, locale, label FROM catalog.comment_preset_translations
                WHERE tenant_id = :tenantId ORDER BY preset_id, locale
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> {
                    byPreset.computeIfAbsent(
                                    row.getObject("preset_id", UUID.class), id -> new java.util.LinkedHashMap<>())
                            .put(row.getString("locale"), row.getString("label"));
                    return row.getString("locale");
                })
                .list();
        return byPreset;
    }

    /**
     * The per-locale label rows of several presets, grouped by preset -- one query for a
     * whole menu or checkout rather than one per preset, filtered on the tenant in the query
     * (a preset id is a UUID a caller may have received from anywhere).
     */
    public Map<UUID, Map<String, String>> translationsForPresets(UUID tenantId, Set<UUID> presetIds) {
        if (presetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, String>> byPreset = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT preset_id, locale, label FROM catalog.comment_preset_translations
                WHERE tenant_id = :tenantId AND preset_id = ANY(:presetIds)
                ORDER BY preset_id, locale
                """)
                .param("tenantId", tenantId)
                .param("presetIds", presetIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    byPreset.computeIfAbsent(
                                    row.getObject("preset_id", UUID.class), id -> new java.util.LinkedHashMap<>())
                            .put(row.getString("locale"), row.getString("label"));
                    return row.getString("locale");
                })
                .list();
        return byPreset;
    }

    /** One preset's translation rows, tenant-scoped in the query. */
    public Map<String, String> translationsFor(UUID tenantId, UUID presetId) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT locale, label FROM catalog.comment_preset_translations
                WHERE tenant_id = :tenantId AND preset_id = :presetId ORDER BY locale
                """)
                .param("tenantId", tenantId)
                .param("presetId", presetId)
                .query((row, number) -> labels.put(row.getString("locale"), row.getString("label")))
                .list();
        return labels;
    }

    /**
     * Writes the given locales' labels and touches no other -- never a whole-set
     * replace, so a locale the caller did not name (one the editor hides) keeps its
     * wording.
     *
     * <p>The {@code DO UPDATE} is conditioned on the row's own tenant. The conflict
     * target is {@code (preset_id, locale)}, which names no tenant, so without the
     * condition a caller passing another tenant's preset id would take the update
     * branch and rewrite that tenant's wording -- the exact shape {@code
     * CatalogTranslationTenantScopeTests} documents for {@code catalog.translations}.
     * The service also checks the preset is the caller's before it gets here; this
     * is the second lock, not the first.
     */
    public void upsertTranslations(UUID tenantId, UUID presetId, Map<String, String> labels, Instant now) {
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            jdbc.sql("""
                    INSERT INTO catalog.comment_preset_translations (tenant_id, preset_id, locale, label, created_at, updated_at)
                    VALUES (:tenantId, :presetId, :locale, :label, :now, :now)
                    ON CONFLICT (preset_id, locale) DO UPDATE
                    SET label = EXCLUDED.label, updated_at = EXCLUDED.updated_at
                    WHERE catalog.comment_preset_translations.tenant_id = EXCLUDED.tenant_id
                    """)
                    .param("tenantId", tenantId)
                    .param("presetId", presetId)
                    .param("locale", entry.getKey())
                    .param("label", entry.getValue())
                    .param("now", utc(now))
                    .update();
        }
    }

    // ---------------------------------------------------- product attachment

    /**
     * Attaches a preset to a product, or — the same call, {@code ON
     * CONFLICT} on the natural key — re-sorts it if already attached, the
     * same discipline {@code JdbcCatalogStore#upsertRecommendation} uses.
     * Never a second row for one pair.
     */
    public UUID upsertProductPreset(UUID tenantId, UUID brandId, UUID productId, UUID presetId, int sortOrder) {
        return jdbc.sql("""
                INSERT INTO catalog.product_comment_presets (
                    id, tenant_id, brand_id, product_id, preset_id, sort_order, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :productId, :presetId, :sortOrder, 1, now(), now())
                ON CONFLICT (product_id, preset_id) DO UPDATE
                SET sort_order = EXCLUDED.sort_order,
                    version = catalog.product_comment_presets.version + 1,
                    updated_at = now()
                RETURNING id
                """)
                .param("id", Ids.newId())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("presetId", presetId)
                .param("sortOrder", sortOrder)
                .query(UUID.class)
                .single();
    }

    /** Idempotent: detaching a pair that is already gone still resolves. */
    public boolean deleteProductPreset(UUID tenantId, UUID brandId, UUID productId, UUID presetId) {
        return jdbc.sql("""
                DELETE FROM catalog.product_comment_presets
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND product_id = :productId
                  AND preset_id = :presetId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("productId", productId)
                        .param("presetId", presetId)
                        .update()
                > 0;
    }

    /**
     * Every preset this product offers, joined to the vocabulary's own
     * labels and POS mapping — the product editor's management list and, in
     * time, the KDS/POS read this attaches its coded value for.
     */
    public List<ProductPresetRow> listForProduct(UUID tenantId, UUID brandId, UUID productId) {
        return jdbc.sql("""
                SELECT pp.preset_id, pp.sort_order,
                       p.code, p.label_ru, p.label_uz, p.label_en, p.pos_modifier_code, p.status
                FROM catalog.product_comment_presets pp
                JOIN catalog.comment_presets p ON p.id = pp.preset_id AND p.tenant_id = pp.tenant_id
                WHERE pp.tenant_id = :tenantId AND pp.brand_id = :brandId AND pp.product_id = :productId
                ORDER BY pp.sort_order, p.code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .query((row, number) -> new ProductPresetRow(
                        row.getObject("preset_id", UUID.class),
                        row.getInt("sort_order"),
                        row.getString("code"),
                        row.getString("label_ru"),
                        row.getString("label_uz"),
                        row.getString("label_en"),
                        row.getString("pos_modifier_code"),
                        row.getString("status")))
                .list();
    }

    /**
     * Every {@code ACTIVE} preset among several products' own offered subset,
     * grouped by product — one query for a whole menu read rather than one
     * per product, the same discipline {@code StorefrontCatalogQuery}'s own
     * bulk offering reads already follow.
     */
    public Map<UUID, List<ProductPresetRow>> listForProducts(UUID tenantId, UUID brandId, Set<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                SELECT pp.product_id, pp.preset_id, pp.sort_order,
                       p.code, p.label_ru, p.label_uz, p.label_en, p.pos_modifier_code, p.status
                FROM catalog.product_comment_presets pp
                JOIN catalog.comment_presets p ON p.id = pp.preset_id AND p.tenant_id = pp.tenant_id
                WHERE pp.tenant_id = :tenantId AND pp.brand_id = :brandId AND pp.product_id = ANY(:productIds)
                  AND p.status = 'ACTIVE'
                ORDER BY pp.product_id, pp.sort_order, p.code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productIds", productIds.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(
                        row.getObject("product_id", UUID.class),
                        new ProductPresetRow(
                                row.getObject("preset_id", UUID.class),
                                row.getInt("sort_order"),
                                row.getString("code"),
                                row.getString("label_ru"),
                                row.getString("label_uz"),
                                row.getString("label_en"),
                                row.getString("pos_modifier_code"),
                                row.getString("status"))))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Map.Entry::getKey,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                Map.Entry::getValue, java.util.stream.Collectors.toList())));
    }

    // ------------------------------------------------------------------- mapping

    private static final String SELECT_PRESET = """
            SELECT id, tenant_id, code, label_ru, label_uz, label_en, pos_modifier_code,
                   sort_order, status, version, created_at
            FROM catalog.comment_presets
            """;

    private static PresetRow mapPreset(ResultSet row, int number) throws SQLException {
        return new PresetRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("code"),
                row.getString("label_ru"),
                row.getString("label_uz"),
                row.getString("label_en"),
                row.getString("pos_modifier_code"),
                row.getInt("sort_order"),
                row.getString("status"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------- records

    public record PresetRow(
            UUID id,
            UUID tenantId,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int version,
            Instant createdAt) {}

    /** One preset attached to one product, joined to its own vocabulary row. */
    public record ProductPresetRow(
            UUID presetId,
            int sortOrder,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            String status) {}
}
