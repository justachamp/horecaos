package uz.qoida.platform.tenancy.application.onboarding;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Drives onboarding across replicas (ADR 0008).
 *
 * <p>Every replica polls. {@code FOR UPDATE SKIP LOCKED} means they take
 * different runs rather than contending for the same one, and the per-step claim
 * token inside {@link OnboardingService} means that even if two replicas somehow
 * reach the same step, only the one holding the token can complete it. Kafka is
 * deliberately not the timer: it cannot be queried, cancelled, or explained per
 * tenant.
 *
 * <p>Stuck runs are surfaced as gauges here and alerted on by
 * {@code infra/observability/qoida-probe.sh}, which is where every ADR 0023
 * threshold lives. A run waiting on platform approval is not stuck, and the
 * gauge this class publishes is careful to exclude it: alerting on a run that is
 * correctly waiting for a person would train that person to ignore the signal.
 *
 * <p>Nothing transactional lives on this class, and that is deliberate. The
 * claiming queries used to be {@code @Transactional} methods here that
 * {@link #drive()} called on itself — which skips the Spring proxy, so neither
 * ran in a transaction at all, and {@code FOR UPDATE SKIP LOCKED} in autocommit
 * releases its locks before the caller has read a row. They live on
 * {@link OnboardingService} now, where a call from here goes through the proxy
 * and the annotation means what it says.
 */
@Component
@ConditionalOnProperty(name = "qoida.onboarding.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OnboardingScheduler {

    private static final Logger log = LoggerFactory.getLogger(OnboardingScheduler.class);

    private final JdbcClient jdbc;
    private final OnboardingService onboarding;
    private final Clock clock;
    private final int batchSize;

    public OnboardingScheduler(
            JdbcClient jdbc,
            OnboardingService onboarding,
            MeterRegistry meters,
            Clock clock,
            @Value("${qoida.onboarding.scheduler.batch-size:5}") int batchSize) {
        this.jdbc = jdbc;
        this.onboarding = onboarding;
        this.clock = clock;
        this.batchSize = batchSize;

        meters.gauge("qoida.onboarding.runs.waiting", this, OnboardingScheduler::runsWaiting);
        meters.gauge("qoida.onboarding.runs.failed", this, OnboardingScheduler::runsFailed);
        meters.gauge("qoida.onboarding.runs.stalled.age.seconds", this, OnboardingScheduler::stalledAgeSeconds);
    }

    @Scheduled(
            initialDelayString = "${qoida.onboarding.scheduler.initial-delay:PT10S}",
            fixedDelayString = "${qoida.onboarding.scheduler.interval:PT5S}")
    public void drive() {
        onboarding.reclaimStaleClaims();

        for (UUID runId : onboarding.dueRuns(batchSize)) {
            try {
                // Bounded per tick so one slow run cannot starve the others.
                for (int step = 0; step < batchSize && onboarding.runNextStep(runId); step++) {
                    // Each call advances at most one step.
                }
            } catch (RuntimeException failure) {
                log.warn("Onboarding run {} failed during scheduling", runId, failure);
            }
        }
    }

    private double runsWaiting() {
        return count("status NOT IN ('ACTIVE', 'CANCELLED', 'FAILED')");
    }

    private double runsFailed() {
        return count("status = 'FAILED'");
    }

    /**
     * How long the longest-stuck run has been stuck, in seconds.
     *
     * <p>The gauge the alert reads, and it is deliberately not "how many runs are
     * waiting". A run parked on {@code TENANT_ACTIVATE} is waiting for a person
     * to approve it and is not stuck at all, so that step is excluded by name.
     * By name rather than by relying on the far-future {@code available_at}
     * {@link OnboardingService} parks it with: that parking only happens if the
     * scheduler reaches the activation step in the same tick that completes the
     * last required one, because {@link OnboardingService#dueRuns(int)} stops
     * returning a run the moment it turns {@code READY}. With the default batch
     * size it does reach it, by exactly one spare iteration; one retry along the
     * way, or a smaller configured batch, and it does not — and then a tenant
     * correctly waiting for approval ages forever and raises the one alert this
     * gauge exists to never raise.
     *
     * <p>What does enter is a run with a step that is due *now* and has not
     * moved: the scheduler is dead, every replica is failing the same step, or a
     * required step has exhausted its attempts and needs a person to resume it.
     * Each of those is the same fact — onboarding has stopped — and each is worth
     * someone's attention within the working day.
     *
     * <p>Zero when nothing is stuck, so an absent series means the application is
     * not reporting rather than that all is well.
     */
    private double stalledAgeSeconds() {
        try {
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            Double oldest = jdbc.sql("""
                    SELECT coalesce(max(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - s.updated_at))), 0)
                      FROM tenant.onboarding_steps s
                      JOIN tenant.onboarding_runs r ON r.id = s.run_id
                     WHERE r.status NOT IN ('ACTIVE', 'CANCELLED')
                       AND s.step_key <> 'TENANT_ACTIVATE'
                       AND s.status IN ('PENDING', 'FAILED')
                       AND s.available_at <= CAST(:now AS timestamptz)
                    """).param("now", now).query(Double.class).single();
            return oldest == null ? 0 : oldest;
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private double count(String predicate) {
        try {
            return jdbc.sql("SELECT count(*) FROM tenant.onboarding_runs WHERE " + predicate)
                    .query(Long.class).single();
        } catch (RuntimeException unavailable) {
            // A gauge must never take the application down with it.
            return -1;
        }
    }
}
