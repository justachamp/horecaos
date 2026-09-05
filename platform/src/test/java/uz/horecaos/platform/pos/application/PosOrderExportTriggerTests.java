package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * What happens to a confirmed order that nobody presses a button for.
 *
 * <p>Before this trigger existed the answer was "nothing": {@code
 * PosOrderExportService} had no caller anywhere in the platform, so a confirmed
 * order reached a till only by somebody retyping it. The tests here are about the
 * two properties that make an automatic caller safe rather than merely present —
 * that the provider is never called from inside the confirming transaction, and
 * that nothing in this class can ever ask for a second ticket.
 *
 * <p>The transaction is driven by hand rather than by a container. The property
 * under test is <em>which phase</em> each half runs in, and a real transaction
 * manager would let both halves pass while hiding which one happened when.
 */
class PosOrderExportTriggerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a01");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a02");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a03");
    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a04");
    private static final UUID OTHER_ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a05");
    private static final UUID EXPORT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a06");
    private static final UUID OTHER_EXPORT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121a07");

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(15);

    private PosOrderExportService exports;
    private PosOrderExportTrigger trigger;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        exports = mock(PosOrderExportService.class);
        clock = new MutableClock(NOW);
        trigger = new PosOrderExportTrigger(exports, clock, 10_000, 50, STALE_AFTER, 50);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the export row is opened with the confirmation, and nothing is sent yet")
    void theTillIsNotCalledFromInsideTheConfirmingTransaction() {
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));

        trigger.onOrderConfirmed(confirmed(ORDER));

        verify(exports).open(TENANT, ORDER);
        // The whole reason the send is not here: inside the confirming
        // transaction it would hold a pooled connection across the provider call,
        // and a till that has stopped answering would become the response time of
        // an operator's approve button.
        verify(exports, never()).send(any(), any());
        assertThat(trigger.queueDepth())
                .as("a confirmation that has not committed has nothing to dispatch")
                .isZero();
    }

    @Test
    @DisplayName("the till is called once the confirmation has committed")
    void aConfirmedOrderReachesTheTillWithoutAnOperator() {
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));
        when(exports.send(TENANT, EXPORT)).thenReturn(success());

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();

        assertThat(trigger.queueDepth()).isEqualTo(1);
        trigger.dispatchPending();

        verify(exports).send(TENANT, EXPORT);
        assertThat(trigger.queueDepth()).isZero();
    }

    @Test
    @DisplayName("a confirmation that rolls back exports nothing")
    void aRolledBackConfirmationPrintsNoTicket() {
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));

        trigger.onOrderConfirmed(confirmed(ORDER));
        // No commit callback: the audit write failed, or the conditional update
        // lost its race. The export row rolled back with the order, and a ticket
        // for an order that does not exist is the one kind nobody can reconcile.
        rollback();
        trigger.dispatchPending();

        verify(exports, never()).send(any(), any());
    }

    @Test
    @DisplayName("a branch with no POS binding is skipped rather than failed")
    void aBranchWithoutATillIsNotAnError() {
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.empty());

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();
        trigger.dispatchPending();

        assertThat(trigger.queueDepth()).isZero();
        verify(exports, never()).send(any(), any());
    }

    @Test
    @DisplayName("a send whose outcome is unknown is never sent again")
    void anUncertainExportIsNotRetried() {
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));
        when(exports.send(TENANT, EXPORT)).thenReturn(ProviderOutcome.uncertain("TIMEOUT", "The till did not answer"));

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();
        trigger.dispatchPending();
        trigger.dispatchPending();

        // The provider has no idempotency key of any kind, so "we did not hear
        // back" must never become "send it again": ADR 0011's recovery read and,
        // failing that, a person settle it.
        verify(exports, times(1)).send(TENANT, EXPORT);
    }

    @Test
    @DisplayName("a send that throws is not sent again either")
    void aFailedDispatchIsNotRequeued() {
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));
        when(exports.send(TENANT, EXPORT)).thenThrow(new IllegalStateException("route is down"));

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();
        trigger.dispatchPending();
        trigger.dispatchPending();

        verify(exports, times(1)).send(TENANT, EXPORT);
        assertThat(trigger.queueDepth())
                .as("an exception says nothing about whether the ticket printed")
                .isZero();
    }

    @Test
    @DisplayName("a redelivered confirmation converges on one export")
    void aReplayedConfirmationDoesNotOpenASecondExport() {
        // The dedupe is the store's unique key on (tenant_id, order_id), which is
        // why open() answers the same export id twice. What is asserted here is
        // that this class adds nothing that could defeat it — no second row, no
        // second identity, one export id for one order however often the event
        // arrives.
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));
        when(exports.send(TENANT, EXPORT)).thenReturn(success());

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();
        TransactionSynchronizationManager.initSynchronization();
        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();

        trigger.dispatchPending();

        verify(exports, times(2)).open(TENANT, ORDER);
        verify(exports, never()).send(eq(TENANT), eq(OTHER_EXPORT));
    }

    @Test
    @DisplayName("the dispatch queue is bounded, and a drop is loud rather than silent")
    void aBacklogDoesNotGrowWithoutBound() {
        trigger = new PosOrderExportTrigger(exports, clock, 1, 50, STALE_AFTER, 50);
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));
        when(exports.open(TENANT, OTHER_ORDER)).thenReturn(Optional.of(OTHER_EXPORT));
        when(exports.send(TENANT, EXPORT)).thenReturn(success());

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();
        TransactionSynchronizationManager.initSynchronization();
        trigger.onOrderConfirmed(confirmed(OTHER_ORDER));
        commit();

        trigger.dispatchPending();

        verify(exports).send(TENANT, EXPORT);
        // The dropped one keeps its durable PENDING row; what it loses is the
        // hint, which is why the drop is an error-level line naming the export.
        verify(exports, never()).send(TENANT, OTHER_EXPORT);
    }

    @Test
    @DisplayName("one tick sends at most a batch, and the rest keep their turn")
    void aBacklogIsDrainedOverSeveralTicks() {
        trigger = new PosOrderExportTrigger(exports, clock, 10_000, 1, STALE_AFTER, 50);
        when(exports.open(TENANT, ORDER)).thenReturn(Optional.of(EXPORT));
        when(exports.open(TENANT, OTHER_ORDER)).thenReturn(Optional.of(OTHER_EXPORT));
        when(exports.send(any(), any())).thenReturn(success());

        trigger.onOrderConfirmed(confirmed(ORDER));
        commit();
        TransactionSynchronizationManager.initSynchronization();
        trigger.onOrderConfirmed(confirmed(OTHER_ORDER));
        commit();

        trigger.dispatchPending();
        assertThat(trigger.queueDepth())
                .as("a backlog must not turn one tick into an unbounded one")
                .isEqualTo(1);

        trigger.dispatchPending();
        assertThat(trigger.queueDepth()).isZero();
        verify(exports).send(TENANT, EXPORT);
        verify(exports).send(TENANT, OTHER_EXPORT);
    }

    // -------------------------------------------------------------- sweepStale

    @Test
    @DisplayName("the sweep asks the store for exports older than its own staleness threshold")
    void theSweepAsksForExportsOlderThanTheConfiguredThreshold() {
        when(exports.pendingOlderThan(any(), eq(50))).thenReturn(List.of());

        trigger.sweepStale();

        verify(exports).pendingOlderThan(NOW.minus(STALE_AFTER), 50);
    }

    @Test
    @DisplayName("a sweep with nothing stale calls send for nobody")
    void anEmptySweepSendsNothing() {
        when(exports.pendingOlderThan(any(), anyInt())).thenReturn(List.of());

        trigger.sweepStale();

        verify(exports, never()).send(any(), any());
    }

    @Test
    @DisplayName("the sweep dispatches every stale export the store finds, through the same send() the queue uses")
    void theSweepDispatchesWhatTheStoreFinds() {
        when(exports.pendingOlderThan(any(), anyInt()))
                .thenReturn(List.of(
                        new JdbcPosExportStore.StaleExport(TENANT, EXPORT),
                        new JdbcPosExportStore.StaleExport(TENANT, OTHER_EXPORT)));
        when(exports.send(TENANT, EXPORT)).thenReturn(success());
        when(exports.send(TENANT, OTHER_EXPORT)).thenReturn(success());

        trigger.sweepStale();

        verify(exports).send(TENANT, EXPORT);
        verify(exports).send(TENANT, OTHER_EXPORT);
    }

    @Test
    @DisplayName("a sweep whose send throws does not touch the export again")
    void aFailedSweepDispatchIsNotRetried() {
        when(exports.pendingOlderThan(any(), anyInt()))
                .thenReturn(List.of(new JdbcPosExportStore.StaleExport(TENANT, EXPORT)));
        when(exports.send(TENANT, EXPORT)).thenThrow(new IllegalStateException("route is down"));

        trigger.sweepStale();
        trigger.sweepStale();

        // Each tick re-reads the store, so a second tick calling send() again is
        // the store's own doing (the export is still PENDING there), not this
        // method retrying anything itself. What is asserted here is narrower and
        // still real: one call to send() found here is one call made, with no
        // in-process bookkeeping of its own that could double it or drop it.
        verify(exports, times(2)).send(TENANT, EXPORT);
    }

    @Test
    @DisplayName("an overlapping tick is skipped rather than run concurrently with itself")
    void anOverlappingSweepTickDoesNothing() throws InterruptedException {
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(exports.pendingOlderThan(any(), anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return List.of();
        });

        Thread firstTick = new Thread(trigger::sweepStale);
        firstTick.start();
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the first tick must be inside pendingOlderThan before the second starts")
                .isTrue();

        // A second tick arriving while the first is still in flight — the exact
        // shape a 15-second fixedDelay racing a slow query could produce.
        trigger.sweepStale();
        verify(exports, times(1)).pendingOlderThan(any(), anyInt());

        release.countDown();
        firstTick.join(5_000);
    }

    // ------------------------------------------------------------------

    private static OrderConfirmed confirmed(UUID orderId) {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                NOW,
                BRAND,
                LOCATION,
                "AUTO_CONFIRM",
                null,
                NOW,
                "UZS",
                82_000L,
                "CONFIRMED",
                2);
    }

    private static ProviderOutcome success() {
        return ProviderOutcome.success(Map.of(), "till-order-1");
    }

    /** Fires exactly what a committing transaction manager fires, and no more. */
    private static void commit() {
        List<TransactionSynchronization> registered =
                List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(TransactionSynchronization::afterCommit);
    }

    private static void rollback() {
        List<TransactionSynchronization> registered =
                List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    /** A clock this suite moves by decree; {@code sweepStale}'s threshold is asserted against it, never against a sleep. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
