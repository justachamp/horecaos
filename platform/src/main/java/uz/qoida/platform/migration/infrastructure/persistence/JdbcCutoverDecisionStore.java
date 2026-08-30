package uz.qoida.platform.migration.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.migration.application.MigrationCutoverDecisionStore;
import uz.qoida.platform.migration.domain.ScopeState;

import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.documentJson;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.documentOrEmpty;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Cutover decision persistence (ADR 0024, ADR 0027).
 *
 * <p>Append-only, and this store has no update method because the database has no
 * update grant: {@code qoida_application} holds SELECT and INSERT on this table
 * and nothing else. A cutover decision is the record that a named human accepted
 * responsibility for moving a capability's writer, and a record that can be
 * edited afterwards is worth nothing at the review where it matters.
 *
 * <p>Transitions nobody decided — a failing gate forcing BLOCKED_RECONCILIATION,
 * a supervisor pausing a runaway catch-up — do not belong here. They are audited
 * through {@code AuditRecorder} in the transaction that makes them, because a
 * decision table holding rows nobody decided would make the decider column
 * meaningless on exactly the rows a reviewer reads first.
 */
@Repository
public class JdbcCutoverDecisionStore implements MigrationCutoverDecisionStore {

    private static final String SELECT_DECISION = """
            SELECT id, tenant_id, scope_id, from_state, to_state, scope_version, decision,
                   reason, evidence_snapshot, requested_by, decided_by, approval_request_id,
                   approval_request_is_platform, idempotency_key, requested_at, decided_at
            FROM migration.cutover_decisions""";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCutoverDecisionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DecisionRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT_DECISION + " WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId).param("key", idempotencyKey)
                .query(this::mapDecision)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The status predicate is {@code APPROVED} and not "the latest decision",
     * because a scope version can carry a refusal and an approval only if the
     * refusal came first and somebody re-requested — and {@code
     * ux_cutover_approved_per_version} guarantees at most one approval either way.
     */
    @Override
    public Optional<DecisionRow> findApproved(UUID tenantId, UUID scopeId, int scopeVersion) {
        return jdbc.sql(SELECT_DECISION + """
                 WHERE tenant_id = :tenantId AND scope_id = :scopeId
                   AND scope_version = :scopeVersion AND decision = 'APPROVED'
                """)
                .param("tenantId", tenantId).param("scopeId", scopeId)
                .param("scopeVersion", scopeVersion)
                .query(this::mapDecision)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two unique keys carry rules that a service check could not. {@code
     * uq_cutover_idempotency} makes a retried "approve cutover" collide instead of
     * applying twice, and the caller reads the decision it already took back
     * through {@link #findByIdempotencyKey}. {@code ux_cutover_approved_per_version}
     * makes a second approval against one scope version impossible, so of two
     * racing approvals only one can be recorded — the other would be describing a
     * transition that never happened while its evidence snapshot claimed
     * otherwise.
     *
     * <p>The decision is written in the same transaction as the scope transition it
     * discharges, and against the scope version that transition expects. Written
     * afterwards in a transaction of its own, it would be evidence of a move that
     * a lost compare-and-set means never occurred.
     *
     * <p>The transition is recorded as states rather than as modes. The modes are
     * derived from the state, so recording only the consequence would make two
     * decisions arriving at TARGET_ONLY — one from CANARY and one from
     * ROLLING_BACK — indistinguishable, and those are opposite decisions.
     */
    @Override
    public void insert(DecisionRow decision) {
        // A HashMap: a withdrawn or expired decision names no decider, and a
        // transition that policy does not gate discharges no approval request.
        Map<String, Object> optional = new HashMap<>();
        optional.put("decidedBy", decision.decidedBy());
        optional.put("approvalRequestId", decision.approvalRequestId());
        optional.put("approvalRequestIsPlatform", decision.approvalRequestIsPlatform());

        jdbc.sql("""
                INSERT INTO migration.cutover_decisions (
                    id, tenant_id, scope_id, from_state, to_state, scope_version, decision,
                    reason, evidence_snapshot, requested_by, decided_by, approval_request_id,
                    approval_request_is_platform, idempotency_key, requested_at, decided_at)
                VALUES (
                    :id, :tenantId, :scopeId, :fromState, :toState, :scopeVersion, :decision,
                    :reason, CAST(:evidence AS jsonb), :requestedBy, :decidedBy,
                    :approvalRequestId, :approvalRequestIsPlatform, :idempotencyKey,
                    :requestedAt, :decidedAt)
                """)
                .param("id", decision.id()).param("tenantId", decision.tenantId())
                .param("scopeId", decision.scopeId())
                .param("fromState", decision.fromState().name())
                .param("toState", decision.toState().name())
                .param("scopeVersion", decision.scopeVersion())
                .param("decision", decision.decision().name())
                .param("reason", decision.reason())
                .param("evidence", documentJson(objectMapper, decision.evidenceSnapshot()))
                .param("requestedBy", decision.requestedBy())
                .params(optional)
                .param("idempotencyKey", decision.idempotencyKey())
                .param("requestedAt", utc(decision.requestedAt()))
                .param("decidedAt", utc(decision.decidedAt()))
                .update();
    }

    private DecisionRow mapDecision(ResultSet row, int number) throws SQLException {
        return new DecisionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("scope_id", UUID.class),
                ScopeState.valueOf(row.getString("from_state")),
                ScopeState.valueOf(row.getString("to_state")),
                row.getInt("scope_version"),
                Decision.valueOf(row.getString("decision")),
                row.getString("reason"),
                documentOrEmpty(objectMapper, row, "evidence_snapshot"),
                row.getString("requested_by"),
                row.getString("decided_by"),
                row.getObject("approval_request_id", UUID.class),
                // getObject, not getBoolean: the column is null exactly when no
                // request is cited, and getBoolean would read that null as false
                // and make the row claim a platform approval it does not have.
                row.getObject("approval_request_is_platform", Boolean.class),
                row.getString("idempotency_key"),
                instantOrNull(row, "requested_at"),
                instantOrNull(row, "decided_at"));
    }
}
