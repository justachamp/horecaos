package uz.qoida.platform.integration.failures;

/**
 * The shared failure classification from ADR 0006.
 *
 * <p>Consistent classification is what keeps retry behavior the same across
 * consumers and routes. Without it each handler decides for itself, and the
 * expensive mistakes are the two directions of getting it wrong: retrying a
 * permanent contract failure forever, and giving up on a transient one.
 */
public enum FailureCategory {

    /** Timeout, broker or database unavailable. */
    TRANSIENT_INFRASTRUCTURE(true),

    /** Provider 429 or 5xx, temporary circuit open. */
    TRANSIENT_PROVIDER(true),

    /** Unknown event version or type. Retrying never helps. */
    CONTRACT_UNSUPPORTED(false),

    /** Missing required fact, or a payload hash collision. */
    PAYLOAD_INVALID(false),

    /** An invariant or stale transition. Terminal, and normally not retried. */
    DOMAIN_REJECTED(false),

    /** Invalid tenant, scope, or service identity. Dead-letter and alert. */
    AUTHORIZATION_REJECTED(false),

    /**
     * The provider may have accepted the command. Reconcile before retrying;
     * a blind retry here is how a customer gets charged twice.
     */
    UNCERTAIN_EXTERNAL_OUTCOME(false);

    private final boolean retryable;

    FailureCategory(boolean retryable) {
        this.retryable = retryable;
    }

    /** Whether a timer may retry this without human judgement. */
    public boolean retryableByTimer() {
        return retryable;
    }

    /** Whether resolving this requires reconciliation evidence first. */
    public boolean requiresReconciliation() {
        return this == UNCERTAIN_EXTERNAL_OUTCOME;
    }

    /** Whether this should raise a security alert rather than an ordinary one. */
    public boolean isSecurityRelevant() {
        return this == AUTHORIZATION_REJECTED;
    }

    /**
     * Whether resolving this needs a second approver (ADR 0027 maker-checker).
     *
     * <p>Retrying is safe and repeatable, so it never needs one. Resolving is
     * irreversible: it declares that work which failed will never be completed.
     * The friction goes exactly where money is, and nowhere else — an operator
     * clearing a malformed payload during an incident should not have to find a
     * second person.
     */
    public boolean requiresSecondApprover() {
        return this == UNCERTAIN_EXTERNAL_OUTCOME;
    }
}
