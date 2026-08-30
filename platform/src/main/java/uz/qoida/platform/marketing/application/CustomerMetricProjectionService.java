package uz.qoida.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.marketing.domain.MetricDefinitions;
import uz.qoida.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.qoida.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore.DriftRow;

/**
 * Building and checking the customer metric projection (ADR 0044).
 *
 * <p>The projection exists because RFM is an aggregate over every order a customer
 * ever placed. Computing it live, for every customer in a tenant, each time a
 * marketer moves a slider, is a scan of the order table on the same database that
 * is taking orders during a dinner rush.
 *
 * <p>{@link #sweep} recomputes from source and <em>reports</em> what it found
 * before it writes. A sweep that recomputed and overwrote in one statement would
 * hide the bug that made the two disagree, and the first person to notice would be
 * a merchant comparing a campaign against an ADR 0043 report — which costs more
 * credibility than the feature earns. Drift against ADR 0043 will be raised at a
 * demo; this is how it is raised with an explanation attached.
 */
@Service
public class CustomerMetricProjectionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerMetricProjectionService.class);

    private final JdbcCustomerMetricStore metrics;
    private final Clock clock;

    public CustomerMetricProjectionService(JdbcCustomerMetricStore metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The initial build for one brand.
     *
     * <p>Separate from {@link #sweep} only in that it does not look for drift
     * first: on an empty projection every row would be reported as drifting, which
     * would fill the observation table with noise on the day the feature ships.
     */
    @Transactional
    public int backfill(UUID tenantId, UUID brandId) {
        Instant now = clock.instant();
        int rows = metrics.recompute(tenantId, brandId, now, MetricDefinitions.CURRENT_VERSION);
        metrics.refreshFrequencyCounters(tenantId, brandId, now);
        log.info("Backfilled {} marketing metric rows for brand {}", rows, brandId);
        return rows;
    }

    /**
     * The nightly pass: observe, then recompute.
     *
     * <p>The order is the decision. Observing after recomputing would compare the
     * projection against itself and find nothing forever.
     */
    @Transactional
    public SweepResult sweep(UUID tenantId, UUID brandId) {
        Instant now = clock.instant();

        int drifted = metrics.observeDrift(tenantId, brandId, now,
                MetricDefinitions.CURRENT_VERSION);
        int rows = metrics.recompute(tenantId, brandId, now, MetricDefinitions.CURRENT_VERSION);
        metrics.refreshFrequencyCounters(tenantId, brandId, now);

        if (drifted > 0) {
            // At warn rather than info. A projection that disagrees with its own
            // source is a bug somebody has to look at, and the count is here so it
            // can be alerted on without reading the table.
            log.warn("Marketing projection drift for brand {}: {} disagreements recorded",
                    brandId, drifted);
        }
        return new SweepResult(rows, drifted);
    }

    public List<DriftRow> drift(UUID tenantId, UUID brandId) {
        return metrics.drift(tenantId, brandId);
    }

    /**
     * ADR 0029 erasure, the part this module owns.
     *
     * <p>The projection row and the customer's snapshot membership go. Campaign
     * recipient counts and campaign spend stay: an aggregate that no longer
     * identifies anyone is not erased, and reversing a finance number to honour a
     * privacy request is a different kind of wrong.
     */
    @Transactional
    public int erase(UUID tenantId, UUID accountId) {
        return metrics.erase(tenantId, accountId);
    }

    /** What one sweep did, and what it refused to fix. */
    public record SweepResult(int rowsRecomputed, int driftObservations) { }
}
