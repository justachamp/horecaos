package uz.horecaos.platform.audit.api;

import java.util.UUID;

/**
 * Whether an action may proceed under the maker-checker policy (ADR 0027).
 */
public sealed interface ApprovalOutcome {

    /**
     * No resolved policy requires approval for this action at this scope and size.
     *
     * <p>This can mean a resolved policy's threshold did not apply, or that the
     * action's ADR 0050 register entry deliberately permits an absent policy.
     * An action whose entry requires configuration instead fails with
     * {@code APPROVAL_POLICY_REQUIRED}; it never reaches this outcome.
     */
    record NotRequired() implements ApprovalOutcome { }

    /**
     * Approval is required and has been requested.
     *
     * <p>The caller must not perform the side effect. It persists its own state
     * and resumes when the request is decided, which composes with the process
     * managers in ADR 0019 and the step handlers in ADR 0008.
     */
    record Pending(UUID requestId) implements ApprovalOutcome { }

    /**
     * A matching request was approved; the action may proceed exactly once.
     *
     * <p>Exactly once is carried by {@code grant} rather than asserted in prose.
     * The record used to be two identifiers, and a caller that read them and
     * acted had no way to say it had acted, so the approval stayed answerable
     * for the rest of its validity and one signature authorised every identical
     * resubmission a maker cared to make. The grant is the half that was
     * missing: hold it, perform the action, and spend it in the same transaction.
     */
    record Approved(UUID requestId, String approvedBy, ApprovalGrant grant) implements ApprovalOutcome { }

    /** A matching request was declined. */
    record Declined(UUID requestId, String reason) implements ApprovalOutcome { }

    default boolean mayProceed() {
        return this instanceof NotRequired || this instanceof Approved;
    }

    /**
     * Spends the approval this outcome carries, if it carries one.
     *
     * <p>Call it in the transaction that performs the action, immediately after
     * {@link #mayProceed()} clears the way. Spending first rather than last is
     * deliberate: it takes the row lock before the effect is written, so two
     * executions racing under one approval serialise onto one winner instead of
     * both applying and one being unwound. Atomicity is the transaction's job
     * either way — the spend and the effect commit together or neither does.
     *
     * <p>{@code NotRequired} spends nothing, so every call site can say this
     * unconditionally rather than re-deciding which outcome it is holding.
     */
    default void consume() {
        if (this instanceof Approved approved) {
            approved.grant().consume();
        }
    }
}
