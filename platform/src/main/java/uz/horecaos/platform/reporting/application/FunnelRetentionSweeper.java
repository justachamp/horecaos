package uz.horecaos.platform.reporting.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.AttributionClickSeries;
import uz.horecaos.platform.reporting.api.ReportingConfigurationKeys;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Deletes funnel, registration and link-click count rows past {@code reporting.funnel_retention_days}
 * -- and, like every destructive retention job (ADR 0029), only <em>reports</em> that it would until a
 * person has seen it do the right thing on a sample.
 *
 * <p>The mode is configuration ({@code horecaos.reporting.funnel.retention.mode}) and its default is the
 * safe one. In report-only a pass counts what it would delete, logs the count and sets the gauge
 * {@code horecaos.reporting.funnel.retention_due}, and writes nothing. The rows hold counts and no
 * personal data (ADR 0167 Decision 7), so an erasure request does not touch them and this job is about
 * usefulness and not law; the per-person facts they are counted from keep their own retention (carts
 * ninety days, ADR 0092; accounts, ADR 0029).
 *
 * <p>The cutoff is a business date: rows with {@code business_date} before
 * {@code today - retentionDays} (UTC today; the retention period is long enough that the hours of
 * difference from a tenant's own day are not the point).
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.reporting.funnel.retention.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FunnelRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(FunnelRetentionSweeper.class);

    public enum Mode {
        /** Count and report; write nothing. The default, and the only mode until sampled proof exists. */
        REPORT_ONLY,
        /** Delete past retention. */
        ENFORCE
    }

    private final JdbcFunnelStore funnel;
    private final AttributionClickSeries clickSeries;
    private final ConfigurationResolver configuration;
    private final Clock clock;
    private final Mode mode;
    private final AtomicLong due = new AtomicLong();

    public FunnelRetentionSweeper(
            JdbcFunnelStore funnel,
            AttributionClickSeries clickSeries,
            ConfigurationResolver configuration,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.reporting.funnel.retention.mode:REPORT_ONLY}") Mode mode) {
        this.funnel = funnel;
        this.clickSeries = clickSeries;
        this.configuration = configuration;
        this.clock = clock;
        this.mode = mode;
        Gauge.builder("horecaos.reporting.funnel.retention_due", due, AtomicLong::doubleValue)
                .description("Funnel, registration and click-series rows past retention and not yet deleted")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.funnel.retention.initial-delay:PT6M}",
            fixedDelayString = "${horecaos.reporting.funnel.retention.interval:PT6H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The customer funnel retention sweep could not run", failure);
        }
    }

    /** @return how many rows this pass deleted, or would have in report-only mode */
    public long runOnce() {
        Integer configured =
                configuration.value(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS, ResourceScope.platform());
        int retentionDays = configured == null ? ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS_DEFAULT : configured;
        LocalDate cutoff = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().minusDays(retentionDays);

        long pastRetention = funnel.countOlderThan(cutoff) + clickSeries.countOlderThan(cutoff);
        due.set(pastRetention);
        if (pastRetention == 0) {
            return 0;
        }
        if (mode == Mode.REPORT_ONLY) {
            log.info(
                    "Customer funnel retention (report-only): {} count rows are past {} days and would be deleted",
                    pastRetention,
                    retentionDays);
            return pastRetention;
        }
        long deleted = funnel.deleteOlderThan(cutoff) + clickSeries.deleteOlderThan(cutoff);
        due.set(0);
        log.info("Customer funnel retention: {} count rows older than {} days deleted", deleted, retentionDays);
        return deleted;
    }
}
