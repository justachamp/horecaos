package uz.horecaos.platform.reporting.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * The production caller {@link DayCloseService} has never had (ADR 0043).
 *
 * <p>Without this class the service is real and tested but nothing ever invokes
 * it outside a test, so a deployed platform writes no fact row and every
 * reporting query answers empty forever — the exact gap ADR 0043's own
 * implementation checklist names, and the gap ADR 0058's supervisor and
 * control-plane digests are blocked on.
 *
 * <p>Two independent timers, matching the two operations {@link DayCloseService}
 * exposes and their different cadences ({@code FiscalObligationSweeper}'s
 * open-then-submit split is the precedent for two {@code @Scheduled} methods
 * over one workflow rather than one method doing both):
 *
 * <ul>
 *   <li>{@link #closeDueDays()} closes each tenant's business day, in order,
 *       once it has been over for {@code close-delay} (default one hour, per
 *       ADR 0043's "business-day end plus sixty minutes").
 *   <li>{@link #recutSettledDays()} re-derives an already-closed day and
 *       compares, once it has been closed for {@code settle-window} (default
 *       one day, per ADR 0043's "re-derived after twenty-four hours"). It never
 *       rewrites a stored figure — see {@link DayCloseService#recut}.
 * </ul>
 *
 * <p><b>Per-tenant, on the tenant's own business-day clock</b> ({@link
 * BusinessDayService}), not a platform-wide midnight: two tenants in different
 * timezones, or one on a non-midnight boundary, close on different wall-clock
 * schedules and this polls all of them on one timer rather than enumerating
 * cron expressions per tenant.
 *
 * <p><b>Idempotent per (tenant, day).</b> {@code close}/{@code recut} already
 * reproduce the same rows when called twice; what they do not protect against
 * is two replicas calling either at the same instant for the same day, which is
 * two concurrent delete-then-rewrite transactions racing each other. {@link
 * JdbcReportingStore#tryClaimDayClose} is the durable claim that serialises
 * that race — the same upsert-against-an-expired-lease shape ADR 0058 uses for
 * its own per-chat send lease ({@code integration.telegram_chat_locks}), applied
 * here to {@code (tenant_id, business_date, run_kind)}.
 *
 * <p><b>Bounded catch-up.</b> Each tick advances at most {@code
 * max-days-per-tick} business days per tenant so that one tenant recovering from
 * a long outage cannot starve every other tenant's tick; the next tick continues
 * from where this one stopped.
 *
 * <p>The two gauges are zero whenever nothing is behind, so an absent series
 * reads as "not reporting" rather than "all is well" — the same convention
 * {@code OnboardingScheduler.stalledAgeSeconds} uses. A control-band watcher can
 * page on either crossing a threshold without knowing which tenant is stuck; the
 * tenant is found from the logs, which carry it, never from a per-tenant metric
 * label (ADR 0023/0033 — tenant identifiers are unbounded cardinality).
 */
@Component
@ConditionalOnProperty(name = "horecaos.reporting.dayclose.enabled", havingValue = "true", matchIfMissing = true)
public class DayCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(DayCloseScheduler.class);

    private static final String CLOSE = "CLOSE";
    private static final String RECUT = "RECUT";

    private final JdbcReportingStore store;
    private final DayCloseService dayClose;
    private final BusinessDayService businessDays;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Duration closeDelay;
    private final Duration settleWindow;
    private final Duration leaseDuration;
    private final int maxDaysPerTenantPerTick;

    private final AtomicLong oldestUnclosedAgeSeconds = new AtomicLong();
    private final AtomicLong oldestUnsettledAgeSeconds = new AtomicLong();

    public DayCloseScheduler(
            JdbcReportingStore store,
            DayCloseService dayClose,
            BusinessDayService businessDays,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.reporting.dayclose.close-delay:PT1H}") Duration closeDelay,
            @Value("${horecaos.reporting.dayclose.settle-window:P1D}") Duration settleWindow,
            @Value("${horecaos.reporting.dayclose.lease-duration:PT5M}") Duration leaseDuration,
            @Value("${horecaos.reporting.dayclose.max-days-per-tick:31}") int maxDaysPerTenantPerTick) {
        this.store = store;
        this.dayClose = dayClose;
        this.businessDays = businessDays;
        this.clock = clock;
        this.meters = meters;
        this.closeDelay = closeDelay;
        this.settleWindow = settleWindow;
        this.leaseDuration = leaseDuration;
        this.maxDaysPerTenantPerTick = maxDaysPerTenantPerTick;

        meters.gauge(
                "horecaos.reporting.dayclose.oldest_unclosed.age.seconds",
                oldestUnclosedAgeSeconds,
                AtomicLong::doubleValue);
        meters.gauge(
                "horecaos.reporting.dayclose.oldest_unsettled.age.seconds",
                oldestUnsettledAgeSeconds,
                AtomicLong::doubleValue);
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.dayclose.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.reporting.dayclose.interval:PT5M}")
    public void closeDueDays() {
        Instant now = clock.instant();
        long oldest = 0;
        for (UUID tenantId : store.activeTenantIds()) {
            try {
                oldest = Math.max(oldest, closeDueDaysFor(tenantId, now));
            } catch (RuntimeException failure) {
                log.warn("Day-close scheduling failed for tenant {}", tenantId, failure);
                recordOutcome(CLOSE, "failed");
            }
        }
        oldestUnclosedAgeSeconds.set(oldest);
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.dayclose.recut-initial-delay:PT1M}",
            fixedDelayString = "${horecaos.reporting.dayclose.recut-interval:PT15M}")
    public void recutSettledDays() {
        Instant now = clock.instant();
        long oldest = 0;
        for (UUID tenantId : store.activeTenantIds()) {
            try {
                oldest = Math.max(oldest, recutSettledDaysFor(tenantId, now));
            } catch (RuntimeException failure) {
                log.warn("Day-recut scheduling failed for tenant {}", tenantId, failure);
                recordOutcome(RECUT, "failed");
            }
        }
        oldestUnsettledAgeSeconds.set(oldest);
    }

    /** @return seconds the oldest still-uncaught-up closeable day has been waiting, or zero if none is */
    private long closeDueDaysFor(UUID tenantId, Instant now) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate upperBound = boundary.dateOf(now).minusDays(1);
        LocalDate candidate = floorDate(tenantId, boundary, store.lastRunDate(tenantId, CLOSE));

        int processed = 0;
        while (!candidate.isAfter(upperBound) && processed < maxDaysPerTenantPerTick) {
            Instant dueAt = boundary.endOf(candidate).plus(closeDelay);
            if (now.isBefore(dueAt)) {
                return 0;
            }
            closeOneDay(tenantId, candidate, now);
            processed++;
            candidate = candidate.plusDays(1);
        }
        if (candidate.isAfter(upperBound)) {
            return 0;
        }
        // The loop stopped at max-days-per-tick with backlog remaining: still due, and stale.
        return ageSeconds(boundary.endOf(candidate).plus(closeDelay), now);
    }

    /** @return seconds the oldest still-uncaught-up settleable day has been waiting, or zero if none is */
    private long recutSettledDaysFor(UUID tenantId, Instant now) {
        Optional<LocalDate> lastClosed = store.lastRunDate(tenantId, CLOSE);
        if (lastClosed.isEmpty()) {
            return 0;
        }
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate candidate = floorDate(tenantId, boundary, store.lastRunDate(tenantId, RECUT));

        int processed = 0;
        while (!candidate.isAfter(lastClosed.get()) && processed < maxDaysPerTenantPerTick) {
            Instant dueAt = boundary.endOf(candidate).plus(closeDelay).plus(settleWindow);
            if (now.isBefore(dueAt)) {
                return 0;
            }
            recutOneDay(tenantId, candidate, now);
            processed++;
            candidate = candidate.plusDays(1);
        }
        if (candidate.isAfter(lastClosed.get())) {
            return 0;
        }
        return ageSeconds(boundary.endOf(candidate).plus(closeDelay).plus(settleWindow), now);
    }

    private LocalDate floorDate(UUID tenantId, BusinessDayBoundary boundary, Optional<LocalDate> lastRun) {
        return lastRun.map(date -> date.plusDays(1)).orElseGet(() -> boundary.dateOf(store.tenantCreatedAt(tenantId)));
    }

    private void closeOneDay(UUID tenantId, LocalDate businessDate, Instant now) {
        UUID owner = UUID.randomUUID();
        if (!store.tryClaimDayClose(tenantId, businessDate, CLOSE, owner, now, leaseDuration)) {
            log.debug("Close of {} for tenant {} is already claimed by another replica", businessDate, tenantId);
            return;
        }
        try {
            dayClose.close(tenantId, businessDate);
            recordOutcome(CLOSE, "closed");
        } finally {
            store.releaseDayCloseClaim(tenantId, businessDate, CLOSE, owner);
        }
    }

    private void recutOneDay(UUID tenantId, LocalDate businessDate, Instant now) {
        UUID owner = UUID.randomUUID();
        if (!store.tryClaimDayClose(tenantId, businessDate, RECUT, owner, now, leaseDuration)) {
            log.debug("Recut of {} for tenant {} is already claimed by another replica", businessDate, tenantId);
            return;
        }
        try {
            DayCloseService.CloseResult result = dayClose.recut(tenantId, businessDate);
            recordOutcome(RECUT, "recut");
            if (!result.divergences().isEmpty()) {
                meters.counter("horecaos.reporting.dayclose.divergences")
                        .increment(result.divergences().size());
            }
        } finally {
            store.releaseDayCloseClaim(tenantId, businessDate, RECUT, owner);
        }
    }

    private void recordOutcome(String kind, String outcome) {
        Counter.builder("horecaos.reporting.dayclose.runs")
                .description("ADR 0043 day-close heartbeat outcomes")
                .tag("kind", kind)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private static long ageSeconds(Instant dueAt, Instant now) {
        return Math.max(0, Duration.between(dueAt, now).toSeconds());
    }
}
