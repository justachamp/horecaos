package uz.horecaos.platform.migration.application.reconciliation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.migration.application.MigrationPreconditionException;
import uz.horecaos.platform.migration.application.MigrationResourceNotFoundException;
import uz.horecaos.platform.migration.application.MigrationRunService;
import uz.horecaos.platform.migration.application.MigrationRunStore.RunRow;
import uz.horecaos.platform.migration.application.MigrationScopeStore;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;

/**
 * Runs the rule library over one scope and records what it found (ADR 0024, step
 * 6).
 *
 * <p>Records everything, including the rules that agreed. ADR 0024 is explicit
 * that "a dashboard summary is not approval evidence" and lists what has to be
 * stored — query and rule versions, watermarks, counts, hashes, sampled
 * discrepancies, resolutions, approvers, timestamps. A suite that stored only its
 * failures would leave an approver unable to tell a rule that passed from a rule
 * that never ran, which is the difference between evidence and a green light.
 *
 * <p>Severity comes from the library and not from the rule object. A rule
 * implementation states what it believes it is; the declaration at that version is
 * what a past approval was read under, and the two are checked against each other
 * so a rule downgraded in code cannot quietly stop blocking.
 *
 * <p>Nothing here approves anything. An open CRITICAL result blocks its scope
 * through {@code MigrationReconciliationStore}, and clearing it is a separate
 * decision with a name attached — which is why this service has no method that
 * takes one.
 */
@Service
// Present only where a legacy source is configured. Both of this bean's
// collaborators read the source, so a platform with no migration running would
// otherwise fail to start for want of a connection to a system it is not
// migrating. Conditional on the property rather than on the bean, because
// @ConditionalOnBean over component-scanned beans depends on definition order.
@ConditionalOnProperty(
        prefix = "horecaos.migration.legacy", name = "enabled", havingValue = "true")
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final MigrationRunService runService;
    private final MigrationScopeStore scopes;
    private final ReconciliationRuleStore rules;
    private final ReconciliationRuleLibrary library;
    private final LegacyQuery legacy;
    private final TargetQuery target;
    private final Clock clock;

    public ReconciliationService(
            MigrationRunService runService,
            MigrationScopeStore scopes,
            ReconciliationRuleStore rules,
            ReconciliationRuleLibrary library,
            LegacyQuery legacy,
            TargetQuery target,
            Clock clock) {
        this.runService = runService;
        this.scopes = scopes;
        this.rules = rules;
        this.library = library;
        this.legacy = legacy;
        this.target = target;
        this.clock = clock;
    }

    /**
     * Evaluates every rule that applies to this scope's capability.
     *
     * <p>One transaction. A suite that committed rule by rule could be killed
     * halfway and leave a scope looking reconciled on the three rules that ran,
     * with the money rule simply absent — and absent reads as "not measured" only
     * to somebody who knows the suite's contents.
     *
     * @return what was measured, in the order the rules ran
     */
    @Transactional
    public List<Finding> reconcile(UUID tenantId, UUID runId) {
        RunRow run = runService.get(tenantId, runId);
        ScopeRow scope = scopes.findById(tenantId, run.scopeId())
                .orElseThrow(() -> new MigrationResourceNotFoundException(
                        "Scope %s does not exist".formatted(run.scopeId())));

        List<ReconciliationRule> applicable = library.forCapability(scope.capability());
        if (applicable.isEmpty()) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.NO_RECONCILIATION_RULES,
                    ("No reconciliation rule covers %s. ADR 0024 gates cutover on evidence, and a "
                            + "capability with no rules would clear every gate by having nothing to "
                            + "fail.").formatted(scope.capability()));
        }

        List<Finding> findings = new ArrayList<>();
        for (ReconciliationRule rule : applicable) {
            ReconciliationRuleStore.Declaration declared = rules.findCurrent(rule.ruleCode())
                    .orElseThrow(() -> new MigrationPreconditionException(
                            MigrationPreconditionException.NO_RECONCILIATION_RULES,
                            ("Rule %s is implemented and not declared. A result carrying a severity "
                                    + "nothing can resolve is not evidence.").formatted(rule.ruleCode())));

            if (declared.ruleVersion() != rule.ruleVersion()) {
                // The same refusal the transformation registry makes, for the same
                // reason: a finding recorded under a version whose meaning has
                // changed cannot be re-derived, and ADR 0024 requires exactly that
                // it can be.
                throw new MigrationPreconditionException(
                        MigrationPreconditionException.RECONCILIATION_RULE_VERSION_DRIFT,
                        ("Rule %s is declared at version %d and implemented at version %d. "
                                + "Declare the new version before measuring under it.")
                                .formatted(rule.ruleCode(), declared.ruleVersion(), rule.ruleVersion()));
            }

            ReconciliationRule.RuleContext context = new ReconciliationRule.RuleContext(
                    tenantId, scope.id(), scope.brandId(), scope.locationId(),
                    rule.entityType(), legacy, target);

            for (Measurement measurement : rule.evaluate(context)) {
                UUID resultId = rules.record(new ReconciliationRuleStore.Result(
                        UUID.randomUUID(), tenantId, runId, scope.id(),
                        rule.ruleCode(), declared.ruleVersion(), measurement.dimensionKey(),
                        declared.severity(), measurement), clock.instant());

                boolean within = measurement.agrees() || declared.tolerates(measurement.difference());
                findings.add(new Finding(resultId, rule.ruleCode(), declared.ruleVersion(),
                        measurement.dimensionKey(), declared.severity(), measurement, within));

                if (!within && declared.severity() == ReconciliationSeverity.CRITICAL) {
                    // The figures themselves are not logged. They are on the result
                    // row, which is where an approver reads them; a log line carrying
                    // a tenant's money totals is the kind of thing ADR 0029 exists to
                    // keep out of wherever logs are shipped.
                    log.warn("Reconciliation rule {} v{} is open on scope {} dimension {}",
                            rule.ruleCode(), declared.ruleVersion(), scope.id(),
                            measurement.dimensionKey());
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * One recorded comparison, as the caller reads it back.
     *
     * @param within whether the two sides agreed, or differed by no more than the
     *               tolerance this version declared. False on a CRITICAL rule is
     *               what blocks the scope
     */
    public record Finding(
            UUID resultId,
            String ruleCode,
            int ruleVersion,
            String dimensionKey,
            ReconciliationSeverity severity,
            Measurement measurement,
            boolean within) { }
}
