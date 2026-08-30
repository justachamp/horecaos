package uz.horecaos.platform.migration.application.reconciliation;

import java.util.List;
import java.util.UUID;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;

/**
 * One versioned rule, evaluated over one scope (ADR 0024).
 *
 * <p>A rule reads both databases and returns what it measured; it does not decide
 * whether the difference is acceptable and it does not write. Severity and
 * tolerance come from {@code migration.reconciliation_rules} at the version this
 * rule declares, so loosening a rule cannot retroactively soften a finding, and
 * accepting a specific difference stays a decision with a name on it rather than
 * something a rule granted in advance.
 *
 * <p>A rule that measures nothing returns an empty list, and that is a real
 * outcome — a scope with no orders yet has no money to compare. It is
 * deliberately different from measuring zero on both sides, which is a comparison
 * that happened and agreed.
 */
public interface ReconciliationRule {

    /** Upper-case code, resolving against {@code migration.reconciliation_rules}. */
    String ruleCode();

    /**
     * The version this implementation is.
     *
     * <p>Checked against the registered current version before the suite runs, for
     * the reason the transformation registry gives: a result recorded under a
     * version whose meaning has changed is evidence of nothing.
     */
    int ruleVersion();

    MigrationCapability capability();

    /** The entity family, or the empty string for a rule spanning the capability. */
    String entityType();

    ReconciliationSeverity severity();

    Measurement.MeasureKind measureKind();

    /** Both sides, one entry per dimension. */
    List<Measurement> evaluate(RuleContext context);

    /**
     * What a rule is allowed to read.
     *
     * <p>Two query ports and the scope, and nothing else. A rule holding a service
     * could compare the target against something other than what the target
     * stores, which is how a reconciliation ends up agreeing with the bug it was
     * meant to find.
     *
     * @param legacy the read-only legacy source
     * @param target the platform's own database
     */
    record RuleContext(
            UUID tenantId,
            UUID scopeId,
            UUID brandId,
            UUID locationId,
            String entityType,
            LegacyQuery legacy,
            TargetQuery target) {}
}
