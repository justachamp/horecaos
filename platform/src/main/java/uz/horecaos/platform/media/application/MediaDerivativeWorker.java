package uz.horecaos.platform.media.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.media.domain.DecodeError;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore.ClaimedJob;

/**
 * Drains {@code media.derivative_jobs} (ADR 0010, V0065).
 *
 * <p><b>Why the render is here and not in an inbox handler.</b> ADR 0010 asks
 * for derivatives "asynchronously through outbox/Kafka/inbox", and the fact half
 * of that is built: reaching {@code AVAILABLE} appends a {@code
 * MediaAssetAvailable} outbox row in the same transaction and the relay
 * publishes it. The <em>work</em> half cannot be an {@code InboxHandler},
 * because {@code InboxExecutor} runs a handler inside the transaction that
 * writes its {@code PROCESSED} row — that is the whole guarantee of ADR 0005 —
 * and rendering is an object-store read, a decode and three object writes.
 * A handler doing that holds one of ten pooled connections for the length of a
 * download and a decode, which is the failure {@code
 * ExternalCallTransactionBoundaryTests} exists to prevent and which the {@code
 * InboxHandler} contract forbids in as many words.
 *
 * <p>So the durable trigger is a job row written with the availability
 * transition — one statement, no network, safe inside that transaction — and the
 * work runs here, under a lease, with no transaction open across any of it. That
 * is V0054's shape for scheduled delivery sourcing, for the same reasons and
 * with the same claim query.
 *
 * <p>A second consequence worth having: derivatives do not depend on Kafka being
 * up. A broker outage delays the fact reaching other modules; it does not leave
 * a menu without thumbnails.
 *
 * <p>Switchable off by property, which is the rollback position: rendering
 * stops, jobs accumulate as evidence of what is owed, and the storefront falls
 * back to originals as it must anyway for formats the JDK cannot decode.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.media.derivatives.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MediaDerivativeWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaDerivativeWorker.class);

    /** The asset is gone or no longer displayable. Nothing will ever come of retrying. */
    private static final String ASSET_NOT_RENDERABLE = "ASSET_NOT_RENDERABLE";

    /** The original could not be read back from the object store. */
    private static final String SOURCE_UNREADABLE = "SOURCE_UNREADABLE";

    private static final String RENDER_FAILED = "RENDER_FAILED";

    /**
     * A decoder asked for memory and did not get it.
     *
     * <p>Its own code and not the generic one, because it is the one failure an
     * operator can act on without reading the asset: either the heap is too
     * small for {@code ImageCostLimits.MAX_DECODED_BYTES} or that ceiling is too
     * generous for this deployment. Restated here rather than imported from the
     * renderer, which is infrastructure this package does not reach into; the
     * value has to match, and the test that abandons a starved render asserts
     * the string.
     */
    private static final String RENDER_OUT_OF_MEMORY = "RENDER_OUT_OF_MEMORY";

    /**
     * A previous attempt left without settling its own job.
     *
     * <p>Nothing inside this class leaves a job unsettled any more: every tick
     * ends in a completion, a reschedule or an abandonment, and a tick that
     * unwinds on an {@code Error} settles its own job first and releases the
     * rest of its batch. What can still reach here is the process being taken
     * away between the claim and the settlement — a container killed by the
     * kernel's OOM killer, a machine losing power, {@code autoheal} recreating
     * the container while a render is in flight. The lease then expires and the
     * job is claimed again, which is correct once and a loop forever, so the
     * claim's own attempt count is checked before any work starts.
     */
    private static final String ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";

    private final JdbcDerivativeJobStore jobs;
    private final MediaDerivativeService derivatives;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;
    private final String workerId;
    private final Counter rendered;
    private final Counter abandoned;
    private final Counter retried;

    /**
     * One tick at a time in this process, for the reason the outbox relay has
     * the same flag: a batch of decodes can outlast the poll interval, and two
     * overlapping polls in one JVM would double the CPU without adding a worker.
     */
    private final AtomicBoolean running = new AtomicBoolean();

    public MediaDerivativeWorker(
            JdbcDerivativeJobStore jobs,
            MediaDerivativeService derivatives,
            Clock clock,
            MeterRegistry meters,
            // A small batch. Each job is a download, a decode and three
            // rescales, so a
            // batch is claimed under one lease and the lease has to outlast the
            // whole of it — and every second of lease is a second a dead
            // worker's asset waits before anybody else may pick it up.
            @Value("${horecaos.media.derivatives.batch-size:4}") int batchSize,
            @Value("${horecaos.media.derivatives.lease-duration:5m}") Duration lease,
            @Value("${horecaos.media.derivatives.initial-backoff:30s}") Duration initialBackoff,
            @Value("${horecaos.media.derivatives.max-backoff:15m}") Duration maximumBackoff,
            @Value("${horecaos.media.derivatives.max-attempts:6}") int maximumAttempts,
            @Value("${spring.application.name:horecaos-platform}") String applicationName) {

        if (batchSize < 1 || maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "A derivative batch size and attempt limit must be positive");
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()
                || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "Derivative retry delays must be positive and consistently ordered");
        }
        this.jobs = jobs;
        this.derivatives = derivatives;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
        // Which process holds a lease. A pod name where there is one, so an
        // operator reading a stuck job finds a worker rather than a random uuid.
        this.workerId = applicationName + "@" + hostName();
        this.rendered = meters.counter("horecaos.media.derivative.jobs", "outcome", "rendered");
        this.abandoned = meters.counter("horecaos.media.derivative.jobs", "outcome", "abandoned");
        this.retried = meters.counter("horecaos.media.derivative.jobs", "outcome", "retried");
    }

    @Scheduled(fixedDelayString = "${horecaos.media.derivatives.poll-interval:2s}")
    public void renderScheduledBatch() {
        renderOnce();
    }

    /** @return how many jobs this poll claimed, which is not how many succeeded */
    public int renderOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<ClaimedJob> claimed = jobs.claim(clock.instant(), lease, batchSize, workerId);

            // Every job settles itself, so one bad asset costs its own job and
            // nothing else in the batch. What this loop adds is the case where
            // a tick does not return at all: a process-fatal Error, or a failure
            // of the settlement itself when the database has gone away.
            //
            // The jobs behind it in the batch are the ones that used to pay for
            // that. They stayed LEASED with an attempt already spent, waited out
            // a five-minute lease, were re-claimed, spent another, and after six
            // rounds were abandoned as ATTEMPTS_EXHAUSTED having never been
            // rendered once. They are handed back untouched instead — attempt
            // refunded, due immediately — so the next poll, in this process or
            // the one that replaces it, finds them exactly as the claim found
            // them.
            //
            // The failure is then rethrown rather than swallowed, which is what
            // puts it in front of the scheduler's error handler. That handler is
            // the only thing on the platform that can act on a process-fatal
            // Error: it marks the process unhealthy so the readiness probe fails
            // and the container is restarted. Rethrowing does not, and never
            // did, end this process by itself.
            for (int index = 0; index < claimed.size(); index++) {
                try {
                    tick(claimed.get(index));
                } catch (RuntimeException | Error interrupted) {
                    releaseUntouched(claimed.subList(index + 1, claimed.size()));
                    throw interrupted;
                }
            }
            return claimed.size();
        } finally {
            running.set(false);
        }
    }

    /**
     * Hands back the jobs this batch never reached.
     *
     * <p>Best effort, one row at a time, and a failure to release one must not
     * stop the next: the reason this method is running is that something already
     * went wrong, and if that something is the database then none of these will
     * succeed and the leases expiring is the fallback that was always there.
     * What this buys over the fallback is the attempt refund and the five
     * minutes, and both are worth having when they can be had.
     */
    private void releaseUntouched(List<ClaimedJob> untouched) {
        if (untouched.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (ClaimedJob job : untouched) {
            try {
                jobs.release(job.jobId(), job.leaseToken(), now, now);
            } catch (RuntimeException | Error secondary) {
                log.error("Could not release derivative job for media asset {} after an "
                                + "interrupted batch ({}); its lease expires in {} instead",
                        job.assetId(), secondary.getClass().getSimpleName(), lease);
            }
        }
        log.warn("A derivative batch was interrupted; {} claimed job(s) were handed back "
                + "unattempted rather than left leased", untouched.size());
    }

    /**
     * One claimed job, and every path out of here ends the lease.
     *
     * <p>A tick that returned without completing, rescheduling or abandoning
     * would leave a job that only becomes claimable again when its lease expires
     * — correct, but it turns a code path nobody thought about into an image
     * that is five minutes late. Worse than late, it turns out: nothing else
     * consults {@code maximumAttempts}, so a job that leaves this method
     * unsettled is re-claimed on every lease expiry for as long as the row
     * exists.
     */
    private void tick(ClaimedJob job) {
        Instant now = clock.instant();

        if (job.attemptCount() > maximumAttempts) {
            // The claim already spent an attempt on this job, and the budget was
            // gone before it did. Reaching here means an earlier tick left
            // without settling — see the Error path below — so the job is ended
            // here rather than given a render it has no budget for. This is what
            // makes "a job cannot be re-claimed indefinitely" true on every
            // path, not only on the paths that throw something catchable.
            terminate(job, ATTEMPTS_EXHAUSTED, now, null);
            return;
        }

        try {
            MediaDerivativeService.DerivativeReport report =
                    derivatives.renderMissing(job.tenantId(), job.assetId());

            // Completed even when nothing was produced, and that is the
            // decision rather than an oversight — but only for the outcomes the
            // service reports as settled. The ways to render nothing and mean
            // it are an original in a format the JDK cannot decode, a source
            // whose header says the decode is more than this platform will pay
            // for, and an asset that is no longer displayable. None improves on
            // a retry, and the storefront's fallback to the original is what
            // the absence of a derivative row already tells it. A render that
            // *failed* is not among them and no longer arrives here: it leaves
            // MediaDerivativeService as a DerivativeRenderFailedException.
            if (!jobs.complete(job.jobId(), job.leaseToken(), now)) {
                // The lease was lost, so this render outlasted it and somebody
                // else owns the job now. Not counted as rendered: whatever this
                // process did, the row's outcome is the other worker's to write.
                log.warn("Derivative job for media asset {} outlived its lease; "
                        + "another worker owns the outcome", job.assetId());
                return;
            }
            rendered.increment();

            if (!report.created().isEmpty()) {
                log.info("Rendered {} derivative(s) for media asset {}",
                        report.created().size(), job.assetId());
            } else if (report.sourceUnsupported()) {
                log.info("Media asset {} has no renderable derivative ({}); "
                        + "the original stands alone", job.assetId(), report.unsupportedReason());
            }
        } catch (IllegalArgumentException gone) {
            // The asset does not exist for this tenant any more. A deletion
            // request between the availability transition and this tick is the
            // ordinary cause, and it is terminal: there is nothing to render and
            // there never will be.
            terminate(job, ASSET_NOT_RENDERABLE, now, gone);
        } catch (DerivativeRenderFailedException failed) {
            // The renderer decoded or encoded and did not finish. Its own code
            // rather than a generic one, because RENDER_OUT_OF_MEMORY and
            // ENCODE_FAILED ask an operator for entirely different things.
            retryOrAbandon(job, failed.errorCode(), now, failed);
        } catch (IllegalStateException unreadable) {
            // The original could not be read back. Usually transient — a
            // degraded object store — so it retries; if the object is genuinely
            // gone the attempt limit turns it into an abandoned job rather than
            // a loop.
            retryOrAbandon(job, SOURCE_UNREADABLE, now, unreadable);
        } catch (RuntimeException failure) {
            retryOrAbandon(job, RENDER_FAILED, now, failure);
        } catch (Error fatal) {
            // The path that used to spin. An image decoder is the one place
            // where an untrusted party sizes an allocation, and JPEGImageReader
            // lets the resulting OutOfMemoryError out as an Error rather than
            // wrapping it: none of the catches above see it, tick returns
            // without settling, and the job is re-claimed on every lease expiry
            // forever, its attempt count climbing past a limit nothing on that
            // path consults.
            //
            // The job is settled first and unconditionally — an attempt spent
            // and a backoff, or an abandonment once the budget is gone. That is
            // deliberate even for an error this asset probably did not cause: if
            // it did cause it, a released job would be re-claimed by the process
            // that replaces this one, kill that too, and turn a bad upload into
            // a crash loop no restart can leave. Spending the attempt bounds it
            // at six restarts and then abandons the asset with its code, which
            // is a thumbnail missing rather than a platform down.
            //
            // Then the decision. DecodeError says which errors are a property of
            // this input — a failed heap allocation, an exhausted thread stack —
            // and which say the process itself is finished. The first are
            // swallowed and the batch carries on. The rest are rethrown, which
            // releases the rest of this batch in renderOnce and puts the error
            // in front of the scheduler's error handler; ProcessHealth is what
            // reads it there and refuses traffic so the container is restarted.
            // The rethrow does not end this process — nothing thrown from a
            // scheduled method does — it delivers the error to the one place
            // that can act on it.
            settleAfterFatalError(job, now, fatal);
            if (!DecodeError.isRecoverable(fatal)) {
                throw fatal;
            }
        }
    }

    /**
     * Ends a job's lease when the tick is unwinding on an {@link Error}.
     *
     * <p>Best effort by construction: on a doomed JVM the database call may
     * fail too, and a secondary failure here must not replace the error that is
     * on its way up — that error is the one the scheduler's handler has to
     * classify, and swapping it for a JDBC exception would turn a restart into
     * another two-second log line. The lease expiring would eventually free the
     * job anyway; this only makes the record honest while the row is still
     * writable.
     */
    private void settleAfterFatalError(ClaimedJob job, Instant now, Error fatal) {
        // The same code an OutOfMemoryError gets when a decoder wraps it, so
        // that one condition reads as one condition whichever of the JDK's two
        // readers produced it. An operator seeing RENDER_OUT_OF_MEMORY should
        // not have to know which format the asset was.
        String errorCode = fatal instanceof OutOfMemoryError
                ? RENDER_OUT_OF_MEMORY : RENDER_FAILED;
        try {
            retryOrAbandon(job, errorCode, now, fatal);
        } catch (RuntimeException | Error secondary) {
            log.error("Could not settle derivative job for media asset {} while unwinding ({})",
                    job.assetId(), secondary.getClass().getSimpleName());
        }
    }

    private void retryOrAbandon(ClaimedJob job, String errorCode, Instant now,
            Throwable failure) {
        if (job.attemptCount() >= maximumAttempts) {
            terminate(job, errorCode, now, failure);
            return;
        }
        jobs.reschedule(job.jobId(), job.leaseToken(), now.plus(backoffAfter(job.attemptCount())),
                errorCode, now);
        retried.increment();
        // The asset id and the code. Never the filename, never the object key:
        // a decoder's own message is the one place an uploaded name has been
        // seen to reach a log nobody was reading.
        log.warn("Derivative render for media asset {} failed on attempt {} ({})",
                job.assetId(), job.attemptCount(), errorCode);
    }

    private void terminate(ClaimedJob job, String errorCode, Instant now, Throwable cause) {
        jobs.abandon(job.jobId(), job.leaseToken(), errorCode, now);
        abandoned.increment();
        // The cause is attached here and not on the retry path above, because
        // this is the last anybody hears of the job and the stack is the only
        // thing that will explain it. What reaches here is an object-store, a
        // database or an allocation failure — the renderer turns decoder
        // exceptions into codes itself — so the message names keys and
        // identifiers, never a customer.
        log.error("Giving up on derivatives for media asset {} after {} attempt(s): {}",
                job.assetId(), job.attemptCount(), errorCode, cause);
    }

    /**
     * Bounded exponential backoff with equal jitter.
     *
     * <p>Written here rather than borrowed from {@code integration.retry}: that
     * package is internal to the integration module and media may not import it,
     * and exposing it as a named interface to save eight lines would widen a
     * module boundary for a convenience. The jitter is the part that matters —
     * undithered backoff is a function of the attempt count alone, so every
     * worker that failed against the same degraded object store wakes at the
     * same instant and re-forms the burst.
     */
    private Duration backoffAfter(int attempt) {
        long ceiling = Math.min(
                maximumBackoff.toMillis(),
                initialBackoff.toMillis() * (1L << Math.min(attempt, 20)));
        long half = ceiling / 2;
        return Duration.ofMillis(half + (long) (ThreadLocalRandom.current().nextDouble() * half));
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException unknown) {
            return "unknown-host";
        }
    }
}
