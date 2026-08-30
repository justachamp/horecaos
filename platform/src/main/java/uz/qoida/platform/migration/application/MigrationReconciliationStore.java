package uz.qoida.platform.migration.application;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import uz.qoida.platform.migration.domain.ReconciliationSeverity;

/**
 * The cutover gate's read of {@code migration.reconciliation_results}.
 *
 * <p>Read-only, and narrow on purpose. Recording a result belongs to the
 * reconciliation run that produced it; approving one is a decision with its own
 * four-eyes rules. What the transition engine needs is the single question
 * {@code ix_reconciliation_blocking} exists to answer — does this scope have an
 * unresolved critical difference — and giving this port a write method would put
 * the ability to clear a blocking result behind the same door as the ability to
 * ask about it.
 */
public interface MigrationReconciliationStore {

    /**
     * Whether an open {@link ReconciliationSeverity#CRITICAL} result stands
     * against this scope.
     *
     * <p>One probe of {@code ix_reconciliation_blocking}, which is why {@code
     * scope_id} is denormalized onto the results table: every transition attempt
     * asks this, and it must not be a scan joined to runs.
     *
     * <p>{@code APPROVED} clears the gate as surely as {@code RESOLVED} does. ADR
     * 0024 defines both zero-tolerance and approved-tolerance rules, and an
     * accepted difference that still blocked would leave operators with no way
     * forward except editing the evidence.
     */
    boolean hasOpenCritical(UUID tenantId, UUID scopeId);

    /**
     * The first few blocking results, for the refusal message.
     *
     * <p>Only ever read after {@link #hasOpenCritical} has already said no. An
     * operator told that reconciliation is outstanding and not which rule goes to
     * the results table to guess, and the guess is what a dashboard summary is —
     * which ADR 0024 is explicit is not evidence.
     */
    List<BlockingResult> openCriticalResults(UUID tenantId, UUID scopeId, int limit);

    /**
     * One versioned rule evaluated over one dimension, as the gate reports it.
     *
     * @param dimensionKey the slice the rule was evaluated over — a currency, a
     *                     provider, an order status — and the empty string, never
     *                     null, for a rule that has no dimension
     * @param measureKind  {@code COUNT}, {@code AMOUNT} or {@code CHECKSUM}, and
     *                     what decides which of the remaining fields are present
     * @param expectedValue exact integers: row counts for {@code COUNT}, minor
     *                     units of {@code currency} for {@code AMOUNT}, and null
     *                     for {@code CHECKSUM}. {@link BigInteger} rather than a
     *                     double, because money that rounds is money that
     *                     reconciles by accident, and rather than a long because
     *                     the column is {@code numeric(38,0)}
     * @param currency     present only for {@code AMOUNT}; an amount without one
     *                     is a number nobody can compare
     */
    record BlockingResult(
            UUID id,
            String ruleCode,
            int ruleVersion,
            String dimensionKey,
            String measureKind,
            BigInteger expectedValue,
            BigInteger actualValue,
            BigInteger differenceValue,
            String currency) {

        /** How the refusal names this difference to the operator who hit it. */
        public String describe() {
            String rule = "%s v%d".formatted(ruleCode, ruleVersion);
            String slice = dimensionKey == null || dimensionKey.isEmpty()
                    ? rule : "%s [%s]".formatted(rule, dimensionKey);
            if (differenceValue == null) {
                return slice + " checksum mismatch";
            }
            return currency == null
                    ? "%s differs by %s".formatted(slice, differenceValue)
                    : "%s differs by %s %s (minor units)".formatted(slice, differenceValue, currency);
        }
    }
}
