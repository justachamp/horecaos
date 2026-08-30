package uz.qoida.platform.pos.domain;

/**
 * Where one order's export has got to (ADR 0011).
 *
 * <p>Nine states for what a provider with an idempotency key would express in
 * four. The five extra ones all exist because the first real POS this platform
 * integrates against has no idempotency mechanism of any kind — no key, no
 * header, no documented repeat semantics — and its own retry guidance concedes
 * it, telling integrators to "check the server state first to avoid duplicates".
 * With an eight-second upstream timeout, a lost response is a weekly event and
 * not an exotic one.
 *
 * <p>So the machine has no edge that sends the same order twice on a machine's
 * decision. {@link #UNCERTAIN} does not lead back to {@link #SENT}. The only
 * state a further attempt may leave from is {@link #RESOLVED_ABSENT}, and
 * reaching that means either the provider handed our own correlation reference
 * back — in which case the match is an identifier rather than a guess — or a
 * person said so.
 */
public enum ExportState {

    /** Created. Nothing has been on the wire. */
    PENDING,

    /** An attempt is in flight. Transient, and durable so a crash mid-call is visible. */
    SENT,

    /** The provider answered and named the order it created. */
    ACCEPTED,

    /**
     * The provider refused on business grounds. Terminal: a repeat produces the
     * same refusal, and the branch needs to hear about it rather than a queue.
     */
    REJECTED,

    /**
     * The outcome is unknown. The provider may have created the order, may have
     * created it and lost the reply, or may never have received it.
     *
     * <p>A recovery read may run from here. Nothing re-sends from here.
     */
    UNCERTAIN,

    /**
     * The recovery read finished and could not decide.
     *
     * <p>The ordinary terminus of an uncertain export, not an exception. The read
     * matches candidates on venue, customer phone, creation time and line
     * composition, and that heuristic cannot tell a double export from a customer
     * who ordered the same basket twice ninety seconds apart. Where the evidence
     * does not decide, a person does.
     */
    AWAITING_OPERATOR,

    /** Established that the order exists at the provider. Terminal. */
    RESOLVED_LANDED,

    /**
     * Established that it does not. The one state from which another attempt is
     * permitted.
     */
    RESOLVED_ABSENT,

    /**
     * A person decided this order will not be exported, and the branch takes it
     * another way. Terminal, and audited: somebody chose to leave the till
     * without a ticket for an order the customer is paying for.
     */
    ABANDONED;

    /** Whether the export is finished and nothing further will happen to it. */
    public boolean terminal() {
        return this == ACCEPTED || this == REJECTED || this == RESOLVED_LANDED || this == ABANDONED;
    }

    /** Whether the export is waiting on a person rather than on the platform. */
    public boolean awaitsHuman() {
        return this == AWAITING_OPERATOR;
    }
}
