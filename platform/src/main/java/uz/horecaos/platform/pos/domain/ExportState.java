package uz.horecaos.platform.pos.domain;

/**
 * Where one order's export has got to (ADR 0011).
 *
 * <p>Nine states for what a provider with an idempotency key <em>this platform
 * controls</em> would express in four. The five extra ones all exist because the
 * first real POS this platform integrates against offers no such key or header —
 * its own retry guidance concedes as much, telling integrators to "check the
 * server state first to avoid duplicates" — even though Clopos later confirmed
 * (Q1/Q18, docs/providers/clopos-api.md) that it does deduplicate a
 * byte-identical repeat. Nothing here reconstructs and resends a stored request
 * body, so that confirmation changes what this class's comments claim, not what
 * it does. With an eight-second upstream timeout, a lost response is a weekly
 * event and not an exotic one.
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

    /**
     * Whether an amendment may proceed against the order this export belongs to
     * (ADR 0039, orders.md &sect;3.11/&sect;4.4, wave P42's operations-plane
     * mirror of the gate `ordering.application.PosExportStatus#settledFor`
     * already enforces).
     *
     * <p>Deliberately duplicated rather than shared: {@code ordering} cannot
     * import this type (it reads this module's table through SQL instead, per
     * {@code JdbcPosExportStatus}'s own doc), so this is the one place {@code
     * pos} states the same rule for its own operations-plane read. Keep the two
     * in sync by hand if either ever changes — there is no third place doing so
     * automatically.
     *
     * <p>{@code PENDING} permits: the row exists but nothing has been sent, so
     * the kitchen has seen nothing yet. {@code REJECTED}, {@code
     * RESOLVED_ABSENT} and {@code ABANDONED} permit because the till
     * demonstrably does not hold this order. Every other state — including
     * {@code ACCEPTED}, where the ticket is confirmed printed — refuses,
     * because the kitchen may already be holding a ticket this edit would
     * silently leave stale.
     */
    public boolean permitsAmendment() {
        return switch (this) {
            case PENDING, REJECTED, RESOLVED_ABSENT, ABANDONED -> true;
            default -> false;
        };
    }
}
