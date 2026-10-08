package uz.horecaos.platform.reporting.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;

/**
 * The two numbers that say whether the customer funnel is being captured (ADR 0167, Observability).
 *
 * <ul>
 *   <li>{@code horecaos.reporting.funnel.unrecorded_days}: funnel rows older than a week that still carry
 *       a "not recorded" basket or checkout count. A gap in the first week is a late close catching up; one
 *       beyond it is a day whose carts are gone.
 *   <li>{@code horecaos.reporting.funnel.days_without_row}: closed business days that hold orders and have
 *       no funnel row at all. This is the alert: it is the one loss that cannot be repaired once the carts
 *       are gone, so it is counted on its own and not folded into the gaps above.
 * </ul>
 *
 * <p>Both gauges are tenant-free and subject-free by construction (ADR 0023/0033): the tenant is found from
 * the logs, never from a metric label.
 */
@Component
@ConditionalOnProperty(name = "horecaos.reporting.funnel.health.enabled", havingValue = "true", matchIfMissing = true)
public class FunnelHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(FunnelHealthProbe.class);

    static final int GRACE_DAYS = 7;
    static final int LOOKBACK_DAYS = 60;

    private final JdbcFunnelStore funnel;
    private final Clock clock;
    private final AtomicLong unrecordedDays = new AtomicLong();
    private final AtomicLong daysWithoutRow = new AtomicLong();

    public FunnelHealthProbe(JdbcFunnelStore funnel, Clock clock, MeterRegistry meters) {
        this.funnel = funnel;
        this.clock = clock;
        Gauge.builder("horecaos.reporting.funnel.unrecorded_days", unrecordedDays, AtomicLong::doubleValue)
                .description("Funnel rows older than a week with a basket or checkout count that was never recorded")
                .register(meters);
        Gauge.builder("horecaos.reporting.funnel.days_without_row", daysWithoutRow, AtomicLong::doubleValue)
                .description("Closed business days with orders and no funnel row at all")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.funnel.health.initial-delay:PT7M}",
            fixedDelayString = "${horecaos.reporting.funnel.health.interval:PT1H}")
    public void probeOnce() {
        try {
            probe();
        } catch (RuntimeException failure) {
            log.error("The customer funnel health probe could not run", failure);
        }
    }

    public Health probe() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate graceCutoff = today.minusDays(GRACE_DAYS);
        LocalDate lookbackStart = today.minusDays(LOOKBACK_DAYS);

        long unrecorded = funnel.countUnrecordedRowsBefore(graceCutoff, lookbackStart);
        // Days newer than the grace window are still being closed; a day with orders and no row there
        // is a close that has not reached it yet, not a loss.
        long missing = funnel.countClosedDaysWithOrdersAndNoFunnelRow(lookbackStart, graceCutoff);
        unrecordedDays.set(unrecorded);
        daysWithoutRow.set(missing);
        if (missing > 0) {
            log.warn(
                    "Customer funnel: {} closed business days hold orders and have no funnel row; their basket "
                            + "counts cannot be recovered once the carts are swept",
                    missing);
        }
        return new Health(unrecorded, missing);
    }

    public record Health(long unrecordedDays, long daysWithoutRow) {}
}
