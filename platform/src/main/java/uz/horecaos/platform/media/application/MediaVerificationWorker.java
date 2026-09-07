package uz.horecaos.platform.media.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcVerificationJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcVerificationJobStore.ClaimedJob;

/**
 * Drains {@code media.verification_jobs} (ADR 0010, V0183).
 *
 * <p><b>What this replaces.</b> {@code finalizeUpload} used to verify an upload
 * inline: a {@code HeadObject}, a ranged read of at most 128KB, and header
 * arithmetic, all on the request thread that called it. ADR 0010 asked for a
 * separate asynchronous validation worker from the start — that request-thread
 * check was a stated departure from the record, not the design. This is that
 * worker: a durable job written by {@link MediaAssetService#finalizeUpload}
 * when the client claims its upload complete, and drained here, under a lease,
 * with no transaction open across the object-store round trip.
 *
 * <p>Structurally this is {@link MediaDerivativeWorker} again — the claim, the
 * lease, the batch-interrupted release, the retry-then-abandon budget — because
 * it is {@code JdbcDerivativeJobStore}'s shape again, which is {@code
 * JdbcSourcingJobStore}'s (V0054). A third worker with a fourth hand-rolled
 * claim loop would be a fourth chance to get {@code FOR UPDATE SKIP LOCKED}
 * subtly wrong, and none of the differences between rendering a thumbnail and
 * verifying an upload reach as far as the claim.
 *
 * <p><b>Why this one does not need {@code DecodeError}'s classification.</b>
 * The derivative worker decodes a whole raster whose allocation size an
 * attacker's declared dimensions drive directly, which is exactly how a
 * three-hundred-kilobyte PNG turns into a three-hundred-megabyte decode and an
 * {@code OutOfMemoryError} that is a property of that one input. Verification
 * never decodes anything: {@code HeadObject} is metadata, and the ranged read
 * is capped at {@code ImageProbe.PROBE_BYTES} (128KB) regardless of what the
 * object claims to be. There is no asset-shaped path to exhausting this
 * process's memory here, so every {@link Error} this worker catches is treated
 * as a process problem rather than an input problem: the job is settled — an
 * attempt spent, or an abandonment once the budget is gone — and the error is
 * unconditionally rethrown, which is what puts it in front of the scheduler's
 * error handler and, through it, the readiness probe.
 */
@Component
@ConditionalOnProperty(name = "horecaos.media.verification.enabled", havingValue = "true", matchIfMissing = true)
public class MediaVerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaVerificationWorker.class);

    /** The asset no longer belongs to this tenant, or is not awaiting verification. Nothing will ever come of retrying. */
    private static final String ASSET_NOT_VERIFIABLE = "ASSET_NOT_VERIFIABLE";

    private static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";

    /**
     * A previous attempt left without settling its own job — the process was
     * taken away between the claim and the settlement. See {@code
     * MediaDerivativeWorker}'s own doc on the same code; the reasoning is
     * unchanged here.
     */
    private static final String ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";

    private final JdbcVerificationJobStore jobs;
    private final MediaAssetService media;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;
    private final String workerId;
    private final Counter verified;
    private final Counter abandoned;
    private final Counter retried;

    /**
     * One tick at a time in this process, for the reason the derivative worker
     * and the outbox relay have the same flag: a batch can outlast the poll
     * interval, and two overlapping polls in one JVM would double the
     * object-store traffic without adding a worker.
     */
    private final AtomicBoolean running = new AtomicBoolean();

    public MediaVerificationWorker(
            JdbcVerificationJobStore jobs,
            MediaAssetService media,
            Clock clock,
            MeterRegistry meters,
            // Larger than the derivative worker's and leased for less time: a
            // verification is a head and a bounded ranged read, not a decode
            // and three re-encodes, so more fit in a lease and the lease can be
            // shorter without risking a live one expiring mid-batch.
            @Value("${horecaos.media.verification.batch-size:8}") int batchSize,
            @Value("${horecaos.media.verification.lease-duration:2m}") Duration lease,
            @Value("${horecaos.media.verification.initial-backoff:15s}") Duration initialBackoff,
            @Value("${horecaos.media.verification.max-backoff:10m}") Duration maximumBackoff,
            @Value("${horecaos.media.verification.max-attempts:6}") int maximumAttempts,
            @Value("${spring.application.name:horecaos-platform}") String applicationName) {

        if (batchSize < 1 || maximumAttempts < 1) {
            throw new IllegalArgumentException("A verification batch size and attempt limit must be positive");
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero() || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Verification retry delays must be positive and consistently ordered");
        }
        this.jobs = jobs;
        this.media = media;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
        this.workerId = applicationName + "@" + hostName();
        this.verified = meters.counter("horecaos.media.verification.jobs", "outcome", "verified");
        this.abandoned = meters.counter("horecaos.media.verification.jobs", "outcome", "abandoned");
        this.retried = meters.counter("horecaos.media.verification.jobs", "outcome", "retried");
    }

    @Scheduled(fixedDelayString = "${horecaos.media.verification.poll-interval:1s}")
    public void verifyScheduledBatch() {
        verifyOnce();
    }

    /**
     * Claims and works one batch.
     *
     * @return how many jobs this poll claimed, which is not how many verified successfully
     */
    public int verifyOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<ClaimedJob> claimed = jobs.claim(clock.instant(), lease, batchSize, workerId);

            // Every job settles itself, so one bad asset costs its own job and
            // nothing else in the batch. What this loop adds is the case where a
            // tick does not return at all — see MediaDerivativeWorker.renderOnce
            // for the full reasoning, which applies here unchanged: the jobs
            // behind it in the batch are handed back untouched rather than left
            // LEASED to wait out a lease and spend an attempt on work nobody did.
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

    /** Hands back the jobs this batch never reached. See {@code MediaDerivativeWorker}'s twin for the reasoning. */
    private void releaseUntouched(List<ClaimedJob> untouched) {
        if (untouched.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (ClaimedJob job : untouched) {
            try {
                jobs.release(job.jobId(), job.leaseToken(), now, now);
            } catch (RuntimeException | Error secondary) {
                log.error(
                        "Could not release verification job for media asset {} after an "
                                + "interrupted batch ({}); its lease expires in {} instead",
                        job.assetId(),
                        secondary.getClass().getSimpleName(),
                        lease);
            }
        }
        log.warn(
                "A verification batch was interrupted; {} claimed job(s) were handed back "
                        + "unattempted rather than left leased",
                untouched.size());
    }

    /**
     * One claimed job, and every path out of here ends the lease.
     *
     * <p>A crash between the claim and any of these branches is exactly the
     * state the lease exists to resume: the row stays {@code LEASED} with a
     * deadline, {@code leased_until} passes, and the next poll — in this
     * process or the one that replaces it — claims it again with the attempt
     * count as the only sign anything was tried before.
     */
    private void tick(ClaimedJob job) {
        Instant now = clock.instant();

        if (job.attemptCount() > maximumAttempts) {
            // The claim already spent an attempt on this job, and the budget was
            // gone before it did — see MediaDerivativeWorker's identical guard.
            terminate(job, ATTEMPTS_EXHAUSTED, now, null);
            return;
        }

        try {
            MediaAssetStatus outcome = media.verifyUpload(job.tenantId(), job.assetId());

            if (!jobs.complete(job.jobId(), job.leaseToken(), now)) {
                // The lease was lost, so this verification outlasted it and
                // somebody else owns the job now. Not counted as verified:
                // whatever this process did, the row's outcome is the other
                // worker's to write. media.verifyUpload is itself idempotent, so
                // no harm was done — only wasted work.
                log.warn(
                        "Verification job for media asset {} outlived its lease; another worker owns the outcome",
                        job.assetId());
                return;
            }
            verified.increment();
            log.info("Media asset {} verification settled as {}", job.assetId(), outcome);
        } catch (IllegalArgumentException gone) {
            // The asset no longer belongs to this tenant. A deletion between
            // finalize and this tick is the ordinary cause, and it is terminal.
            terminate(job, ASSET_NOT_VERIFIABLE, now, gone);
        } catch (IllegalStateException wrongState) {
            // media.verifyUpload's own doc: a job should only ever name an
            // UPLOADED asset. Reaching this means that invariant broke
            // elsewhere, and retrying will not change the asset's status, so
            // this is terminal rather than backed off.
            terminate(job, ASSET_NOT_VERIFIABLE, now, wrongState);
        } catch (RuntimeException failure) {
            // An object-store fault is the ordinary cause — a degraded MinIO
            // refusing a HeadObject or a ranged read — and is transient, so it
            // retries; the attempt limit turns a genuinely broken object into
            // an abandoned job rather than a loop.
            retryOrAbandon(job, VERIFICATION_FAILED, now, failure);
        } catch (Error fatal) {
            // See this class's own doc for why every Error here is treated as a
            // process problem and not an input problem: nothing about verifying
            // a bounded head and a 128KB ranged read scales with an attacker's
            // choices the way a decode does. Settled first so a doomed process
            // does not also strand the job, then rethrown unconditionally.
            try {
                retryOrAbandon(job, VERIFICATION_FAILED, now, fatal);
            } catch (RuntimeException | Error secondary) {
                log.error(
                        "Could not settle verification job for media asset {} while unwinding ({})",
                        job.assetId(),
                        secondary.getClass().getSimpleName());
            }
            throw fatal;
        }
    }

    private void retryOrAbandon(ClaimedJob job, String errorCode, Instant now, Throwable failure) {
        if (job.attemptCount() >= maximumAttempts) {
            terminate(job, errorCode, now, failure);
            return;
        }
        jobs.reschedule(job.jobId(), job.leaseToken(), now.plus(backoffAfter(job.attemptCount())), errorCode, now);
        retried.increment();
        log.warn(
                "Verification for media asset {} failed on attempt {} ({})",
                job.assetId(),
                job.attemptCount(),
                errorCode);
    }

    private void terminate(ClaimedJob job, String errorCode, Instant now, @Nullable Throwable cause) {
        jobs.abandon(job.jobId(), job.leaseToken(), errorCode, now);
        abandoned.increment();
        log.error(
                "Giving up on verification for media asset {} after {} attempt(s): {}",
                job.assetId(),
                job.attemptCount(),
                errorCode,
                cause);
    }

    /**
     * Bounded exponential backoff with equal jitter. Identical in shape to
     * {@code MediaDerivativeWorker}'s, and duplicated for the same reason that
     * one is not shared from {@code integration.retry}: the module may not
     * import it, and exposing it to save a dozen lines would widen a boundary
     * for a convenience.
     */
    private Duration backoffAfter(int attempt) {
        long ceiling = Math.min(maximumBackoff.toMillis(), initialBackoff.toMillis() * (1L << Math.min(attempt, 20)));
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
