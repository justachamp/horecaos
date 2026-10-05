package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore.DueOrder;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore.Finding;

/**
 * The sweep's own behaviour when an order misbehaves (ADR 0140, ADR 0019, row 6.1).
 *
 * <p>What the re-quote finds is {@code PromotionLifecycleHttpTests}' business, against a real database; this
 * class owns what a sweep does when one order blows up, which a real database cannot be made to do on cue: it
 * keeps going, it records that the order could not be judged so the next pass does not pick it up again for
 * ever, and it neither logs nor records anything that came out of the failure.
 */
class ScheduledOrderRequoteWorkerTests {

    private static final Instant NOW = Instant.parse("2026-10-05T07:30:00Z");
    private static final Duration LEAD = Duration.ofHours(1);

    private final JdbcOrderRequoteStore store = mock(JdbcOrderRequoteStore.class);
    private final ScheduledOrderRequoteService service = mock(ScheduledOrderRequoteService.class);
    private final ScheduledOrderRequoteWorker worker =
            new ScheduledOrderRequoteWorker(service, store, Clock.fixed(NOW, ZoneOffset.UTC), LEAD, 50);

    private static final UUID TENANT = UUID.randomUUID();
    private final UUID broken = UUID.randomUUID();
    private final UUID fine = UUID.randomUUID();

    @Test
    @DisplayName("the sweep asks for the orders due within the lead time and judges each at the sweep's instant")
    void theSweepJudgesEachDueOrderAtItsOwnInstant() {
        when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50)).thenReturn(List.of(new DueOrder(TENANT, fine)));
        when(service.requoteAtCheckpoint(TENANT, fine, NOW)).thenReturn(Optional.of(mock(Finding.class)));

        assertThat(worker.sweepOnce(NOW)).isEqualTo(1);

        verify(service).requoteAtCheckpoint(TENANT, fine, NOW);
        verify(service, never()).recordFailure(any(), any(), any());
    }

    @Test
    @DisplayName("an order that fails is recorded as failed and the sweep carries on to the next one")
    void anOrderThatFailsIsRecordedAndTheSweepContinues() {
        when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50))
                .thenReturn(List.of(new DueOrder(TENANT, broken), new DueOrder(TENANT, fine)));
        when(service.requoteAtCheckpoint(TENANT, broken, NOW)).thenThrow(new IllegalStateException("boom"));
        when(service.requoteAtCheckpoint(TENANT, fine, NOW)).thenReturn(Optional.of(mock(Finding.class)));

        int judged = worker.sweepOnce(NOW);

        assertThat(judged)
                .as("the failure is a finding, and the next order is still judged")
                .isEqualTo(2);
        verify(service).recordFailure(TENANT, broken, NOW);
        verify(service, never()).recordFailure(eq(TENANT), eq(fine), any());
    }

    @Test
    @DisplayName("a transient failure is not recorded as a pricing refusal: the order stays due and the next sweep"
            + " tries it again")
    void aTransientFailureLeavesTheOrderDue() {
        when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50))
                .thenReturn(List.of(new DueOrder(TENANT, broken), new DueOrder(TENANT, fine)));
        when(service.requoteAtCheckpoint(TENANT, broken, NOW))
                .thenThrow(new CannotCreateTransactionException("Connection is not available, request timed out"));
        when(service.requoteAtCheckpoint(TENANT, fine, NOW)).thenReturn(Optional.of(mock(Finding.class)));

        assertThat(worker.sweepOnce(NOW))
                .as("only the order that was judged counts, and the sweep carries on past the one that was not")
                .isEqualTo(1);

        verify(service, never()).recordFailure(any(), any(), any());
        verify(service).requoteAtCheckpoint(TENANT, fine, NOW);
    }

    @Test
    @DisplayName("every kind of transient database failure leaves the order due, however deeply it is wrapped")
    void transientFailuresAreRecognisedThroughTheirCauses() {
        for (RuntimeException transientFailure : List.<RuntimeException>of(
                new QueryTimeoutException("statement timed out"),
                new CannotAcquireLockException("deadlock detected"),
                new CannotGetJdbcConnectionException("no connection", new SQLException("pool exhausted")),
                new TransactionTimedOutException("transaction timed out"),
                new IllegalStateException(
                        "pricing failed", new RuntimeException("wrapped", new QueryTimeoutException("cancelled"))),
                new IllegalStateException("pricing failed", new SQLTransientConnectionException("connect timeout")))) {
            when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50)).thenReturn(List.of(new DueOrder(TENANT, broken)));
            // doThrow, not when(): a repeated when() calls the stubbed method, which throws the last round's failure.
            doThrow(transientFailure).when(service).requoteAtCheckpoint(TENANT, broken, NOW);

            assertThat(worker.sweepOnce(NOW)).as(transientFailure.toString()).isZero();
        }

        verify(service, never()).recordFailure(any(), any(), any());
    }

    @Test
    @DisplayName("a failure that is not transient is still recorded, once, so a broken order is not retried for ever")
    void aPermanentFailureIsStillRecorded() {
        when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50)).thenReturn(List.of(new DueOrder(TENANT, broken)));
        when(service.requoteAtCheckpoint(TENANT, broken, NOW))
                .thenThrow(new IllegalStateException("The quote behind an order's revision is gone"));

        assertThat(worker.sweepOnce(NOW)).isEqualTo(1);

        verify(service).recordFailure(TENANT, broken, NOW);
    }

    @Test
    @DisplayName("an order whose failure cannot even be recorded does not stop the sweep")
    void aFailureThatCannotBeRecordedDoesNotStopTheSweep() {
        when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50))
                .thenReturn(List.of(new DueOrder(TENANT, broken), new DueOrder(TENANT, fine)));
        when(service.requoteAtCheckpoint(TENANT, broken, NOW)).thenThrow(new IllegalStateException("boom"));
        doThrow(new IllegalStateException("the database is down")).when(service).recordFailure(TENANT, broken, NOW);
        when(service.requoteAtCheckpoint(TENANT, fine, NOW)).thenReturn(Optional.of(mock(Finding.class)));

        assertThat(worker.sweepOnce(NOW))
                .as("only the order that could be judged counts")
                .isEqualTo(1);

        verify(service).requoteAtCheckpoint(TENANT, fine, NOW);
    }

    @Test
    @DisplayName("an order another node has already judged writes nothing and counts for nothing")
    void anOrderAnotherNodeJudgedCountsForNothing() {
        when(store.dueForCheckpoint(NOW, LEAD.toSeconds(), 50)).thenReturn(List.of(new DueOrder(TENANT, fine)));
        when(service.requoteAtCheckpoint(TENANT, fine, NOW)).thenReturn(Optional.empty());

        assertThat(worker.sweepOnce(NOW)).isZero();
        verify(service, never()).recordFailure(any(), any(), any());
    }
}
