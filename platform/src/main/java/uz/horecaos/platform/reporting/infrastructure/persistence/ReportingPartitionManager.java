package uz.horecaos.platform.reporting.infrastructure.persistence;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the fact partitions ahead of the clock (ADR 0043).
 *
 * <p>Unlike {@code audit.audit_events}, the fact tables have no default
 * partition. An audited action must never fail for want of a partition, because
 * losing the evidence is worse than any alternative; a fact is derived and
 * rebuildable, so a failed insert costs a rerun. What a default partition costs
 * instead is worse: it quietly absorbs rows for a month nobody provisioned and
 * then blocks the creation of that month's partition until somebody finds and
 * moves them.
 *
 * <p>So the partitions are provisioned well ahead and the close job fails loudly
 * if one is missing. Running daily is deliberate over-provisioning of an
 * operation that costs nothing.
 */
@Component
@ConditionalOnProperty(name = "horecaos.reporting.partitions.enabled", havingValue = "true",
        matchIfMissing = true)
public class ReportingPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(ReportingPartitionManager.class);

    /** Every table the migration's upkeep function is allowed to partition. */
    private static final List<String> TABLES =
            List.of("fact_order", "fact_order_line", "fact_refund");

    /** Three months of runway leaves room for a fortnight of missed runs. */
    private static final int MONTHS_AHEAD = 3;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ReportingPartitionManager(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.partitions.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.reporting.partitions.interval:P1D}")
    public void ensurePartitions() {
        LocalDate month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        // Back one month as well as forward: a close running just after midnight
        // on the first is still writing yesterday, which is last month.
        for (int offset = -1; offset <= MONTHS_AHEAD; offset++) {
            ensurePartitionsFor(month.plusMonths(offset));
        }
    }

    /** Idempotent, so a repeated run is harmless. */
    public void ensurePartitionsFor(LocalDate month) {
        for (String table : TABLES) {
            jdbc.sql("SELECT reporting.ensure_fact_partition(:table, :month)")
                    .param("table", table)
                    .param("month", month.withDayOfMonth(1))
                    .query(Object.class)
                    .optional();
        }
        log.debug("Ensured reporting partitions for {}", month.withDayOfMonth(1));
    }
}
