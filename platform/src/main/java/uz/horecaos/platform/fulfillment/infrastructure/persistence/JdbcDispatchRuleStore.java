package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads the dispatch-rules editor and its publish-time validation need (ADR 0142).
 *
 * <p>What a rule may <em>name</em> -- a delivery installation, a delivery zone, a sales channel, a
 * branch -- lives in three other modules' tables. These are read-only queries of this module's own,
 * the shape {@link JdbcDispatchBranchStore} already has for {@code tenant.locations}: fulfilment
 * cannot import {@code integration.api.provider} (that edge would close a cycle with the booking
 * adapter), and what it needs is a list of ids and operator-facing labels, not a binding.
 *
 * <p>Every query is tenant-scoped in its predicate. An id a caller supplies is never proof of
 * ownership, so a rule naming another tenant's installation finds it absent here and is refused.
 */
@Repository
public class JdbcDispatchRuleStore {

    private final JdbcClient jdbc;

    public JdbcDispatchRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * This tenant's delivery installations that have not been retired. A suspended or draft one is
     * still nameable: the rule outlives the suspension, and the ladder skips it at runtime with the
     * skip recorded.
     */
    public List<InstallationRow> deliveryInstallations(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, provider_type, display_name, status
                FROM integration.installations
                WHERE tenant_id = :tenantId
                  AND provider_category = 'DELIVERY'
                  AND status <> 'RETIRED'
                ORDER BY display_name, id
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new InstallationRow(
                        row.getObject("id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("display_name"),
                        row.getString("status")))
                .list();
    }

    /**
     * This tenant's delivery-role zones, archived ones included -- an order priced before the archive still
     * names one.
     *
     * <p>A zone belongs to one brand, so a document at brand or branch scope reaches only that brand's
     * zones ({@code brandId} set); the company-wide document ({@code brandId} null) reaches them all. A
     * brand's manager is never shown, or allowed to name, a sibling brand's zone (ADR 0025).
     */
    public List<ZoneRow> deliveryZones(UUID tenantId, @Nullable UUID brandId) {
        return jdbc.sql("""
                SELECT id, brand_id, code, display_name_en, display_name_ru, display_name_uz, status
                FROM fulfillment.service_zones
                WHERE tenant_id = :tenantId AND zone_role = 'DELIVERY'
                  AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                ORDER BY code, id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new ZoneRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("code"),
                        row.getString("display_name_en"),
                        row.getString("display_name_ru"),
                        row.getString("display_name_uz"),
                        row.getString("status")))
                .list();
    }

    public List<ChannelRow> channels(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, code, system_type, display_name, status
                FROM tenant.sales_channels
                WHERE tenant_id = :tenantId
                ORDER BY display_name, id
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new ChannelRow(
                        row.getObject("id", UUID.class),
                        row.getString("code"),
                        row.getString("system_type"),
                        row.getString("display_name"),
                        row.getString("status")))
                .list();
    }

    /**
     * The branches a document at this scope can name: every location of the tenant at tenant scope,
     * of the brand at brand scope, and just the one at location scope.
     */
    public List<LocationRow> locationsInScope(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        return jdbc.sql("""
                SELECT id, brand_id, display_name
                FROM tenant.locations
                WHERE tenant_id = :tenantId
                  AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                  AND (CAST(:locationId AS uuid) IS NULL OR id = CAST(:locationId AS uuid))
                ORDER BY display_name, id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((row, number) -> new LocationRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("display_name")))
                .list();
    }

    /**
     * How many plans each rule produced since an instant, newest rules and the default alike.
     *
     * <p>The key is the rule id the plan recorded, null for the default (built-in or the document's
     * own). Counted from {@code delivery_plans}, never from a metric: a rule id is operator-typed text
     * and has no business being a metric label.
     */
    public Map<String, Long> plansPerRule(
            UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId, Instant since) {
        List<RuleCount> rows = jdbc.sql("""
                SELECT dispatch_rule_id, count(*) AS plans
                FROM fulfillment.delivery_plans
                WHERE tenant_id = :tenantId
                  AND created_at >= :since
                  AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                  AND (CAST(:locationId AS uuid) IS NULL OR location_id = CAST(:locationId AS uuid))
                GROUP BY dispatch_rule_id
                ORDER BY plans DESC, dispatch_rule_id NULLS LAST
                """)
                .param("tenantId", tenantId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((row, number) -> new RuleCount(row.getString("dispatch_rule_id"), row.getLong("plans")))
                .list();
        Map<String, Long> counts = new LinkedHashMap<>();
        // A LinkedHashMap permits the null key the default's row carries.
        rows.forEach(count -> counts.put(count.ruleId(), count.plans()));
        return counts;
    }

    private record RuleCount(@Nullable String ruleId, long plans) {}

    /**
     * The newest plans of a branch, for the simulator's "re-read a recent order" picker. The public order
     * number is the only identifier shown: it is already shouted across a counter and carries nobody.
     */
    public List<RecentPlanRow> recentPlans(
            UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId, int limit) {
        return jdbc.sql("""
                SELECT p.id, p.location_id, p.status, p.sourcing_mode, p.dispatch_rule_id, p.created_at,
                       o.public_order_number
                FROM fulfillment.delivery_plans p
                JOIN ordering.orders o ON o.id = p.order_id AND o.tenant_id = p.tenant_id
                WHERE p.tenant_id = :tenantId
                  AND (CAST(:brandId AS uuid) IS NULL OR p.brand_id = CAST(:brandId AS uuid))
                  AND (CAST(:locationId AS uuid) IS NULL OR p.location_id = CAST(:locationId AS uuid))
                ORDER BY p.created_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("limit", limit)
                .query((row, number) -> new RecentPlanRow(
                        row.getObject("id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getString("status"),
                        row.getString("sourcing_mode"),
                        row.getString("dispatch_rule_id"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public record InstallationRow(UUID id, String providerType, String displayName, String status) {}

    public record ZoneRow(
            UUID id, UUID brandId, String code, String nameEn, String nameRu, String nameUz, String status) {}

    public record ChannelRow(UUID id, String code, String systemType, String displayName, String status) {}

    public record LocationRow(UUID id, UUID brandId, String displayName) {}

    public record RecentPlanRow(
            UUID planId,
            UUID locationId,
            String orderReference,
            String status,
            String sourcingMode,
            @Nullable String ruleId,
            Instant createdAt) {}
}
