package uz.horecaos.platform.integration.api;

import org.jspecify.annotations.Nullable;

/**
 * An inbox handler whose work includes calling something outside the platform
 * (ADR 0005, ADR 0007).
 *
 * <p>{@link InboxHandler} says plainly that a handler must not call an external
 * provider, and the reason is not style: {@code InboxExecutor} runs a plain
 * handler inside the transaction that also records the {@code PROCESSED}
 * transition, so a provider call there would hold one of ten pooled connections
 * open across the network for the whole of a courier's timeout. Ten slow calls
 * would stall every other module on the box, which is the property
 * {@code ExternalCallTransactionBoundaryTests} exists to defend.
 *
 * <p>ADR 0007's whole design is nonetheless a command arriving on Kafka, a route
 * calling a provider, and the result reaching the outbox — so the answer is not
 * to forbid the shape but to split it. {@link #perform} runs with no transaction
 * open; {@link #record} runs inside the one that commits the inbox transition.
 * A handler that implements this interface is telling the executor which half is
 * which, and the executor holds no connection while the first half runs.
 *
 * <h2>What the split costs, stated rather than hidden</h2>
 *
 * <p>The external effect happens before the evidence of it commits. If the
 * process dies in between, the record is redelivered and {@link #perform} runs
 * again. That is unavoidable — holding the transaction open across the call does
 * not fix it, it only adds the connection cost, because the commit can fail after
 * the call either way. The mitigation is the one ADR 0007 already requires: the
 * provider idempotency key is derived from the canonical command id, so a second
 * attempt is deduplicated by the provider rather than by luck. A command whose
 * effect cannot be keyed that way does not belong on this interface.
 *
 * @param <T> the version-specific payload type
 * @param <W> whatever {@link #perform} learned, handed to {@link #record}
 */
public interface ExternalWorkInboxHandler<T, W> extends InboxHandler<T> {

    /**
     * The part that talks to the outside world. No transaction is open.
     *
     * <p>Throwing schedules an inbox retry under the ADR 0006 backoff, and
     * exhausting the attempts dead-letters the record. Returning normally means
     * the outcome is settled enough to record, whatever that outcome was.
     *
     * @param attempt which try this is, so a handler can settle differently on
     *                the last one instead of letting an unanswerable case
     *                disappear into a dead letter
     * @return whatever the handler learned, or {@code null} when there is
     *         nothing honest to record and the work is simply done
     */
    @Nullable
    W perform(ExternalEventEnvelope<T> event, Attempt attempt);

    /**
     * The part that writes. Runs inside the transaction that also marks the
     * inbox row {@code PROCESSED}, so the effect and the evidence commit
     * together or not at all. It must not call anything external.
     *
     * @param work whatever {@link #perform} returned, including {@code null}
     *             when there was nothing honest to record
     */
    void record(ExternalEventEnvelope<T> event, @Nullable W work);

    /**
     * Kept so that a caller holding only an {@link InboxHandler} still behaves
     * correctly, and so the interface cannot drift into two contracts.
     */
    @Override
    default void handle(ExternalEventEnvelope<T> event) {
        record(event, perform(event, new Attempt(1, 1)));
    }

    /**
     * Which try this is, out of how many the consumer allows.
     *
     * @param number  this attempt, counting from one
     * @param maximum the configured attempt budget for the consumer
     */
    record Attempt(int number, int maximum) {

        public Attempt {
            if (number < 1 || maximum < 1) {
                throw new IllegalArgumentException("Attempt numbers start at one");
            }
        }

        /** Whether a failure now would exhaust the budget rather than retry. */
        public boolean isLast() {
            return number >= maximum;
        }
    }
}
