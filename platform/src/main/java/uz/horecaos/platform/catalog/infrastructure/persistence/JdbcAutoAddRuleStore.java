package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.catalog.domain.AutoAddRules.AutoAddRule;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Band;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Kind;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Mode;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Trigger;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.configuration.Ids;

/**
 * Persistence for auto-add rules (ADR 0158): {@code catalog.auto_add_rules} and the three child
 * tables a PUT replaces as sets.
 *
 * <p>Every statement carries the tenant and the brand, so a rule id from another brand finds no
 * row. A rule is archived and never deleted (the application role holds no DELETE on it), so a
 * published snapshot can always be traced to a row; its children carry no status and are replaced
 * in the same transaction as the rule's version bump, the snapshot holding its own copy.
 */
@Repository
public class JdbcAutoAddRuleStore {

    private final JdbcClient jdbc;

    public JdbcAutoAddRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes the rule and its three child sets. */
    public void insert(AutoAddRule rule) {
        jdbc.sql("""
                INSERT INTO catalog.auto_add_rules (
                    id, tenant_id, brand_id, code, kind, mode, status, add_variant_id, quantity,
                    trigger_threshold, repeat_per_multiple, minimum_quantity, maximum_quantity, mandatory,
                    fulfillment_modes, channel_system_types, sort_order, version)
                VALUES (
                    :id, :tenantId, :brandId, :code, :kind, :mode, :status, :addVariantId, :quantity,
                    :threshold, :repeat, :minimum, :maximum, :mandatory,
                    :modes, :channels, :sortOrder, :version)
                """).params(columns(rule)).update();
        replaceChildren(rule);
    }

    /**
     * Replaces the rule's fields and its child sets, only if it is still at {@code expectedVersion}.
     *
     * @return false when the version moved on; nothing is written
     */
    public boolean update(AutoAddRule rule, int expectedVersion) {
        Map<String, Object> params = columns(rule);
        params.put("expectedVersion", expectedVersion);
        int updated = jdbc.sql("""
                UPDATE catalog.auto_add_rules SET
                    mode = :mode, status = :status, add_variant_id = :addVariantId, quantity = :quantity,
                    trigger_threshold = :threshold, repeat_per_multiple = :repeat,
                    minimum_quantity = :minimum, maximum_quantity = :maximum, mandatory = :mandatory,
                    fulfillment_modes = :modes, channel_system_types = :channels, sort_order = :sortOrder,
                    version = :version, updated_at = now()
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expectedVersion
                """).params(params).update();
        if (updated == 0) {
            return false;
        }
        replaceChildren(rule);
        return true;
    }

    public Optional<AutoAddRule> find(UUID tenantId, UUID brandId, UUID ruleId) {
        return load(tenantId, brandId, ruleId, null).stream().findFirst();
    }

    /** Every rule of the brand, any status, in display order. */
    public List<AutoAddRule> list(UUID tenantId, UUID brandId) {
        return load(tenantId, brandId, null, null);
    }

    /** The rules the next publication carries. */
    public List<AutoAddRule> active(UUID tenantId, UUID brandId) {
        return load(tenantId, brandId, null, Status.ACTIVE);
    }

    public boolean codeTaken(UUID tenantId, UUID brandId, String code) {
        return jdbc.sql("SELECT 1 FROM catalog.auto_add_rules WHERE tenant_id = :t AND brand_id = :b AND code = :c")
                .param("t", tenantId)
                .param("b", brandId)
                .param("c", code)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** The ids among {@code locationIds} that are locations of this brand. */
    public Set<UUID> locationsOfBrand(UUID tenantId, UUID brandId, Collection<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                        SELECT id FROM tenant.locations
                        WHERE tenant_id = :t AND brand_id = :b AND id = ANY(:ids)
                        """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ids", locationIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }

    /** The ids among these that are products of this brand. */
    public Set<UUID> productsOfBrand(UUID tenantId, UUID brandId, Collection<UUID> ids) {
        return idsOf("catalog.products", tenantId, brandId, ids);
    }

    /** The ids among these that are categories of this brand. */
    public Set<UUID> categoriesOfBrand(UUID tenantId, UUID brandId, Collection<UUID> ids) {
        return idsOf("catalog.categories", tenantId, brandId, ids);
    }

    private Set<UUID> idsOf(String table, UUID tenantId, UUID brandId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        // The table name is one of two literals chosen by the two callers above, never input.
        return Set.copyOf(
                jdbc.sql("SELECT id FROM " + table + " WHERE tenant_id = :t AND brand_id = :b AND id = ANY(:ids)")
                        .param("t", tenantId)
                        .param("b", brandId)
                        .param("ids", ids.toArray(UUID[]::new))
                        .query(UUID.class)
                        .list());
    }

    /**
     * Replaces a rule's customer text with exactly these locales: the ones named are upserted and
     * any other is removed, so the translation table never holds a locale the author took out.
     */
    public void replaceCustomerText(UUID tenantId, UUID brandId, UUID ruleId, Map<String, String> textByLocale) {
        for (Map.Entry<String, String> entry : textByLocale.entrySet()) {
            jdbc.sql("""
                    INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                    VALUES (:t, :b, 'AUTO_ADD_RULE', :id, :locale, :name)
                    ON CONFLICT (tenant_id, entity_type, entity_id, locale) DO UPDATE
                    SET name = EXCLUDED.name, version = catalog.translations.version + 1, updated_at = now()
                    """)
                    .param("t", tenantId)
                    .param("b", brandId)
                    .param("id", ruleId)
                    .param("locale", entry.getKey())
                    .param("name", entry.getValue())
                    .update();
        }
        jdbc.sql("""
                DELETE FROM catalog.translations
                WHERE tenant_id = :t AND brand_id = :b AND entity_type = 'AUTO_ADD_RULE'
                  AND entity_id = :id AND NOT (locale = ANY(:locales))
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("id", ruleId)
                .param("locales", textByLocale.keySet().toArray(String[]::new))
                .update();
    }

    /** The customer text of these rules, by rule and then by locale. */
    public Map<UUID, Map<String, String>> customerText(UUID tenantId, UUID brandId, Collection<UUID> ruleIds) {
        Map<UUID, Map<String, String>> byRule = new LinkedHashMap<>();
        if (ruleIds.isEmpty()) {
            return byRule;
        }
        jdbc.sql("""
                SELECT entity_id, locale, name FROM catalog.translations
                WHERE tenant_id = :t AND brand_id = :b AND entity_type = 'AUTO_ADD_RULE' AND entity_id = ANY(:ids)
                ORDER BY locale
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ids", ruleIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    byRule.computeIfAbsent(row.getObject("entity_id", UUID.class), id -> new LinkedHashMap<>())
                            .put(row.getString("locale"), row.getString("name"));
                    return 0;
                })
                .list();
        return byRule;
    }

    // ---------------------------------------------------------------------------- internals

    private void replaceChildren(AutoAddRule rule) {
        Map<String, Object> key = Map.of("rule", rule.id(), "t", rule.tenantId(), "b", rule.brandId());
        for (String table : List.of("auto_add_rule_triggers", "auto_add_rule_bands", "auto_add_rule_locations")) {
            jdbc.sql("DELETE FROM catalog." + table + " WHERE rule_id = :rule AND tenant_id = :t AND brand_id = :b")
                    .params(key)
                    .update();
        }
        for (Trigger trigger : rule.triggers()) {
            jdbc.sql("""
                    INSERT INTO catalog.auto_add_rule_triggers (
                        id, rule_id, tenant_id, brand_id, variant_id, product_id, category_id)
                    VALUES (:id, :rule, :t, :b, :variantId, :productId, :categoryId)
                    """)
                    .param("id", Ids.newId())
                    .param("rule", rule.id())
                    .param("t", rule.tenantId())
                    .param("b", rule.brandId())
                    .param("variantId", trigger.variantId())
                    .param("productId", trigger.productId())
                    .param("categoryId", trigger.categoryId())
                    .update();
        }
        for (Band band : rule.bands()) {
            jdbc.sql("""
                    INSERT INTO catalog.auto_add_rule_bands (
                        id, rule_id, tenant_id, brand_id, portions_from, portions_to, add_variant_id, quantity)
                    VALUES (:id, :rule, :t, :b, :from, :to, :variantId, :quantity)
                    """)
                    .param("id", Ids.newId())
                    .param("rule", rule.id())
                    .param("t", rule.tenantId())
                    .param("b", rule.brandId())
                    .param("from", band.portionsFrom())
                    .param("to", band.portionsTo())
                    .param("variantId", band.addVariantId())
                    .param("quantity", band.quantity())
                    .update();
        }
        for (UUID locationId : rule.locationIds()) {
            jdbc.sql("""
                    INSERT INTO catalog.auto_add_rule_locations (rule_id, tenant_id, brand_id, location_id)
                    VALUES (:rule, :t, :b, :location)
                    """)
                    .param("rule", rule.id())
                    .param("t", rule.tenantId())
                    .param("b", rule.brandId())
                    .param("location", locationId)
                    .update();
        }
    }

    private static Map<String, Object> columns(AutoAddRule rule) {
        // A HashMap because several values are legitimately null.
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("id", rule.id());
        params.put("tenantId", rule.tenantId());
        params.put("brandId", rule.brandId());
        params.put("code", rule.code());
        params.put("kind", rule.kind().name());
        params.put("mode", rule.mode().name());
        params.put("status", rule.status().name());
        params.put("addVariantId", rule.addVariantId());
        params.put("quantity", rule.quantity());
        params.put("threshold", rule.triggerThreshold());
        params.put("repeat", rule.repeatPerMultiple());
        params.put("minimum", rule.minimumQuantity());
        params.put("maximum", rule.maximumQuantity());
        params.put("mandatory", rule.mandatory());
        params.put("modes", sorted(rule.fulfillmentModes()));
        params.put("channels", sorted(rule.channelSystemTypes()));
        params.put("sortOrder", rule.sortOrder());
        params.put("version", rule.version());
        return params;
    }

    private static String[] sorted(Set<String> values) {
        return values.stream().sorted().toArray(String[]::new);
    }

    private List<AutoAddRule> load(UUID tenantId, UUID brandId, @Nullable UUID ruleId, @Nullable Status status) {
        List<RuleRow> rows = jdbc.sql("""
                SELECT id, code, kind, mode, status, add_variant_id, quantity, trigger_threshold,
                       repeat_per_multiple, minimum_quantity, maximum_quantity, mandatory,
                       fulfillment_modes, channel_system_types, sort_order, version
                FROM catalog.auto_add_rules
                WHERE tenant_id = :t AND brand_id = :b
                  AND (CAST(:ruleId AS uuid) IS NULL OR id = CAST(:ruleId AS uuid))
                  AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                ORDER BY sort_order, code
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ruleId", ruleId)
                .param("status", status == null ? null : status.name())
                .query(JdbcAutoAddRuleStore::mapRow)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID[] ids = rows.stream().map(RuleRow::id).toArray(UUID[]::new);
        Map<UUID, List<Trigger>> triggers = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rule_id, variant_id, product_id, category_id FROM catalog.auto_add_rule_triggers
                WHERE tenant_id = :t AND brand_id = :b AND rule_id = ANY(:ids) ORDER BY id
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ids", ids)
                .query((row, number) -> {
                    triggers.computeIfAbsent(row.getObject("rule_id", UUID.class), id -> new ArrayList<>())
                            .add(new Trigger(
                                    row.getObject("variant_id", UUID.class),
                                    row.getObject("product_id", UUID.class),
                                    row.getObject("category_id", UUID.class)));
                    return 0;
                })
                .list();
        Map<UUID, List<Band>> bands = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rule_id, portions_from, portions_to, add_variant_id, quantity
                FROM catalog.auto_add_rule_bands
                WHERE tenant_id = :t AND brand_id = :b AND rule_id = ANY(:ids) ORDER BY portions_from
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ids", ids)
                .query((row, number) -> {
                    bands.computeIfAbsent(row.getObject("rule_id", UUID.class), id -> new ArrayList<>())
                            .add(new Band(
                                    row.getBigDecimal("portions_from"),
                                    row.getBigDecimal("portions_to"),
                                    row.getObject("add_variant_id", UUID.class),
                                    row.getBigDecimal("quantity")));
                    return 0;
                })
                .list();
        Map<UUID, Set<UUID>> locations = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rule_id, location_id FROM catalog.auto_add_rule_locations
                WHERE tenant_id = :t AND brand_id = :b AND rule_id = ANY(:ids)
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ids", ids)
                .query((row, number) -> {
                    locations
                            .computeIfAbsent(row.getObject("rule_id", UUID.class), id -> new LinkedHashSet<>())
                            .add(row.getObject("location_id", UUID.class));
                    return 0;
                })
                .list();
        return rows.stream()
                .map(row -> new AutoAddRule(
                        row.id(),
                        tenantId,
                        brandId,
                        row.code(),
                        row.kind(),
                        row.mode(),
                        row.status(),
                        row.addVariantId(),
                        row.quantity(),
                        row.threshold(),
                        row.repeat(),
                        row.minimum(),
                        row.maximum(),
                        row.mandatory(),
                        row.modes(),
                        row.channels(),
                        row.sortOrder(),
                        row.version(),
                        triggers.getOrDefault(row.id(), List.of()),
                        bands.getOrDefault(row.id(), List.of()),
                        locations.getOrDefault(row.id(), Set.of())))
                .toList();
    }

    private record RuleRow(
            UUID id,
            String code,
            Kind kind,
            Mode mode,
            Status status,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal threshold,
            boolean repeat,
            BigDecimal minimum,
            BigDecimal maximum,
            boolean mandatory,
            Set<String> modes,
            Set<String> channels,
            int sortOrder,
            int version) {}

    private static RuleRow mapRow(ResultSet row, int number) throws SQLException {
        return new RuleRow(
                row.getObject("id", UUID.class),
                row.getString("code"),
                Kind.valueOf(row.getString("kind")),
                Mode.valueOf(row.getString("mode")),
                Status.valueOf(row.getString("status")),
                row.getObject("add_variant_id", UUID.class),
                row.getBigDecimal("quantity"),
                row.getBigDecimal("trigger_threshold"),
                row.getBoolean("repeat_per_multiple"),
                row.getBigDecimal("minimum_quantity"),
                row.getBigDecimal("maximum_quantity"),
                row.getBoolean("mandatory"),
                strings(row.getArray("fulfillment_modes")),
                strings(row.getArray("channel_system_types")),
                row.getInt("sort_order"),
                row.getInt("version"));
    }

    private static Set<String> strings(@Nullable Array raw) throws SQLException {
        if (raw == null) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object element : (Object[]) raw.getArray()) {
            values.add((String) element);
        }
        return values;
    }
}
