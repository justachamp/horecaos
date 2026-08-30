package uz.qoida.platform.migration.application.reconciliation;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.domain.ReconciliationSeverity;

/**
 * "Zero cross-tenant or invalid ancestry references" (ADR 0024's first
 * zero-tolerance rule).
 *
 * <p>The one rule whose expected value is a constant. Every other rule compares
 * two systems; this one asserts a property of the target that no legacy figure
 * can excuse, so a non-zero actual blocks whatever the source says.
 *
 * <p>Three separate counts, each its own dimension rather than a sum, because
 * they have different causes and different fixes. A row under the wrong tenant is
 * a scope misconfiguration; a row whose brand belongs to another tenant is a
 * crosswalk that resolved across a boundary; a row with no brand at all is the
 * legacy's own gap — {@code orders.vendor_id} is nullable, and an order with no
 * branch has no brand and no tenant. ADR 0024 quarantines that row rather than
 * assigning it to a convenient parent, so seeing one imported means the
 * transformation defaulted something it should have refused.
 *
 * <p>The counts are joined through the crosswalk, so they measure what this
 * migration wrote and not what the target happens to contain. A target row with
 * bad ancestry that this migration did not create is a different incident with a
 * different owner.
 */
public final class CrossTenantAncestryRule implements ReconciliationRule {

    /** An imported row filed under a tenant that is not the scope's. */
    private static final String FOREIGN_TENANT = """
            SELECT count(*)
            FROM migration.entity_mappings m
            JOIN ordering.orders o ON o.id = m.target_id
            WHERE m.scope_id = :scopeId AND m.entity_type = :entityType
              AND m.mapping_status = 'MAPPED'
              AND o.tenant_id <> :tenantId
            """;

    /**
     * An imported row whose brand is not a brand of its own tenant.
     *
     * <p>A LEFT JOIN and an IS NULL rather than a NOT EXISTS on the id alone: the
     * join is on the pair, so a brand that exists under another tenant fails it
     * exactly as a brand that does not exist at all does. Those are the two ways
     * ancestry goes wrong and neither should need a second query to find.
     */
    private static final String FOREIGN_BRAND = """
            SELECT count(*)
            FROM migration.entity_mappings m
            JOIN ordering.orders o ON o.id = m.target_id
            LEFT JOIN tenant.brands b ON b.id = o.brand_id AND b.tenant_id = o.tenant_id
            WHERE m.scope_id = :scopeId AND m.entity_type = :entityType
              AND m.mapping_status = 'MAPPED'
              AND o.tenant_id = :tenantId
              AND b.id IS NULL
            """;

    /**
     * An imported row whose location is not a location of its own brand.
     *
     * <p>The level below the brand, and the one a brand-only check would miss: a
     * location correctly under the tenant but under a different brand puts one
     * restaurant's order in another's reporting.
     */
    private static final String FOREIGN_LOCATION = """
            SELECT count(*)
            FROM migration.entity_mappings m
            JOIN ordering.orders o ON o.id = m.target_id
            LEFT JOIN tenant.locations l
                   ON l.id = o.location_id AND l.tenant_id = o.tenant_id AND l.brand_id = o.brand_id
            WHERE m.scope_id = :scopeId AND m.entity_type = :entityType
              AND m.mapping_status = 'MAPPED'
              AND o.tenant_id = :tenantId
              AND l.id IS NULL
            """;

    private final MigrationCapability capability;

    public CrossTenantAncestryRule(MigrationCapability capability) {
        this.capability = capability;
    }

    @Override
    public String ruleCode() {
        return "CROSS_TENANT_ANCESTRY";
    }

    @Override
    public int ruleVersion() {
        return 1;
    }

    @Override
    public MigrationCapability capability() {
        return capability;
    }

    @Override
    public String entityType() {
        return "ORDER";
    }

    @Override
    public ReconciliationSeverity severity() {
        return ReconciliationSeverity.CRITICAL;
    }

    @Override
    public Measurement.MeasureKind measureKind() {
        return Measurement.MeasureKind.COUNT;
    }

    @Override
    public List<Measurement> evaluate(RuleContext context) {
        Map<String, Object> parameters = Map.of(
                "tenantId", context.tenantId(),
                "scopeId", context.scopeId(),
                "entityType", entityType());

        return List.of(
                zeroExpected("FOREIGN_TENANT", context, FOREIGN_TENANT, parameters),
                zeroExpected("FOREIGN_BRAND", context, FOREIGN_BRAND, parameters),
                zeroExpected("FOREIGN_LOCATION", context, FOREIGN_LOCATION, parameters));
    }

    private static Measurement zeroExpected(String dimension, RuleContext context, String sql,
            Map<String, Object> parameters) {
        BigInteger actual = context.target().exactInteger(sql, parameters).orElse(BigInteger.ZERO);
        return new Measurement(dimension, Measurement.MeasureKind.COUNT,
                BigInteger.ZERO, actual, null, null, null, null);
    }
}
