package uz.horecaos.platform.migration.application.reconciliation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import uz.horecaos.platform.migration.domain.ReconciliationSeverity;

/**
 * The rule library and the results it produces
 * ({@code migration.reconciliation_rules}, {@code migration.reconciliation_results},
 * ADR 0024).
 *
 * <p>One port over two tables, because they are one thought: a result is
 * meaningless without the version of the rule it was measured under, and the
 * declaration exists only to be resolved from results. Splitting them would put a
 * join across two ports on the path a cutover gate takes.
 *
 * <p>Recording is an upsert on {@code (run, rule, dimension)}. ADR 0024 requires
 * runs to be safe to repeat, and a suite retried inside one run must converge on
 * one result per dimension — otherwise the gate counts a single discrepancy
 * twice and an operator resolves the copy nobody reads.
 */
public interface ReconciliationRuleStore {

    /**
     * The version of this rule that is currently declared.
     *
     * <p>Empty means the rule library does not know this code, which is a stop and
     * not a default: a result recorded against an undeclared rule carries a
     * severity nothing can resolve, and the cutover gate reads severity to decide
     * whether to block.
     */
    Optional<Declaration> findCurrent(String ruleCode);

    /**
     * Writes one measured comparison.
     *
     * @return the result id
     */
    UUID record(Result result, Instant now);

    /**
     * A rule as the library declares it.
     *
     * @param toleranceValue exact integer in the measure's own unit — rows for a
     *                       count, minor units for an amount, and for UZS a minor
     *                       unit is a whole som. Zero for every CRITICAL rule,
     *                       which the schema enforces: a rule that blocks cutover
     *                       admits no difference, and accepting a specific one is
     *                       a decision about a result with an approver's name on it
     */
    record Declaration(
            UUID id,
            String ruleCode,
            int ruleVersion,
            String capability,
            String entityType,
            ReconciliationSeverity severity,
            String measureKind,
            String toleranceKind,
            BigInteger toleranceValue,
            String rationale) {

        /** Whether a difference of this size is inside what was approved in advance. */
        public boolean tolerates(BigInteger difference) {
            if (difference == null) {
                // A checksum. Two digests are equal or they are not, and the schema
                // refuses a tolerance against one for that reason.
                return false;
            }
            return difference.abs().compareTo(toleranceValue) <= 0;
        }
    }

    /** One measured comparison, ready for {@code migration.reconciliation_results}. */
    record Result(
            UUID id,
            UUID tenantId,
            UUID runId,
            UUID scopeId,
            String ruleCode,
            int ruleVersion,
            String dimensionKey,
            ReconciliationSeverity severity,
            Measurement measurement) { }
}
