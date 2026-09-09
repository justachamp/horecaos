package uz.horecaos.platform.integration.api.pos;

/**
 * The seam between whatever decided a catalog sync should start and the ADR
 * 0004 outbox.
 *
 * <p>Narrowed to one method for the same reason {@code ReconciliationRequester}
 * is: the caller — {@code pos}'s durable scheduler and its resume endpoint —
 * depends on "can ask for a sync", not on the JDBC outbox store or object
 * mapper the concrete implementation also carries. A top-level interface here
 * (rather than a member of the implementing class) is also what lets a test
 * double implement it directly with no database.
 */
public interface PosSyncRequester {

    /**
     * Appends a {@code PosSyncRequested} command to the outbox, in the caller's
     * own transaction.
     *
     * <p>Never publishes to Kafka directly — see ADR 0004. The caller decides
     * atomicity: the scheduler calls this inside the same transaction that
     * advances the claimed schedule's {@code next_run_at}, so the command exists
     * if and only if that advance committed.
     */
    void requestSync(PosSyncRequestedPayload command, String correlationId);
}
