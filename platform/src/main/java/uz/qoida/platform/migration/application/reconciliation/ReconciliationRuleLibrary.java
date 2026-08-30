package uz.qoida.platform.migration.application.reconciliation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.api.ImportPort;

/**
 * Which rules apply to which capability (ADR 0024).
 *
 * <p>Assembled from the import ports rather than listed separately, and that is
 * the point: the count and checksum rules measure exactly what {@link
 * ImportPort#extraction()} pages, filter included. A separately maintained list
 * would drift on the first mapping change, and it would drift in the direction
 * that reconciles — a rule counting a table the importer no longer reads passes
 * for a reason unrelated to the migration.
 *
 * <p>A capability with no rule is refused at reconciliation time rather than
 * silently returning nothing. ADR 0024 gates cutover on evidence, and a
 * capability with nothing to fail would clear every gate.
 */
@Component
public class ReconciliationRuleLibrary {

    /**
     * Which capability each entity family belongs to.
     *
     * <p>Stated here and not on the port, because the capability is an ownership
     * unit and a port is a mapping. The two coincide today and would not have to:
     * ADR 0024 transfers ownership per capability, and one capability's cutover
     * can cover several entity families.
     */
    private static final Map<String, MigrationCapability> CAPABILITY_OF_ENTITY = Map.of(
            "BRAND", MigrationCapability.TENANCY,
            "LOCATION", MigrationCapability.TENANCY,
            "CUSTOMER", MigrationCapability.CUSTOMERS,
            "ORDER", MigrationCapability.ORDERS);

    private final Map<MigrationCapability, List<ReconciliationRule>> byCapability;

    public ReconciliationRuleLibrary(List<ImportPort<?>> ports) {
        Map<MigrationCapability, List<ReconciliationRule>> assembled = new LinkedHashMap<>();

        for (ImportPort<?> port : ports) {
            MigrationCapability capability = CAPABILITY_OF_ENTITY.get(port.entityType());
            if (capability == null) {
                // An import port whose family belongs to no capability could be run
                // and could never be reconciled, which is a migration that finishes
                // with no evidence behind it.
                throw new IllegalStateException(
                        ("Entity type %s has an import port and no capability. Every family that "
                                + "can be imported has to be reconcilable (ADR 0024).")
                                .formatted(port.entityType()));
            }
            assembled.computeIfAbsent(capability, key -> new ArrayList<>())
                    .addAll(AuthoritativeIdRules.forEntity(port.extraction(), capability));
        }

        // The two rules that are not per-entity. Money is measured over orders and
        // ancestry over the rows the migration wrote, and both belong to the
        // capability rather than to a family — which is why they are added once
        // and not inside the loop above.
        if (assembled.containsKey(MigrationCapability.ORDERS)) {
            assembled.get(MigrationCapability.ORDERS)
                    .add(new MoneyTotalsRule(MigrationCapability.ORDERS));
            assembled.get(MigrationCapability.ORDERS)
                    .add(new CrossTenantAncestryRule(MigrationCapability.ORDERS));
        }

        Map<MigrationCapability, List<ReconciliationRule>> frozen = new LinkedHashMap<>();
        assembled.forEach((capability, rules) -> frozen.put(capability, List.copyOf(rules)));
        this.byCapability = Map.copyOf(frozen);
    }

    /** Every rule covering this capability, in the order they run. */
    public List<ReconciliationRule> forCapability(MigrationCapability capability) {
        return byCapability.getOrDefault(capability, List.of());
    }
}
