package uz.horecaos.platform.migration.infrastructure.persistence;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.migration.application.reconciliation.Measurement;
import uz.horecaos.platform.migration.application.reconciliation.ReconciliationRuleStore;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.exactIntegerOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * The rule library and its results (ADR 0024).
 *
 * <p>Reads {@code migration.reconciliation_rules} and writes
 * {@code migration.reconciliation_results}, which is the shape of the port: a
 * result is meaningless without the version of the rule it was measured under.
 */
@Repository
public class JdbcReconciliationRuleStore implements ReconciliationRuleStore {

    private final JdbcClient jdbc;

    public JdbcReconciliationRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Declaration> findCurrent(String ruleCode) {
        // No ORDER BY and no LIMIT: ux_reconciliation_rule_current guarantees at
        // most one live version, so two rows here would surface as an error rather
        // than as a silent choice between two severities.
        return jdbc.sql("""
                SELECT id, rule_code, rule_version, capability, entity_type, severity,
                       measure_kind, tolerance_kind, tolerance_value, rationale
                FROM migration.reconciliation_rules
                WHERE rule_code = :ruleCode AND retired_at IS NULL
                """)
                .param("ruleCode", ruleCode)
                .query(this::mapDeclaration)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ON CONFLICT} on {@code (run, rule, dimension)}, so a suite retried
     * inside one run restates its finding rather than filing a second one. The
     * update deliberately leaves {@code status}, {@code approved_by} and
     * {@code approved_at} alone: a re-measured rule that still disagrees must not
     * clear an approval somebody gave, and one that now agrees is settled by a
     * resolution rather than by the row quietly changing underneath it.
     */
    @Override
    public UUID record(Result result, Instant now) {
        Measurement measurement = result.measurement();

        // A HashMap: each measure kind leaves a different set of these null, and
        // the schema's CHECK is what enforces which. Map.of would reject them.
        Map<String, Object> values = new HashMap<>();
        values.put("expectedValue", measurement.expected());
        values.put("actualValue", measurement.actual());
        values.put("differenceValue", measurement.difference());
        values.put("currency", measurement.currency());
        values.put("expectedChecksum", measurement.expectedChecksum());
        values.put("actualChecksum", measurement.actualChecksum());
        values.put("sampleReference", measurement.sampleReference());

        return jdbc.sql("""
                INSERT INTO migration.reconciliation_results (
                    id, tenant_id, run_id, scope_id, rule_code, rule_version, dimension_key,
                    severity, measure_kind, expected_value, actual_value, difference_value,
                    currency, expected_checksum, actual_checksum, sample_reference,
                    status, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :runId, :scopeId, :ruleCode, :ruleVersion, :dimensionKey,
                    :severity, :measureKind, :expectedValue, :actualValue, :differenceValue,
                    :currency, :expectedChecksum, :actualChecksum, :sampleReference,
                    'OPEN', :now, :now)
                ON CONFLICT ON CONSTRAINT uq_reconciliation_result DO UPDATE
                SET expected_value = EXCLUDED.expected_value,
                    actual_value = EXCLUDED.actual_value,
                    difference_value = EXCLUDED.difference_value,
                    currency = EXCLUDED.currency,
                    expected_checksum = EXCLUDED.expected_checksum,
                    actual_checksum = EXCLUDED.actual_checksum,
                    sample_reference = EXCLUDED.sample_reference,
                    updated_at = EXCLUDED.updated_at
                WHERE migration.reconciliation_results.tenant_id = :tenantId
                RETURNING id
                """)
                .param("id", result.id()).param("tenantId", result.tenantId())
                .param("runId", result.runId()).param("scopeId", result.scopeId())
                .param("ruleCode", result.ruleCode()).param("ruleVersion", result.ruleVersion())
                .param("dimensionKey", result.dimensionKey())
                .param("severity", result.severity().name())
                .param("measureKind", measurement.measureKind().name())
                .params(values)
                .param("now", utc(now))
                .query(UUID.class)
                .single();
    }

    private Declaration mapDeclaration(ResultSet row, int rowNumber) throws SQLException {
        BigInteger tolerance = exactIntegerOrNull(row, "tolerance_value");
        return new Declaration(
                row.getObject("id", UUID.class),
                row.getString("rule_code"),
                row.getInt("rule_version"),
                row.getString("capability"),
                row.getString("entity_type"),
                ReconciliationSeverity.valueOf(row.getString("severity")),
                row.getString("measure_kind"),
                row.getString("tolerance_kind"),
                // The column is NOT NULL DEFAULT 0, so this cannot be null; zero
                // rather than null anyway, because a null tolerance compared with
                // compareTo would throw on the path a gate takes.
                tolerance == null ? BigInteger.ZERO : tolerance,
                row.getString("rationale"));
    }
}
