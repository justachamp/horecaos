package uz.horecaos.platform.migration.infrastructure.persistence;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.exactIntegerOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.migration.application.MigrationReconciliationStore;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;
import uz.horecaos.platform.migration.domain.ReconciliationStatus;

/**
 * Reconciliation persistence (ADR 0024).
 *
 * <p>One rule, one version of that rule, one dimension, both sides of the
 * comparison, and a reference to the sampled discrepancies. Enough to re-derive a
 * finding months later without re-running anything, because ADR 0024 is explicit
 * that a dashboard summary is not approval evidence.
 *
 * <p>{@link #hasOpenCritical} is the query the cutover gate asks on every
 * transition attempt, and everything else here is arranged around keeping it a
 * single index probe.
 *
 * <p>The port this implements is read-only on purpose: recording a result belongs
 * to the reconciliation run that produced it, and approving one is a decision with
 * its own four-eyes rules, so neither is reachable through the interface the
 * cutover gate holds. The write statements below therefore sit outside the port
 * rather than on it — they are the reconciliation runner's, not the gate's.
 */
@Repository
public class JdbcReconciliationStore implements MigrationReconciliationStore {

    private static final String SELECT_RESULT = """
            SELECT id, tenant_id, run_id, scope_id, rule_code, rule_version, dimension_key,
                   severity, measure_kind, expected_value, actual_value, difference_value,
                   currency, expected_checksum, actual_checksum, sample_reference, status,
                   approved_by, approved_at, resolved_at, created_at, updated_at
            FROM migration.reconciliation_results""";

    private final JdbcClient jdbc;

    public JdbcReconciliationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one evaluation of one rule.
     *
     * <p>The difference is computed in the statement rather than passed in.
     * {@code ck_reconciliation_difference} asserts that the stored difference
     * cannot disagree with the two sides it came from, and a caller that
     * subtracted in Java would be offering the database a third opinion for it to
     * check. Deriving it here means there is only ever one.
     *
     * <p>The upsert covers a rule retried inside one run: {@code
     * uq_reconciliation_result} exists so that one discrepancy is not counted
     * twice, and the second evaluation is the same evaluation, so it overwrites.
     * It stops at a result that has already been settled, though — re-evaluating
     * a rule must not erase the approval or the correction that answered it.
     *
     * @return the result id, or empty when the finding has already been approved
     *         or resolved and a fresh evaluation belongs in a fresh run
     */
    public Optional<UUID> record(ReconciliationResult result) {
        Map<String, Object> measure = result.measure().params();

        return jdbc.sql("""
                INSERT INTO migration.reconciliation_results (
                    id, tenant_id, run_id, scope_id, rule_code, rule_version, dimension_key,
                    severity, measure_kind, expected_value, actual_value, difference_value,
                    currency, expected_checksum, actual_checksum, sample_reference, status,
                    created_at, updated_at)
                VALUES (
                    :id, :tenantId, :runId, :scopeId, :ruleCode, :ruleVersion, :dimensionKey,
                    :severity, :measureKind,
                    CAST(:expectedValue AS numeric), CAST(:actualValue AS numeric),
                    CAST(:actualValue AS numeric) - CAST(:expectedValue AS numeric),
                    :currency, :expectedChecksum, :actualChecksum, :sampleReference, 'OPEN',
                    :now, :now)
                ON CONFLICT ON CONSTRAINT uq_reconciliation_result DO UPDATE
                SET rule_version = EXCLUDED.rule_version,
                    severity = EXCLUDED.severity,
                    measure_kind = EXCLUDED.measure_kind,
                    expected_value = EXCLUDED.expected_value,
                    actual_value = EXCLUDED.actual_value,
                    difference_value = EXCLUDED.difference_value,
                    currency = EXCLUDED.currency,
                    expected_checksum = EXCLUDED.expected_checksum,
                    actual_checksum = EXCLUDED.actual_checksum,
                    sample_reference = EXCLUDED.sample_reference,
                    updated_at = EXCLUDED.updated_at
                WHERE migration.reconciliation_results.tenant_id = :tenantId
                  AND migration.reconciliation_results.status = 'OPEN'
                RETURNING id
                """)
                .param("id", result.resultId())
                .param("tenantId", result.tenantId())
                .param("runId", result.runId())
                .param("scopeId", result.scopeId())
                .param("ruleCode", result.ruleCode())
                .param("ruleVersion", result.ruleVersion())
                .param("dimensionKey", result.dimensionKey())
                .param("severity", result.severity().name())
                .params(measure)
                .param("sampleReference", result.sampleReference())
                .param("now", utc(result.evaluatedAt()))
                .query(UUID.class)
                .optional();
    }

    /**
     * Whether this scope has an unresolved critical difference.
     *
     * <p>Asked on every cutover attempt, and answered by exactly one probe of
     * {@code ix_reconciliation_blocking}, whose predicate is this predicate. The
     * scope is denormalized onto the result for this query alone: joined back to
     * the run it belongs to, the gate would scan a tenant's whole reconciliation
     * history to decide whether one scope may move.
     *
     * <p>APPROVED clears the gate as surely as RESOLVED does. ADR 0024 defines
     * both zero-tolerance and approved-tolerance rules, and an accepted difference
     * that still blocked would leave an operator with no way forward except
     * editing the evidence.
     */
    @Override
    public boolean hasOpenCritical(UUID tenantId, UUID scopeId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM migration.reconciliation_results
                    WHERE tenant_id = :tenantId AND scope_id = :scopeId
                      AND severity = 'CRITICAL' AND status = 'OPEN')
                """)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .query(Boolean.class)
                .single();
    }

    /**
     * The differences that are blocking this scope, for the refusal to name them.
     *
     * <p>The companion to {@link #hasOpenCritical}: the gate asks the cheap
     * question, and the Problem Details body that explains the refusal asks this
     * one. Bounded by the caller, because a refusal message names the first few
     * differences to send an operator to the right rule — a scope with a thousand
     * open critical findings has a mapping problem, and listing all of them into
     * an error body helps nobody read it.
     */
    @Override
    public List<BlockingResult> openCriticalResults(UUID tenantId, UUID scopeId, int limit) {
        return jdbc.sql("""
                SELECT id, rule_code, rule_version, dimension_key, measure_kind,
                       expected_value, actual_value, difference_value, currency
                FROM migration.reconciliation_results
                WHERE tenant_id = :tenantId AND scope_id = :scopeId
                  AND severity = 'CRITICAL' AND status = 'OPEN'
                ORDER BY created_at, id
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .param("limit", limit)
                .query((row, number) -> new BlockingResult(
                        row.getObject("id", UUID.class),
                        row.getString("rule_code"),
                        row.getInt("rule_version"),
                        row.getString("dimension_key"),
                        row.getString("measure_kind"),
                        exactIntegerOrNull(row, "expected_value"),
                        exactIntegerOrNull(row, "actual_value"),
                        exactIntegerOrNull(row, "difference_value"),
                        row.getString("currency")))
                .list();
    }

    public Optional<ReconciliationResultRow> find(UUID tenantId, UUID resultId) {
        return jdbc.sql(SELECT_RESULT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", resultId)
                .query(JdbcReconciliationStore::mapResult)
                .optional();
    }

    /**
     * Accepts a difference as tolerable, naming who accepted it.
     *
     * @return whether this caller settled the finding
     */
    public boolean approve(UUID tenantId, UUID resultId, String approvedBy, Instant now) {
        return jdbc.sql("""
                UPDATE migration.reconciliation_results
                SET status = 'APPROVED',
                    approved_by = :approvedBy,
                    approved_at = :now,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN'
                """)
                        .param("tenantId", tenantId)
                        .param("id", resultId)
                        .param("approvedBy", approvedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Records that a difference was corrected rather than accepted.
     *
     * <p>Only from OPEN, and that is the schema's decision rather than a
     * conservative choice here: {@code ck_reconciliation_approval} ties an
     * approver to the APPROVED status alone, so moving an approved finding to
     * RESOLVED would have to clear the approver and erase the fact that somebody
     * agreed to live with the difference. "We corrected it" and "we agreed to live
     * with it" are different answers to an auditor, and the schema keeps them from
     * overwriting one another.
     *
     * @return whether this caller settled the finding
     */
    public boolean resolve(UUID tenantId, UUID resultId, Instant now) {
        return jdbc.sql("""
                UPDATE migration.reconciliation_results
                SET status = 'RESOLVED',
                    resolved_at = :now,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN'
                """)
                        .param("tenantId", tenantId)
                        .param("id", resultId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Everything one reconciliation run found, most severe first. */
    public List<ReconciliationResultRow> listForRun(UUID tenantId, UUID runId, MigrationPageCursor after, int limit) {
        return jdbc.sql(SELECT_RESULT + """
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND (CAST(:afterId AS uuid) IS NULL
                        OR (created_at, id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY created_at, id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .params(MigrationPageCursor.params(after))
                .param("limit", limit)
                .query(JdbcReconciliationStore::mapResult)
                .list();
    }

    private static ReconciliationResultRow mapResult(ResultSet row, int number) throws SQLException {
        return new ReconciliationResultRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("run_id", UUID.class),
                row.getObject("scope_id", UUID.class),
                row.getString("rule_code"),
                row.getInt("rule_version"),
                row.getString("dimension_key"),
                ReconciliationSeverity.valueOf(row.getString("severity")),
                mapMeasure(row),
                exactIntegerOrNull(row, "difference_value"),
                row.getString("sample_reference"),
                ReconciliationStatus.valueOf(row.getString("status")),
                row.getString("approved_by"),
                instantOrNull(row, "approved_at"),
                instantOrNull(row, "resolved_at"),
                instantOrNull(row, "created_at"),
                instantOrNull(row, "updated_at"));
    }

    /**
     * Rebuilds the comparison from the columns its kind uses.
     *
     * <p>The kind decides which columns are present, so it decides which factory
     * rebuilds the measure. Reading all six columns into one flat shape instead
     * would put a checksum rule's absent values next to a count rule's real ones
     * and leave every reader to work out which of them meant anything.
     */
    private static ReconciliationMeasure mapMeasure(ResultSet row) throws SQLException {
        String kind = row.getString("measure_kind");
        return switch (kind) {
            case "COUNT" ->
                ReconciliationMeasure.count(
                        exactIntegerOrNull(row, "expected_value"), exactIntegerOrNull(row, "actual_value"));
            case "AMOUNT" ->
                ReconciliationMeasure.amount(
                        exactIntegerOrNull(row, "expected_value"),
                        exactIntegerOrNull(row, "actual_value"),
                        Currency.getInstance(row.getString("currency")));
            case "CHECKSUM" ->
                ReconciliationMeasure.checksum(row.getString("expected_checksum"), row.getString("actual_checksum"));
            default -> throw new IllegalStateException("Unknown reconciliation measure kind " + kind);
        };
    }

    /**
     * What a rule compared, and in which of the three ways it can compare.
     *
     * <p>Typed rather than stringly, and constructed only through the three
     * factories, because {@code ck_reconciliation_measure} states exactly which
     * columns each kind uses and which must be absent. A flat six-field
     * constructor would let a caller assemble a money difference without its
     * currency and discover the fact from a constraint violation.
     *
     * <p>Amounts are exact integers in minor units with a currency beside them,
     * never a floating point number: money that rounds is money that reconciles by
     * accident. {@link BigInteger} rather than {@code long} because the column is
     * {@code numeric(38, 0)} and a platform-wide total in minor units has more
     * headroom than anyone wants to discover during a cutover window.
     */
    public record ReconciliationMeasure(
            String kind,
            @Nullable BigInteger expected,
            @Nullable BigInteger actual,
            @Nullable Currency currency,
            @Nullable String expectedChecksum,
            @Nullable String actualChecksum) {

        public static ReconciliationMeasure count(@Nullable BigInteger expected, @Nullable BigInteger actual) {
            Objects.requireNonNull(expected, "A count rule compares an expected value");
            Objects.requireNonNull(actual, "A count rule compares an actual value");
            return new ReconciliationMeasure("COUNT", expected, actual, null, null, null);
        }

        public static ReconciliationMeasure amount(
                @Nullable BigInteger expectedMinor, @Nullable BigInteger actualMinor, Currency currency) {
            Objects.requireNonNull(expectedMinor, "An amount rule compares an expected value");
            Objects.requireNonNull(actualMinor, "An amount rule compares an actual value");
            Objects.requireNonNull(currency, "An amount is meaningless without its currency");
            return new ReconciliationMeasure("AMOUNT", expectedMinor, actualMinor, currency, null, null);
        }

        public static ReconciliationMeasure checksum(String expected, String actual) {
            Objects.requireNonNull(expected, "A checksum rule compares an expected digest");
            Objects.requireNonNull(actual, "A checksum rule compares an actual digest");
            return new ReconciliationMeasure("CHECKSUM", null, null, null, expected, actual);
        }

        /**
         * Whether the two sides agreed, whichever way this rule compares them.
         *
         * <p>Safe without a null check here: {@code kind} decides which pair is
         * present, and the three factories above never construct an instance
         * missing the pair its own kind uses.
         */
        public boolean matched() {
            return "CHECKSUM".equals(kind)
                    ? Objects.requireNonNull(expectedChecksum).equals(actualChecksum)
                    : Objects.requireNonNull(expected).equals(actual);
        }

        // A HashMap: whichever kind this is, four of the six columns are null by
        // construction, and Map.of would refuse every one of them. The two values
        // go down as BigDecimal because that is the exact type the driver binds to
        // numeric; a BigInteger has no SQL type it can infer.
        Map<String, Object> params() {
            Map<String, Object> params = new HashMap<>();
            params.put("measureKind", kind);
            params.put("expectedValue", expected == null ? null : new BigDecimal(expected));
            params.put("actualValue", actual == null ? null : new BigDecimal(actual));
            params.put("currency", currency == null ? null : currency.getCurrencyCode());
            params.put("expectedChecksum", expectedChecksum);
            params.put("actualChecksum", actualChecksum);
            return params;
        }
    }

    /**
     * One measured comparison, ready to be written.
     *
     * @param scopeId     copied from the run so the cutover gate can ask its
     *                    question without a join. The composite foreign key keeps
     *                    the copy honest: a result cannot name a scope its own run
     *                    does not belong to
     * @param ruleVersion recorded alongside the finding, so a rule that was
     *                    loosened afterwards cannot make a past approval look
     *                    stricter than it was
     * @param dimensionKey the slice the rule was evaluated over, or the empty
     *                    string for an undimensioned rule — a sentinel and never
     *                    null, because null would not compare equal and the
     *                    deduplicating key would stop deduplicating
     */
    public record ReconciliationResult(
            UUID resultId,
            UUID tenantId,
            UUID runId,
            UUID scopeId,
            String ruleCode,
            int ruleVersion,
            String dimensionKey,
            ReconciliationSeverity severity,
            ReconciliationMeasure measure,
            @Nullable String sampleReference,
            Instant evaluatedAt) {}

    /**
     * One measured comparison as stored, with its settlement if it has one.
     *
     * @param difference the stored difference, read back rather than recomputed,
     *                   because it is the figure the approval was given against.
     *                   Null for a checksum rule, which has no numeric sides
     */
    public record ReconciliationResultRow(
            UUID resultId,
            UUID tenantId,
            UUID runId,
            UUID scopeId,
            String ruleCode,
            int ruleVersion,
            String dimensionKey,
            ReconciliationSeverity severity,
            ReconciliationMeasure measure,
            @Nullable BigInteger difference,
            @Nullable String sampleReference,
            ReconciliationStatus status,
            @Nullable String approvedBy,
            @Nullable Instant approvedAt,
            @Nullable Instant resolvedAt,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {}
}
