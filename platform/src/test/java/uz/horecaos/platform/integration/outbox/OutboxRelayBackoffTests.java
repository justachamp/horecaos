package uz.horecaos.platform.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

/**
 * ADR 0006's retry policy is "exponential backoff with jitter". The relay had
 * the first half.
 *
 * <p>Nothing here needs a database: the question is what the relay computes as
 * the next attempt time, so the store is a recorder and the publisher always
 * fails. The assertion is a strict inequality rather than a distribution, so it
 * cannot flake — an undithered delay lands exactly on the ceiling every time.
 */
class OutboxRelayBackoffTests {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    @Test
    void aRetryIsScheduledStrictlyInsideItsExponentialCeiling() {
        RecordingStore store = new RecordingStore(claimed(4));
        relay(store).relayOnce();

        // Four prior attempts, so the undithered delay would be 1s * 2^3.
        Instant undithered = NOW.plus(Duration.ofSeconds(8));

        assertThat(store.nextAttempts).hasSize(1);
        assertThat(store.nextAttempts.getFirst())
                .as("a delay equal to the ceiling is the undithered value, which is the bug")
                .isBefore(undithered)
                .isAfterOrEqualTo(NOW.plus(Duration.ofSeconds(4)));
    }

    @Test
    void replicasFailingOnTheSameAttemptDoNotAllRetryAtOneInstant() {
        List<ClaimedOutboxEvent> batch = new ArrayList<>();
        for (int replica = 0; replica < 12; replica++) {
            batch.add(claimed(6).getFirst());
        }
        RecordingStore store = new RecordingStore(batch);

        relay(store).relayOnce();

        assertThat(store.nextAttempts.stream().distinct().toList())
                .as("a thundering herd is twelve replicas computing the same delay from the "
                        + "same attempt count and waking together against a broker that just fell over")
                .hasSizeGreaterThan(1);
    }

    @Test
    void anExhaustedEventIsDeadLetteredRatherThanBackedOff() {
        RecordingStore store = new RecordingStore(claimed(10));

        relay(store).relayOnce();

        assertThat(store.deadLettered).containsExactly(true);
        assertThat(store.nextAttempts.getFirst())
                .as("a dead letter waits on an operator, not on a timer")
                .isEqualTo(NOW);
    }

    private static OutboxRelay relay(JdbcOutboxStore store) {
        return new OutboxRelay(
                store,
                event -> {
                    throw new IllegalStateException("broker unavailable");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(),
                20,
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                10,
                INITIAL_BACKOFF,
                Duration.ofMinutes(5));
    }

    private static List<ClaimedOutboxEvent> claimed(int attemptCount) {
        return List.of(new ClaimedOutboxEvent(
                UUID.randomUUID(), "TenantCreated", 1, UUID.randomUUID(),
                "Tenant", UUID.randomUUID(), "tenancy.events", "partition-key",
                "correlation-1", null, NOW.minusSeconds(60), "{}", null,
                attemptCount, UUID.randomUUID()));
    }

    /**
     * A store that records what the relay decided instead of persisting it.
     * Hand-rolled because this repository has no mocking framework, and because
     * the two recorded values are the entire subject of the test.
     */
    private static final class RecordingStore extends JdbcOutboxStore {

        private final List<ClaimedOutboxEvent> batch;
        private final List<Instant> nextAttempts = new CopyOnWriteArrayList<>();
        private final List<Boolean> deadLettered = new CopyOnWriteArrayList<>();

        private RecordingStore(List<ClaimedOutboxEvent> batch) {
            super(null);
            this.batch = batch;
        }

        @Override
        public List<ClaimedOutboxEvent> claimBatch(Instant now, Duration leaseDuration, int batchSize) {
            return batch;
        }

        @Override
        public boolean markFailed(
                UUID eventId, UUID claimToken, Instant now, Instant nextAttemptAt,
                String error, boolean deadLetter) {
            nextAttempts.add(nextAttemptAt);
            deadLettered.add(deadLetter);
            return true;
        }
    }
}
