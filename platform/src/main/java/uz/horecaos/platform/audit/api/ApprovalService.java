package uz.horecaos.platform.audit.api;

import java.util.UUID;

/**
 * The one maker-checker implementation (ADR 0027).
 *
 * <p>ADRs 0006, 0012, 0013, 0021, and 0024 all require approval above
 * configured thresholds. They consume this rather than each building an
 * approval table, because five approval models would differ in who may approve,
 * whether a policy change reopens a decision, and whether an approval expires.
 */
public interface ApprovalService {

    /**
     * Resolves whether the action needs approval and, if so, records a request.
     *
     * <p>Returns {@link ApprovalOutcome.Approved} when a valid, unspent approval
     * for the identical parameters already exists, so a caller resuming after
     * approval takes the same code path as one that never needed it.
     *
     * <p><strong>Call this inside the transaction that performs the action, and
     * call {@link ApprovalOutcome#consume()} before performing it.</strong> An
     * {@code Approved} outcome carries an {@link ApprovalGrant} that must be
     * spent for the approval to be single-use; a spent approval no longer
     * matches, so the maker's next identical submission raises a fresh request
     * for a fresh signature. Implementations refuse to hand out a grant when
     * there is no transaction to bind the spend to, and fail a transaction that
     * commits still holding one.
     */
    ApprovalOutcome requireApproval(ApprovalRequestCommand command);

    /** Records a decision. The approver may never be the requester. */
    void decide(UUID requestId, Decision decision, ActorRef approver, String reason);

    /** Marks overdue requests expired; scheduled, not on the request path. */
    int expireOverdue();

    enum Decision {
        APPROVE,
        DECLINE
    }

    /** Thrown when an approver attempts to decide their own request. */
    final class SelfApprovalException extends IllegalStateException {
        public SelfApprovalException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a transaction commits still holding an unspent grant.
     *
     * <p>A programming error rather than an operator one, and deliberately fatal
     * to the transaction: a caller that acted on an approval and did not spend
     * it has just reproduced the defect the grant exists to close, and the only
     * safe reading of a committed effect under an approval still answerable is
     * that neither should have happened.
     */
    final class ApprovalNotConsumedException extends IllegalStateException {
        public ApprovalNotConsumedException(UUID requestId) {
            super(("Approval request %s authorised an action that never spent it. "
                            + "Call ApprovalOutcome.consume() in the transaction that performs the action.")
                    .formatted(requestId));
        }
    }
}
