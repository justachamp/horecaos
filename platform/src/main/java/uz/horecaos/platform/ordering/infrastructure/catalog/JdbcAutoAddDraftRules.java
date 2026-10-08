package uz.horecaos.platform.ordering.infrastructure.catalog;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.application.AutoAddDraftRules;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Band;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Kind;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Mode;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Trigger;

/**
 * Reads the authoring tables for the simulator (ADR 0158). Every query carries the tenant and the brand,
 * so a variant or rule of another brand is simply not there.
 */
@Component
public class JdbcAutoAddDraftRules implements AutoAddDraftRules {

    private final JdbcClient jdbc;

    public JdbcAutoAddDraftRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DraftRule> rules(UUID tenantId, UUID brandId) {
        record Row(
                UUID id,
                int version,
                String code,
                Kind kind,
                Mode mode,
                String status,
                @Nullable UUID addVariantId,
                @Nullable BigDecimal quantity,
                @Nullable BigDecimal threshold,
                boolean repeat,
                BigDecimal minimum,
                BigDecimal maximum,
                boolean mandatory,
                Set<String> modes,
                Set<String> channels) {}
        List<Row> rows = jdbc.sql("""
                SELECT id, version, code, kind, mode, status, add_variant_id, quantity, trigger_threshold,
                       repeat_per_multiple, minimum_quantity, maximum_quantity, mandatory,
                       fulfillment_modes, channel_system_types
                FROM catalog.auto_add_rules
                WHERE tenant_id = :t AND brand_id = :b AND status <> 'ARCHIVED'
                ORDER BY sort_order, code
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .query((row, number) -> new Row(
                        row.getObject("id", UUID.class),
                        row.getInt("version"),
                        row.getString("code"),
                        Kind.valueOf(row.getString("kind")),
                        Mode.valueOf(row.getString("mode")),
                        row.getString("status"),
                        row.getObject("add_variant_id", UUID.class),
                        row.getBigDecimal("quantity"),
                        row.getBigDecimal("trigger_threshold"),
                        row.getBoolean("repeat_per_multiple"),
                        row.getBigDecimal("minimum_quantity"),
                        row.getBigDecimal("maximum_quantity"),
                        row.getBoolean("mandatory"),
                        strings(row.getArray("fulfillment_modes")),
                        strings(row.getArray("channel_system_types"))))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
        Map<UUID, List<Trigger>> triggers = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rule_id, variant_id, product_id, category_id FROM catalog.auto_add_rule_triggers
                WHERE tenant_id = :t AND brand_id = :b AND rule_id = ANY(:ids)
                """).param("t", tenantId).param("b", brandId).param("ids", ids).query(row -> {
            triggers.computeIfAbsent(row.getObject("rule_id", UUID.class), id -> new ArrayList<>())
                    .add(new Trigger(
                            row.getObject("variant_id", UUID.class),
                            row.getObject("product_id", UUID.class),
                            row.getObject("category_id", UUID.class)));
        });
        Map<UUID, List<Band>> bands = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rule_id, portions_from, portions_to, add_variant_id, quantity
                FROM catalog.auto_add_rule_bands
                WHERE tenant_id = :t AND brand_id = :b AND rule_id = ANY(:ids) ORDER BY portions_from
                """).param("t", tenantId).param("b", brandId).param("ids", ids).query(row -> {
            bands.computeIfAbsent(row.getObject("rule_id", UUID.class), id -> new ArrayList<>())
                    .add(new Band(
                            row.getBigDecimal("portions_from"),
                            row.getBigDecimal("portions_to"),
                            row.getObject("add_variant_id", UUID.class),
                            row.getBigDecimal("quantity")));
        });
        Map<UUID, Set<UUID>> locations = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rule_id, location_id FROM catalog.auto_add_rule_locations
                WHERE tenant_id = :t AND brand_id = :b AND rule_id = ANY(:ids)
                """).param("t", tenantId).param("b", brandId).param("ids", ids).query(row -> {
            locations
                    .computeIfAbsent(row.getObject("rule_id", UUID.class), id -> new LinkedHashSet<>())
                    .add(row.getObject("location_id", UUID.class));
        });
        return rows.stream()
                .map(row -> new DraftRule(
                        new PublishedAutoAddRule(
                                row.id(),
                                row.version(),
                                row.code(),
                                row.kind(),
                                row.mode(),
                                row.addVariantId(),
                                row.quantity(),
                                row.threshold(),
                                row.repeat(),
                                row.minimum(),
                                row.maximum(),
                                row.mandatory(),
                                row.modes(),
                                row.channels(),
                                locations.getOrDefault(row.id(), Set.of()),
                                triggers.getOrDefault(row.id(), List.of()),
                                bands.getOrDefault(row.id(), List.of()),
                                Map.of()),
                        row.status()))
                .toList();
    }

    @Override
    public Map<UUID, VariantFacts> variantFacts(UUID tenantId, UUID brandId, Collection<UUID> variantIds) {
        Map<UUID, VariantFacts> facts = new LinkedHashMap<>();
        if (variantIds.isEmpty()) {
            return facts;
        }
        jdbc.sql("""
                SELECT v.id AS variant_id, v.product_id, v.status, pa.portions_per_unit,
                       COALESCE(
                           (SELECT array_agg(cp.category_id) FROM catalog.category_products cp
                             WHERE cp.tenant_id = v.tenant_id AND cp.brand_id = v.brand_id
                               AND cp.product_id = v.product_id),
                           '{}') AS categories
                FROM catalog.variants v
                LEFT JOIN catalog.variant_physical_attributes pa
                  ON pa.variant_id = v.id AND pa.tenant_id = v.tenant_id AND pa.brand_id = v.brand_id
                WHERE v.tenant_id = :t AND v.brand_id = :b AND v.id = ANY(:ids)
                """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .query(row -> {
                    facts.put(
                            row.getObject("variant_id", UUID.class),
                            new VariantFacts(
                                    row.getObject("product_id", UUID.class),
                                    uuids(row.getArray("categories")),
                                    row.getBigDecimal("portions_per_unit"),
                                    "ACTIVE".equals(row.getString("status"))));
                });
        return facts;
    }

    private static Set<String> strings(@Nullable Array raw) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        if (raw != null) {
            for (Object element : (Object[]) raw.getArray()) {
                values.add((String) element);
            }
        }
        return values;
    }

    private static Set<UUID> uuids(@Nullable Array raw) {
        Set<UUID> values = new LinkedHashSet<>();
        if (raw != null) {
            try {
                for (Object element : (Object[]) raw.getArray()) {
                    values.add((UUID) element);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not read the categories of a variant", failure);
            }
        }
        return values;
    }
}
