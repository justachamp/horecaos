package uz.horecaos.platform.integration.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The half of {@link JdbcOutboxStore} that {@link OutboxRelay} actually needs.
 *
 * <p>Narrowed so the relay depends on "claim, publish, fail a batch", not on the
 * JDBC client the concrete store also carries — interface segregation rather
 * than a fat dependency. It is also what lets a relay test's recording double
 * implement this directly, with no database standing in behind a collaborator
 * it never uses.
 *
 * <p>A top-level type rather than a member of {@link JdbcOutboxStore} itself:
 * {@code class X implements X.Y} is a cyclic-inheritance error in javac —
 * resolving {@code X}'s supertypes requires completing its member {@code Y}, and
 * completing a member type requires its enclosing type already resolved.
 */
public interface RelayStore {
    List<ClaimedOutboxEvent> claimBatch(Instant now, Duration leaseDuration, int batchSize);

    boolean markPublished(UUID eventId, UUID claimToken, Instant publishedAt);

    boolean markFailed(
            UUID eventId, UUID claimToken, Instant now, Instant nextAttemptAt, String error, boolean deadLetter);
}
