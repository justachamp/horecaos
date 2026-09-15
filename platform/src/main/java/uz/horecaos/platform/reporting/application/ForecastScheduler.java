package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.HolidayMode;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * The production caller {@link ForecastService} has never had — the brief's
 * "the run itself is a scheduled system job", the same gap {@code
 * DayCloseScheduler}'s own doc names for {@code DayCloseService} before wave
 * 6 gave it one. Without this class the model is real and tested but nothing
 * ever generates a run outside a test.
 *
 * <p>Two independent timers, the same shape {@code DayCloseScheduler} already
 * uses for its own two operations:
 *
 * <ul>
 *   <li>{@link #generateDueForecasts()} — daily, one {@code forecast_run} per
 *       active location per weekday (all seven, not only tomorrow's): a
 *       manager can pick any weekday on {@code /statistics/forecast} the same
 *       way {@code demand-history} already lets them, and every one of those
 *       choices needs a recent run behind it, not only the next calendar day.
 *       Idempotent by construction — {@code ForecastService.generateForecast}
 *       always writes a fresh {@code run_id}, so a missed or repeated tick
 *       costs an extra row of forecast history, never a wrong one, and two
 *       replicas racing the same tick simply both add a row rather than
 *       corrupting one.
 *   <li>{@link #backfillDueActuals()} — more often, sweeping the last few
 *       business dates for every active tenant. {@code ForecastService
 *       .backfillActuals} only ever touches a row whose {@code
 *       actual_quantity} is still null, so repeating it against an
 *       already-backfilled date is a no-op select that finds nothing, the
 *       same safety property that lets this run without a durable
 *       cross-replica claim the way {@code DayCloseScheduler} needs one for
 *       its delete-then-rewrite close — there is no delete-then-rewrite here,
 *       only an INSERT nothing else contends for and an UPDATE guarded by
 *       {@code actual_quantity IS NULL}.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "horecaos.reporting.forecast.enabled", havingValue = "true", matchIfMissing = true)
public class ForecastScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForecastScheduler.class);

    /** Mirrors {@code ReportingController.DEMAND_SAMPLE_DEFAULT} — "the last 4 Tuesdays" applies to the model exactly as it does to the honest average beside it. */
    private static final int DEFAULT_SAMPLE_SIZE = 4;

    /** How many trailing business dates {@link #backfillDueActuals()} checks per tenant per tick — covers a normal one-day close delay plus headroom for a missed tick or two. */
    private static final int BACKFILL_LOOKBACK_DAYS = 4;

    private final JdbcReportingStore store;
    private final ForecastService forecasts;
    private final BusinessDayService businessDays;
    private final Clock clock;

    public ForecastScheduler(
            JdbcReportingStore store, ForecastService forecasts, BusinessDayService businessDays, Clock clock) {
        this.store = store;
        this.forecasts = forecasts;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.forecast.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.reporting.forecast.interval:P1D}")
    public void generateDueForecasts() {
        for (UUID tenantId : store.activeTenantIds()) {
            try {
                for (UUID locationId : store.activeLocationIds(tenantId)) {
                    for (int weekday = 1; weekday <= 7; weekday++) {
                        forecasts.generateForecast(
                                tenantId, locationId, weekday, DEFAULT_SAMPLE_SIZE, HolidayMode.INCLUDE);
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("Forecast generation failed for tenant {}", tenantId, failure);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.forecast.backfill-initial-delay:PT3M}",
            fixedDelayString = "${horecaos.reporting.forecast.backfill-interval:PT15M}")
    public void backfillDueActuals() {
        for (UUID tenantId : store.activeTenantIds()) {
            try {
                BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
                LocalDate today = LocalDate.now(clock.withZone(boundary.zone()));
                for (int daysAgo = 1; daysAgo <= BACKFILL_LOOKBACK_DAYS; daysAgo++) {
                    forecasts.backfillActuals(tenantId, today.minusDays(daysAgo));
                }
            } catch (RuntimeException failure) {
                log.warn("Forecast actual backfill failed for tenant {}", tenantId, failure);
            }
        }
    }
}
