package uz.horecaos.platform.migration.web;

import java.time.Instant;
import java.util.UUID;

import uz.horecaos.platform.migration.application.MigrationCutoverDecisionStore.Decision;
import uz.horecaos.platform.migration.application.MigrationCutoverDecisionStore.DecisionRow;
import uz.horecaos.platform.migration.domain.ScopeState;

/**
 * One recorded decision about moving a capability's writer.
 *
 * <p>Both names are published. A refusal that showed only the decider would let
 * a reviewer read it as a disagreement between the decider and nobody, when what
 * it records is one named person declining another named person's request — which
 * is the fact ADR 0027's four eyes exists to produce.
 *
 * <p>The evidence snapshot is not published here. It is an open set of figures
 * with no fixed keys, and ADR 0031 admits no unbounded free-form map into a
 * response contract; it is read from the audit trail and the decisions table,
 * where it is evidence rather than a field a client can come to depend on.
 *
 * @param scopeVersion the revision of the scope the decision was taken against,
 *                     which is what binds the approval to what the approver was
 *                     actually looking at
 * @param decidedBy    null for a decision nobody made — a request withdrawn or
 *                     expired — and never equal to {@code requestedBy}
 */
public record CutoverDecisionView(
        UUID id,
        UUID scopeId,
        ScopeState fromState,
        ScopeState toState,
        int scopeVersion,
        Decision decision,
        String reason,
        String requestedBy,
        String decidedBy,
        UUID approvalRequestId,
        Instant requestedAt,
        Instant decidedAt) {

    static CutoverDecisionView of(DecisionRow row) {
        return new CutoverDecisionView(
                row.id(), row.scopeId(), row.fromState(), row.toState(), row.scopeVersion(),
                row.decision(), row.reason(), row.requestedBy(), row.decidedBy(),
                row.approvalRequestId(), row.requestedAt(), row.decidedAt());
    }
}
