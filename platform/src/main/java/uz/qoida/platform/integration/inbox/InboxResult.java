package uz.qoida.platform.integration.inbox;

/**
 * The outcome of offering one record to the inbox (ADR 0005).
 *
 * <p>Every outcome except {@link #RETRY_SCHEDULED} means the Kafka offset may be
 * acknowledged: the platform has taken durable responsibility for the record,
 * whether by processing it, recognising it as already processed, quarantining
 * it for operations, or parking it behind an earlier sibling.
 */
public enum InboxResult {

    /** The handler ran and its effect committed with the inbox transition. */
    PROCESSED,

    /** Already processed under this consumer name; the handler did not run again. */
    DUPLICATE_IGNORED,

    /**
     * The same event id arrived with a different payload. A producer has
     * violated event immutability, so this is quarantined rather than treated
     * as a duplicate.
     */
    CONTRACT_COLLISION,

    /** No handler is registered for this type and version; permanently dead-lettered. */
    UNSUPPORTED,

    /** The envelope itself was invalid; permanently dead-lettered. */
    INVALID_ENVELOPE,

    /** A transient failure. The offset is not acknowledged and the item retries. */
    RETRY_SCHEDULED,

    /**
     * An earlier unresolved event for the same aggregate has not been settled,
     * so this one is parked rather than allowed to overtake it (ADR 0006).
     *
     * <p>The offset <em>is</em> acknowledged, which looks wrong until you count
     * what the alternative stalls. A Kafka partition carries many aggregates;
     * refusing the offset would hold every other aggregate on that partition
     * behind one dead letter, and ADR 0006 is explicit that other aggregates
     * continue. The parked row is durable and due for the retry worker, so
     * nothing is lost by letting the partition move on.
     */
    BLOCKED_BY_EARLIER,

    /** Retries are exhausted; retained for ADR 0006 operations. */
    DEAD_LETTERED;

    public boolean mayAcknowledgeOffset() {
        return this != RETRY_SCHEDULED;
    }
}
