package uz.horecaos.platform.migration.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.migration.domain.ScopeState;

/**
 * Appends to and reads {@code migration.cutover_decisions}.
 *
 * <p>There is no update and no delete, and the application role in V0024 holds
 * only {@code SELECT} and {@code INSERT}, so the absence is enforced a layer
 * below this interface as well. A cutover decision is the record that a person
 * accepted responsibility for moving a capability's writer, and a record that
 * can be edited afterwards is worth nothing at the review where it matters.
 */
public interface MigrationCutoverDecisionStore {

    /**
     * The decision this key already recorded, per {@code uq_cutover_idempotency}.
     *
     * <p>ADR 0031's replay for the one mutation in this package that cannot be
     * made safe by a version check alone. A retried approval that applied twice
     * would move a scope that had already moved on.
     */
    Optional<DecisionRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * The approved decision taken against exactly this revision of the scope, per
     * {@code ux_cutover_approved_per_version}.
     *
     * <p>The version is what binds the approval to what the approver was looking
     * at. An approval that stayed valid across an edit of the scope would be a
     * signature on a document somebody changed afterwards.
     */
    Optional<DecisionRow> findApproved(UUID tenantId, UUID scopeId, int scopeVersion);

    /**
     * Appends the decision.
     *
     * <p>Implementations must let the constraint violations through rather than
     * pre-checking them away: {@code ux_cutover_approved_per_version} is what
     * makes two approvals racing on one scope version resolve to one winner, and
     * a store that read-then-inserted would let both believe they moved it.
     */
    void insert(DecisionRow decision);

    /** What was decided about a proposed move. */
    enum Decision {

        /** The move was authorised, and the transition follows in this transaction. */
        APPROVED,

        /** A named person declined it. Recorded, and the scope does not move. */
        REFUSED,

        /** The requester withdrew it before anyone decided. */
        WITHDRAWN,

        /** Nobody decided it in time. */
        EXPIRED;

        /** Whether {@code ck_cutover_decider} requires this decision to name a decider. */
        public boolean requiresDecider() {
            return this == APPROVED || this == REFUSED;
        }
    }

    /**
     * Who transferred ownership, on what evidence, and whether anyone agreed.
     *
     * @param scopeVersion     the revision of the scope the decision was taken
     *                         against: the optimistic-concurrency token and part
     *                         of the evidence at once
     * @param evidenceSnapshot the aggregate figures the decision rested on —
     *                         watermarks, counts, checksums, the reconciliation
     *                         runs that cleared, the observed soak window.
     *                         References and totals, never source rows
     * @param decidedBy        the person who decided, whichever way they decided;
     *                         null while nobody has, and never equal to {@code
     *                         requestedBy}, which {@code ck_cutover_four_eyes}
     *                         also refuses
     * @param approvalRequestIsPlatform
     *                         whether the cited approval request is a
     *                         PLATFORM-scope one. Null exactly when no request is
     *                         cited, which {@code
     *                         ck_cutover_approval_ownership_declared} enforces.
     *                         It exists because {@code
     *                         audit.approval_requests.tenant_id} is nullable and a
     *                         request id alone therefore does not say whose the
     *                         request is; V0088 turns this declaration into the
     *                         tenant half of {@code fk_cutover_approval_request},
     *                         so a decision citing another tenant's authorisation
     *                         cannot be stored however it is written
     */
    record DecisionRow(
            UUID id,
            UUID tenantId,
            UUID scopeId,
            ScopeState fromState,
            ScopeState toState,
            int scopeVersion,
            Decision decision,
            String reason,
            Map<String, Object> evidenceSnapshot,
            String requestedBy,
            String decidedBy,
            UUID approvalRequestId,
            Boolean approvalRequestIsPlatform,
            String idempotencyKey,
            Instant requestedAt,
            Instant decidedAt) {

        public DecisionRow {
            if ((approvalRequestId == null) != (approvalRequestIsPlatform == null)) {
                throw new IllegalArgumentException(
                        "A cutover decision either cites an approval request and says whose it "
                                + "is, or cites none. Leaving the ownership undeclared is what "
                                + "takes fk_cutover_approval_request out of the check entirely.");
            }
        }
    }
}
