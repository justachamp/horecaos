package uz.horecaos.platform.fulfillment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore.ClaimedJob;

/**
 * The durable scheduler behind ADR 0014's two-hour preparation order.
 *
 * <p>PostgreSQL and not a Kafka delayed message, which ADR 0014 rejects by name:
 * the delay is approximate, invisible to an operator, and cannot be cancelled or
 * rescheduled when the kitchen changes its estimate. A row in
 * {@code delivery_sourcing_jobs} can be all three.
 *
 * <p>The claim is {@code OutboxRelay}'s, deliberately — {@code FOR UPDATE SKIP
 * LOCKED}, a lease with a token, and a batch — because a second pattern for the
 * same problem is a second chance to get it subtly wrong. What differs is what
 * happens to a job that is claimed twice, and the answer is nothing: the lease
 * only decides who does the work, and whether the work is safe to repeat is
 * settled a layer down by {@link DeliverySourcingService}'s derived idempotency
 * keys and V0054's unique indexes. A scheduler is not allowed to be the thing
 * that stops two couriers being booked, because a scheduler cannot be.
 *
 * <p>Switchable off by property, which is ADR 0014's stated rollback position:
 * automated sourcing off, plans, quotes, attempts and reconciliation evidence
 * preserved, operations assigning by hand.
 */
@Component
@ConditionalOnProperty(name = "horecaos.fulfillment.sourcing.enabled", havingValue = "true", matchIfMissing = true)
public class DeliverySourcingScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliverySourcingScheduler.class);

    private final JdbcSourcingJobStore jobs;
    private final DeliverySourcingRunner runner;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final String workerId;
    private final Counter sourcedCounter;
    private final Counter failedCounter;

    /**
     * One tick at a time in this process, for the reason the outbox relay has the
     * same flag: the poll interval is shorter than a worst-case batch, and two
     * overlapping polls in one JVM would double the work without adding a worker.
     */
    private final AtomicBoolean running = new AtomicBoolean();

    public DeliverySourcingScheduler(
            JdbcSourcingJobStore jobs,
            DeliverySourcingRunner runner,
            Clock clock,
            MeterRegistry meterRegistry,
            // A small batch and a short lease, which is the opposite of the
            // outbox's trade-off and deliberately so. A batch is claimed under one
            // lease, so the lease has to outlast the whole batch — and every
            // second of lease is a second a dead worker's order waits before
            // anybody else may touch it. An event published a minute late is a
            // late event; a courier sourced a minute late is a cold delivery. The
            // poll runs every second, so five per tick is three hundred a minute
            // and the batch is not the throughput limit.
            @Value("${horecaos.fulfillment.sourcing.batch-size:5}") int batchSize,
            @Value("${horecaos.fulfillment.sourcing.lease-duration:3m}") Duration lease,
            @Value("${horecaos.fulfillment.sourcing.tick-timeout:20s}") Duration tickTimeout,
            @Value("${spring.application.name:horecaos-platform}") String applicationName) {

        if (batchSize < 1) {
            throw new IllegalArgumentException("A sourcing batch size must be positive");
        }
        Duration worstCaseBatch = tickTimeout.multipliedBy(batchSize).plusSeconds(5);
        if (lease.compareTo(worstCaseBatch) < 0) {
            // A lease shorter than the batch it covers expires while the batch is
            // still running, and a second worker then re-runs a tick whose partner
            // call is in flight. The idempotency key saves it; the alert nobody
            // gets does not.
            throw new IllegalArgumentException("The sourcing lease must outlast the batch's worst-case tick time");
        }
        this.jobs = jobs;
        this.runner = runner;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
        // Which process holds a lease. A pod name where one exists, so an operator
        // reading a stuck job can find the worker rather than a random uuid.
        this.workerId = applicationName + "@" + hostName();
        this.sourcedCounter = meterRegistry.counter("horecaos.delivery.sourcing.ticks", "outcome", "decided");
        this.failedCounter = meterRegistry.counter("horecaos.delivery.sourcing.ticks", "outcome", "failed");
    }

    @Scheduled(fixedDelayString = "${horecaos.fulfillment.sourcing.poll-interval:1s}")
    public void sourceScheduledBatch() {
        sourceOnce();
    }

    /**
     * Claims and sources one batch of due jobs.
     *
     * @return how many jobs this poll claimed, which is not how many succeeded
     */
    public int sourceOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<ClaimedJob> claimed = jobs.claim(clock.instant(), lease, batchSize, workerId);
            claimed.forEach(this::tick);
            return claimed.size();
        } finally {
            running.set(false);
        }
    }

    private void tick(ClaimedJob job) {
        try {
            runner.run(job);
            sourcedCounter.increment();
        } catch (RuntimeException failure) {
            failedCounter.increment();
            // The lease is left to expire rather than released. A tick that threw
            // may have called a partner, and handing the job straight back to the
            // next poll would repeat that call before anybody has looked at why —
            // the lease is the pause in which the attempt row can be read.
            //
            // The plan id and nothing else: a delivery failure's most tempting log
            // line is the address it could not reach.
            log.error("Sourcing tick failed for plan {}; its lease will expire", job.planId(), failure);
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException unknown) {
            return "unknown-host";
        }
    }
}
