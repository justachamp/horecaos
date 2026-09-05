package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * What puts a confirmed order in front of a till (ADR 0011, ADR 0019).
 *
 * <p>{@link PosOrderExportService} was built with no caller, so until now every
 * kitchen ticket was somebody retyping an order from a screen. This class is the
 * caller, and it is split across two phases because the two halves of an export
 * have opposite requirements.
 *
 * <p><strong>Opening the export runs in the confirming transaction.</strong>
 * {@link TransactionPhase#BEFORE_COMMIT}, like ADR 0020's notification trigger
 * and ADR 0041's ticket opener, so the export row and the confirmation that
 * caused it commit together. There is then no window in which a restaurant has
 * committed to an order and no record exists that the till was meant to hear
 * about it — the window a customer reports as "you confirmed it and nobody
 * cooked it". {@link PosOrderExportService#open} is total by design for exactly
 * this position: a branch with no POS binding, an unmapped line, and an import
 * are all answers rather than exceptions, so the only way this can fail a
 * confirmation is a database that was failing the confirmation anyway.
 *
 * <p><strong>Sending it does not.</strong> ADR 0019 refuses to let a POS outage
 * become a customer-facing checkout outage, and two things follow. The provider
 * call must not run inside the checkout or approval transaction, where it would
 * hold a pooled connection across the network for every other module to wait on
 * (see {@code ExternalCallTransactionBoundaryTests}); and it must not run on the
 * caller's thread after commit either, where a slow till would still be the
 * response time of an operator's approve button. So the send happens on the
 * scheduler, and the confirming request is finished with the export the moment it
 * commits.
 *
 * <h2>Why one order cannot produce two tickets</h2>
 *
 * The provider has no idempotency key of any kind, so every layer here is
 * written on the assumption that a repeat is unrecoverable.
 *
 * <ol>
 *   <li>{@code open} inserts under {@code uq_pos_export_per_order}, so a
 *       redelivered {@code OrderConfirmed}, a retried command and a second worker
 *       all converge on one export row rather than one each.</li>
 *   <li>{@code send} claims that row with a conditional update naming the state
 *       it expects, so of two threads holding the same export id exactly one puts
 *       anything on the wire.</li>
 *   <li>Nothing here re-queues. A send whose outcome is unknown is settled by
 *       ADR 0011's recovery read and, failing that, by a person — never by this
 *       class trying again, because "we did not hear back" and "it did not
 *       arrive" are not the same fact.</li>
 * </ol>
 *
 * <p>The queue below is a wake-up hint and never the record of intent. The record
 * is the {@code PENDING} row, which is durable; losing a hint costs a delay, and
 * the only thing that can lose one — a process that dies between the commit and
 * the next tick, or a hint that lands in one replica's memory while another
 * replica served the confirming request — leaves an export that {@link
 * #sweepStale} picks up once the store can enumerate it. The queue therefore
 * remains a latency optimisation rather than the mechanism the guarantee rests
 * on: a confirmed order is dispatched eventually regardless of which process
 * confirmed it, because the sweep does not care who queued the hint, only that a
 * {@code PENDING} row has sat long enough that its hint — wherever it went —
 * evidently did not fire here.
 *
 * <h2>Why the sweep is safe to run on every replica, unconditionally</h2>
 *
 * It changes nothing by itself. {@link #sweepStale} only lists candidates and
 * calls {@link #dispatch}, which calls {@link PosOrderExportService#send} —
 * the exact call the fast path makes, protected by the exact same conditional
 * claim ({@code JdbcPosExportStore#claimForAttempt}) that already keeps two
 * threads in one process from sending one order twice. A second pattern for
 * one problem is a second chance to get it subtly wrong (see {@code
 * DeliverySourcingScheduler}'s own reasoning for reusing {@code OutboxRelay}'s
 * claim rather than inventing one); this reuses {@code send} itself; there is
 * no second pattern here at all.
 */
@Component
@ConditionalOnProperty(name = "horecaos.pos.export.auto-dispatch", havingValue = "true", matchIfMissing = true)
public class PosOrderExportTrigger {

    private static final Logger log = LoggerFactory.getLogger(PosOrderExportTrigger.class);

    private final PosOrderExportService exports;
    private final Clock clock;
    private final Queue<Dispatch> pending = new ConcurrentLinkedQueue<>();
    private final int queueLimit;
    private final int batchSize;
    private final Duration sweepStaleAfter;
    private final int sweepBatchSize;

    /**
     * One sweep tick at a time in this process, for the reason {@code
     * OutboxRelay} and {@code DeliverySourcingScheduler} guard their own ticks:
     * the poll interval can be shorter than a worst-case batch against a slow
     * till, and two overlapping sweeps in one JVM would double the work rather
     * than add a worker.
     */
    private final AtomicBoolean sweeping = new AtomicBoolean();

    public PosOrderExportTrigger(
            PosOrderExportService exports,
            Clock clock,
            @Value("${horecaos.pos.export.dispatch-queue-limit:10000}") int queueLimit,
            @Value("${horecaos.pos.export.dispatch-batch:50}") int batchSize,
            // Longer than the fast path's dispatch-interval on purpose: under
            // ordinary single-replica operation the in-process hint always wins
            // the race, so this threshold is what keeps the sweep a backstop
            // rather than a second, competing dispatch path that just adds noisy
            // "claimed elsewhere" outcomes to the log.
            @Value("${horecaos.pos.export.sweep-stale-after:PT15S}") Duration sweepStaleAfter,
            @Value("${horecaos.pos.export.sweep-batch:50}") int sweepBatchSize) {
        this.exports = exports;
        this.clock = clock;
        this.queueLimit = queueLimit;
        this.batchSize = batchSize;
        this.sweepStaleAfter = sweepStaleAfter;
        this.sweepBatchSize = sweepBatchSize;
    }

    /**
     * Opens the export for an order the platform has just committed to.
     *
     * <p>The dispatch hint is registered as an after-commit synchronization rather
     * than pushed from here, so a confirmation that rolls back — an audit failure,
     * a losing conditional update — leaves nothing queued. The callback itself
     * touches no database: after-commit is a phase where a write joins a
     * connection whose transaction is already over, and the only safe work there
     * is work that is not a write.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderConfirmed(OrderConfirmed event) {
        UUID tenantId = event.tenantId().value();

        exports.open(tenantId, event.orderId())
                .ifPresentOrElse(
                        exportId -> afterCommit(() -> hint(new Dispatch(tenantId, exportId))),
                        // Not an error, and the reason it is only debug: a branch with no
                        // POS binding takes its orders exactly as it did before there was
                        // one, and that is most branches during the pilot.
                        () -> log.debug("Order {} opened no POS export", event.orderId()));
    }

    /**
     * Sends the exports that confirmations have opened.
     *
     * <p>One order at a time and never in a transaction, so a till that has
     * stopped answering costs this method its own thread and nothing else. The
     * batch bound is what keeps a backlog from turning one tick into a long one:
     * the remainder is still queued and the next tick takes it.
     */
    @Scheduled(
            initialDelayString = "${horecaos.pos.export.dispatch-initial-delay:PT5S}",
            fixedDelayString = "${horecaos.pos.export.dispatch-interval:PT1S}")
    public void dispatchPending() {
        for (int dispatched = 0; dispatched < batchSize; dispatched++) {
            Dispatch next = pending.poll();
            if (next == null) {
                return;
            }
            dispatch(next);
        }
    }

    /**
     * The durable backstop behind the queue above (ADR 0011, ADR 0023's runtime
     * shape): {@code PENDING} exports whose hint apparently never reached this
     * process, however that happened.
     *
     * <p>This is what makes a multi-replica deployment safe rather than merely
     * quiet about failing. Before this method existed, an order confirmed on one
     * replica queued its hint only in that replica's memory; a second replica
     * running this scheduler had no way to learn the order existed, and the
     * export sat {@code PENDING} until somebody noticed a ticket never printed.
     * Every replica now runs this tick and asks the one thing every replica can
     * see — the database — rather than the one thing only the confirming
     * replica can see.
     *
     * <p>Never claims anything itself; see {@link JdbcPosExportStore#findStalePending}
     * and this class's own doc for why {@link #dispatch} alone is what makes a
     * repeat finding safe.
     */
    @Scheduled(
            initialDelayString = "${horecaos.pos.export.sweep-initial-delay:PT15S}",
            fixedDelayString = "${horecaos.pos.export.sweep-interval:PT15S}")
    public void sweepStale() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant threshold = clock.instant().minus(sweepStaleAfter);
            List<JdbcPosExportStore.StaleExport> stale = exports.pendingOlderThan(threshold, sweepBatchSize);
            if (stale.isEmpty()) {
                return;
            }
            log.info(
                    "POS export sweep found {} PENDING export(s) older than {}; dispatching from this process",
                    stale.size(),
                    sweepStaleAfter);
            stale.forEach(candidate -> dispatch(new Dispatch(candidate.tenantId(), candidate.exportId())));
        } finally {
            sweeping.set(false);
        }
    }

    /** Exports queued and not yet attempted, for a test or an operator's gauge. */
    public int queueDepth() {
        return pending.size();
    }

    private void dispatch(Dispatch dispatch) {
        try {
            ProviderOutcome outcome = exports.send(dispatch.tenantId(), dispatch.exportId());
            if (outcome.status() == ProviderOutcome.Status.SUCCESS) {
                log.debug("POS export {} reached the till", dispatch.exportId());
                return;
            }
            // Logged rather than retried, and the export row already carries the
            // state that decides what may happen next. An UNCERTAIN one is the
            // ADR 0011 recovery read's; a REJECTED one is terminal; a claim lost
            // to another worker is that worker's to finish.
            log.warn(
                    "POS export {} did not succeed: {} {}", dispatch.exportId(), outcome.status(), outcome.errorCode());
        } catch (RuntimeException failure) {
            // Deliberately not re-queued. The export is in SENT or beyond, and an
            // exception here says nothing about whether the ticket printed — which
            // is precisely the case where sending again prints a second one.
            log.error(
                    "POS export {} could not be sent; it will not be re-sent automatically",
                    dispatch.exportId(),
                    failure);
        }
    }

    private void hint(Dispatch dispatch) {
        if (pending.size() >= queueLimit) {
            log.error(
                    "The POS dispatch queue is full at {}; export {} stays PENDING and will not "
                            + "be sent until somebody resends it",
                    queueLimit,
                    dispatch.exportId());
            return;
        }
        pending.add(dispatch);
    }

    /**
     * Runs work once the confirming transaction has committed.
     *
     * <p>Registering a synchronization from inside a before-commit callback is
     * safe: the manager hands the trigger loop a copy of the list and reads the
     * after-commit set again afterwards. Where there is no transaction at all —
     * a test publishing the event directly — the work runs immediately, because
     * "no transaction" and "a transaction that will commit" have the same
     * consequence for a hint.
     */
    private static void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }

    /** One export waiting for a thread that is not somebody's request thread. */
    private record Dispatch(UUID tenantId, UUID exportId) {

        Dispatch {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(exportId, "An export id is required");
        }
    }
}
