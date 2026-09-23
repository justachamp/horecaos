package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.configuration.Ids;

/**
 * The named {@code Menu} entity (row 4.4a, V0389/V0390) — a brand-owned,
 * copyable assortment and its optional binding to a branch.
 *
 * <p>See {@code V0389__catalog_menus.sql} and
 * {@code V0390__catalog_branch_menu_bindings.sql} for why this is additive to
 * ADR 0016's {@code location_offerings}/publication model rather than a
 * replacement for it, and for the "one default plus one per-channel override"
 * binding rule this store enforces at the database via two partial unique
 * indexes.
 */
@Repository
public class JdbcMenuStore {

    private final JdbcClient jdbc;

    public JdbcMenuStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ menus

    public void insertMenu(MenuRow menu) {
        jdbc.sql("""
                INSERT INTO catalog.menus (id, tenant_id, brand_id, name, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, :status, 1, :now, :now)
                """)
                .param("id", menu.id())
                .param("tenantId", menu.tenantId())
                .param("brandId", menu.brandId())
                .param("name", menu.name())
                .param("status", menu.status())
                .param("now", utc(menu.createdAt()))
                .update();
    }

    public List<MenuRow> list(UUID tenantId, UUID brandId) {
        return jdbc.sql(SELECT_MENU + " WHERE tenant_id = :tenantId AND brand_id = :brandId ORDER BY name")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcMenuStore::mapMenu)
                .list();
    }

    public Optional<MenuRow> find(UUID tenantId, UUID brandId, UUID menuId) {
        return jdbc.sql(SELECT_MENU + " WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", menuId)
                .query(JdbcMenuStore::mapMenu)
                .optional();
    }

    /** @return the new version, or empty when the row moved since it was read */
    public Optional<Integer> update(
            UUID tenantId, UUID brandId, UUID menuId, String name, String status, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE catalog.menus
                SET name = :name, status = :status, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expectedVersion
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", menuId)
                .param("name", name)
                .param("status", status)
                .param("expectedVersion", expectedVersion)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    // ------------------------------------------------------------- membership

    public List<MenuItemRow> listItems(UUID tenantId, UUID brandId, UUID menuId) {
        return jdbc.sql("""
                SELECT mi.variant_id, mi.sort_order, mi.availability_default, mi.version,
                       v.sku, p.id AS product_id, t.name AS product_name
                FROM catalog.menu_items mi
                JOIN catalog.variants v
                    ON v.id = mi.variant_id AND v.tenant_id = mi.tenant_id AND v.brand_id = mi.brand_id
                JOIN catalog.products p
                    ON p.id = v.product_id AND p.tenant_id = v.tenant_id AND p.brand_id = v.brand_id
                LEFT JOIN catalog.translations t
                    ON t.entity_type = 'PRODUCT' AND t.entity_id = p.id AND t.tenant_id = p.tenant_id
                       AND t.brand_id = p.brand_id AND t.locale = :locale
                WHERE mi.tenant_id = :tenantId AND mi.brand_id = :brandId AND mi.menu_id = :menuId
                ORDER BY mi.sort_order, v.id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("menuId", menuId)
                .param("locale", "uz")
                .query((row, number) -> new MenuItemRow(
                        row.getObject("variant_id", UUID.class),
                        row.getObject("product_id", UUID.class),
                        coalesce(row.getString("product_name"), ""),
                        row.getString("sku"),
                        row.getInt("sort_order"),
                        row.getString("availability_default"),
                        row.getInt("version")))
                .list();
    }

    /**
     * Adds a variant to a menu, or — {@code ON CONFLICT} on the natural key,
     * the same discipline {@code JdbcCommentPresetStore#upsertProductPreset}
     * and {@code JdbcCatalogStore#upsertRecommendation} both already keep —
     * re-sorts and re-defaults it if already there. Never a second row for
     * one (menu, variant) pair.
     */
    public UUID upsertItem(
            UUID tenantId, UUID brandId, UUID menuId, UUID variantId, int sortOrder, String availabilityDefault) {
        return jdbc.sql("""
                INSERT INTO catalog.menu_items (
                    id, tenant_id, brand_id, menu_id, variant_id, sort_order, availability_default,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :menuId, :variantId, :sortOrder, :availabilityDefault,
                    1, now(), now())
                ON CONFLICT (menu_id, variant_id) DO UPDATE
                SET sort_order = EXCLUDED.sort_order,
                    availability_default = EXCLUDED.availability_default,
                    version = catalog.menu_items.version + 1,
                    updated_at = now()
                RETURNING id
                """)
                .param("id", Ids.newId())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("menuId", menuId)
                .param("variantId", variantId)
                .param("sortOrder", sortOrder)
                .param("availabilityDefault", availabilityDefault)
                .query(UUID.class)
                .single();
    }

    /** Idempotent: removing a variant already off the menu still resolves. */
    public boolean deleteItem(UUID tenantId, UUID brandId, UUID menuId, UUID variantId) {
        return jdbc.sql("""
                DELETE FROM catalog.menu_items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND menu_id = :menuId AND variant_id = :variantId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("menuId", menuId)
                        .param("variantId", variantId)
                        .update()
                > 0;
    }

    /**
     * The filtered select-all gesture: every active variant of this brand
     * matching an optional category and/or a case-insensitive product-name-
     * or-SKU search, added to the menu in one statement — never one round
     * trip per matched product. Already-present variants are re-sorted to
     * the end of the current membership rather than duplicated or skipped,
     * so a second, broader filter pass after a first narrow one still adds
     * only what is missing.
     *
     * <p>Sort order starts after the menu's current highest position, in
     * match order (product id, then variant id) — a deterministic order a
     * repeated call reproduces exactly, which matters for a test proving the
     * count is stable across a re-run.
     *
     * @param categoryId restricts to products placed in this category; {@code null} for every category
     * @param search     matches product name (this locale) or SKU, case-insensitively; {@code null} for no filter
     * @return how many (menu, variant) rows this call inserted or updated
     */
    public int addByFilter(
            UUID tenantId,
            UUID brandId,
            UUID menuId,
            @Nullable UUID categoryId,
            @Nullable String search,
            String availabilityDefault,
            String locale) {
        String searchPattern = search == null || search.isBlank() ? null : "%" + search.trim() + "%";
        return jdbc.sql("""
                WITH base AS (
                    SELECT mi.variant_id, mi.sort_order
                    FROM catalog.menu_items mi
                    WHERE mi.tenant_id = :tenantId AND mi.brand_id = :brandId AND mi.menu_id = :menuId
                ),
                start AS (
                    SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order FROM base
                ),
                matched AS (
                    SELECT v.id AS variant_id,
                           ROW_NUMBER() OVER (ORDER BY p.id, v.id) - 1 AS rank
                    FROM catalog.variants v
                    JOIN catalog.products p
                        ON p.id = v.product_id AND p.tenant_id = v.tenant_id AND p.brand_id = v.brand_id
                    LEFT JOIN catalog.translations t
                        ON t.entity_type = 'PRODUCT' AND t.entity_id = p.id AND t.tenant_id = p.tenant_id
                           AND t.brand_id = p.brand_id AND t.locale = :locale
                    WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                      AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                      AND (CAST(:categoryId AS uuid) IS NULL OR EXISTS (
                          SELECT 1 FROM catalog.category_products cp
                          WHERE cp.tenant_id = p.tenant_id AND cp.brand_id = p.brand_id
                            AND cp.product_id = p.id AND cp.category_id = :categoryId
                      ))
                      AND (CAST(:search AS varchar) IS NULL
                           OR t.name ILIKE :search OR v.sku ILIKE :search)
                )
                INSERT INTO catalog.menu_items (
                    id, tenant_id, brand_id, menu_id, variant_id, sort_order, availability_default,
                    version, created_at, updated_at)
                SELECT gen_random_uuid(), :tenantId, :brandId, :menuId, matched.variant_id,
                       start.next_order + matched.rank, :availabilityDefault, 1, now(), now()
                FROM matched, start
                ON CONFLICT (menu_id, variant_id) DO UPDATE
                SET availability_default = EXCLUDED.availability_default,
                    version = catalog.menu_items.version + 1,
                    updated_at = now()
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("menuId", menuId)
                .param("categoryId", categoryId)
                .param("search", searchPattern)
                .param("availabilityDefault", availabilityDefault)
                .param("locale", locale)
                .update();
    }

    /**
     * Copies every membership row from one menu to another, in one statement
     * — {@code MenuAuthoringService.copyMenu}'s "new menu with the same
     * membership". The target menu must already exist and carry no
     * membership of its own (a freshly created menu always does), since this
     * is a plain insert rather than an upsert.
     */
    public int copyMembership(UUID tenantId, UUID brandId, UUID sourceMenuId, UUID targetMenuId) {
        return jdbc.sql("""
                INSERT INTO catalog.menu_items (
                    id, tenant_id, brand_id, menu_id, variant_id, sort_order, availability_default,
                    version, created_at, updated_at)
                SELECT gen_random_uuid(), tenant_id, brand_id, :targetMenuId, variant_id, sort_order,
                       availability_default, 1, now(), now()
                FROM catalog.menu_items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND menu_id = :sourceMenuId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("sourceMenuId", sourceMenuId)
                .param("targetMenuId", targetMenuId)
                .update();
    }

    // ------------------------------------------------------------- bindings

    /**
     * Binds a menu to a branch, for one channel ({@code channelId} set) or
     * as the branch's default across every channel ({@code channelId} null)
     * — replacing whichever menu previously held that exact scope. Two
     * separate statements against the two independent partial unique
     * indexes {@code V0390} carries, the same reason
     * {@code JdbcCatalogStore#excludeFromChannel} is two statements: Postgres
     * requires an {@code ON CONFLICT} target to name one specific partial
     * index, not a column list that could match either.
     */
    public UUID upsertBinding(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId, UUID menuId, Instant now) {
        String sql = channelId != null ? """
                INSERT INTO catalog.branch_menu_bindings (
                    id, tenant_id, brand_id, location_id, channel_id, menu_id, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :menuId, 1, :now, :now)
                ON CONFLICT (location_id, channel_id) WHERE channel_id IS NOT NULL DO UPDATE
                SET menu_id = EXCLUDED.menu_id,
                    version = catalog.branch_menu_bindings.version + 1,
                    updated_at = :now
                RETURNING id
                """ : """
                INSERT INTO catalog.branch_menu_bindings (
                    id, tenant_id, brand_id, location_id, channel_id, menu_id, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, NULL, :menuId, 1, :now, :now)
                ON CONFLICT (location_id) WHERE channel_id IS NULL DO UPDATE
                SET menu_id = EXCLUDED.menu_id,
                    version = catalog.branch_menu_bindings.version + 1,
                    updated_at = :now
                RETURNING id
                """;
        return jdbc.sql(sql)
                .param("id", Ids.newId())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("menuId", menuId)
                .param("now", utc(now))
                .query(UUID.class)
                .single();
    }

    /** Idempotent: unbinding a scope that already carries no binding still resolves. */
    public boolean deleteBinding(UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID channelId) {
        return jdbc.sql("""
                DELETE FROM catalog.branch_menu_bindings
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND location_id = :locationId
                  AND channel_id IS NOT DISTINCT FROM :channelId
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("locationId", locationId)
                        .param("channelId", channelId)
                        .update()
                > 0;
    }

    /** Every binding this brand has authored, joined for the console's own list. */
    public List<BranchMenuBindingRow> listBindings(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT b.location_id, l.display_name AS location_name,
                       b.channel_id, sc.code AS channel_code,
                       b.menu_id, m.name AS menu_name, b.version
                FROM catalog.branch_menu_bindings b
                JOIN tenant.locations l ON l.tenant_id = b.tenant_id AND l.id = b.location_id
                LEFT JOIN tenant.sales_channels sc ON sc.tenant_id = b.tenant_id AND sc.id = b.channel_id
                JOIN catalog.menus m ON m.id = b.menu_id AND m.tenant_id = b.tenant_id AND m.brand_id = b.brand_id
                WHERE b.tenant_id = :tenantId AND b.brand_id = :brandId
                ORDER BY l.display_name, sc.code NULLS FIRST
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new BranchMenuBindingRow(
                        row.getObject("location_id", UUID.class),
                        row.getString("location_name"),
                        row.getObject("channel_id", UUID.class),
                        row.getString("channel_code"),
                        row.getObject("menu_id", UUID.class),
                        row.getString("menu_name"),
                        row.getInt("version")))
                .list();
    }

    /**
     * The menu bound to this branch for the storefront's own read
     * ({@code StorefrontCatalogQuery.menuFor}) — a channel-specific binding
     * first, falling back to the branch's default (channel_id NULL) binding,
     * and empty when neither exists (today's unmodified
     * {@code location_offerings} behaviour applies).
     *
     * <p>One query rather than two round trips: {@code ORDER BY (channel_id
     * IS NOT NULL) DESC} puts a channel-specific row first when both exist,
     * and the {@code OR} in the {@code WHERE} clause is what lets a default
     * row match regardless of the requested channel while a channel-specific
     * row matches only its own.
     */
    public Optional<UUID> findBoundMenuId(UUID tenantId, UUID brandId, UUID locationId, String channelCode) {
        return jdbc.sql("""
                SELECT b.menu_id
                FROM catalog.branch_menu_bindings b
                LEFT JOIN tenant.sales_channels sc ON sc.tenant_id = b.tenant_id AND sc.id = b.channel_id
                WHERE b.tenant_id = :tenantId AND b.brand_id = :brandId AND b.location_id = :locationId
                  AND (b.channel_id IS NULL OR sc.code = :channelCode)
                ORDER BY (b.channel_id IS NOT NULL) DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelCode", channelCode)
                .query(UUID.class)
                .optional();
    }

    /**
     * A bound menu's membership, in the exact shape
     * {@code StorefrontCatalogQuery} already consumes from {@code
     * offeringsForLocation}: variant id to {@link OfferingStatus}, HIDDEN
     * rows excluded here in SQL — the same double guard {@code
     * offeringsForLocation}'s own Javadoc explains ({@code
     * StorefrontCatalogQuery.variantsOf} filters HIDDEN again itself; take
     * out the pair or neither).
     */
    public Map<UUID, OfferingStatus> menuMembershipOfferings(UUID tenantId, UUID brandId, UUID menuId) {
        return jdbc
                .sql("""
                SELECT variant_id, availability_default
                FROM catalog.menu_items
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND menu_id = :menuId
                  AND availability_default <> 'HIDDEN'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("menuId", menuId)
                .query((row, number) -> Map.entry(
                        row.getObject("variant_id", UUID.class),
                        OfferingStatus.valueOf(row.getString("availability_default"))))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ------------------------------------------------------------- mapping

    private static final String SELECT_MENU = """
            SELECT id, tenant_id, brand_id, name, status, version, created_at
            FROM catalog.menus
            """;

    private static MenuRow mapMenu(ResultSet row, int number) throws SQLException {
        return new MenuRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("name"),
                row.getString("status"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String coalesce(@Nullable String value, String fallback) {
        return value != null ? value : fallback;
    }

    /** Translates a constraint violation into the sentence it is protecting. */
    public static RuntimeException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("uq_menu_name")) {
            return new IllegalStateException("A menu with this name already exists for this brand");
        }
        if (message.contains("fk_menu_item_variant")) {
            return new IllegalArgumentException("That variant does not belong to this brand");
        }
        if (message.contains("fk_branch_menu_binding_location")) {
            return new IllegalArgumentException("That branch does not belong to this brand");
        }
        if (message.contains("fk_branch_menu_binding_channel")) {
            return new IllegalArgumentException("That sales channel is not registered for this tenant");
        }
        if (message.contains("fk_branch_menu_binding_menu") || message.contains("fk_menu_item_menu")) {
            return new IllegalArgumentException("That menu does not belong to this brand");
        }
        return violation;
    }

    // ------------------------------------------------------------- records

    public record MenuRow(
            UUID id, UUID tenantId, UUID brandId, String name, String status, int version, Instant createdAt) {}

    /** One variant on one menu, joined with just enough product detail for the console's own list. */
    public record MenuItemRow(
            UUID variantId,
            UUID productId,
            String productName,
            @Nullable String sku,
            int sortOrder,
            String availabilityDefault,
            int version) {}

    /** One branch's binding to one menu, for one channel (or the branch's default, {@code channelId} null). */
    public record BranchMenuBindingRow(
            UUID locationId,
            String locationName,
            @Nullable UUID channelId,
            @Nullable String channelCode,
            UUID menuId,
            String menuName,
            int version) {}
}
