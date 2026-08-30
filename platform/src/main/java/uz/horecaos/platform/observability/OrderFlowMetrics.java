package uz.horecaos.platform.observability;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orders by state, and the age of the oldest order that has not reached one
 * (ADR 0023).
 *
 * <p>Nothing pages on these and that is deliberate. They are the diagnosis after
 * a page has already fired: the outbox alert says events are not moving, and
 * this says whether that has left a hundred orders sitting in
 * {@code PAYMENT_AUTHORIZING} or none. An alert on "orders in state X" would
 * have to know what a normal Tuesday looks like, and at one pilot tenant there
 * is no such thing yet — which is the same reason ADR 0023 refuses error budgets.
 *
 * <p>The label is the order status and nothing else. There is no tenant label
 * here, under ADR 0023's rule and ADR 0033's before it: tenant identifiers are
 * unbounded cardinality on a metrics store that shares a disk with PostgreSQL.
 * "Which tenant" is answered from the logs, which carry it, or from the
 * operations order list, which is authorised.
 *
 * <p>The status set is closed by {@code ck_order_status}, so the label set is
 * bounded by the schema rather than by hope.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.observability.metrics.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OrderFlowMetrics {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowMetrics.class);

    /**
     * Statuses an order is still moving through. The terminal ones are excluded
     * from the age gauge because a completed order from last March is correctly
     * old, and including it would pin the gauge at four months forever.
     */
    private static final String LIVE_STATUSES = """
            'RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL',
            'CONFIRMED', 'PREPARING', 'READY', 'FULFILLING'
            """;

    private final JdbcClient jdbc;
    private final AtomicLong oldestLiveOrderAgeSeconds = new AtomicLong();
    private final MultiGauge ordersByStatus;

    public OrderFlowMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("horecaos.orders.oldest.live.age", oldestLiveOrderAgeSeconds, AtomicLong::doubleValue)
                .description("Age of the oldest order that has not reached a terminal status")
                .baseUnit("seconds")
                .register(meters);
        this.ordersByStatus = MultiGauge.builder("horecaos.orders.live")
                .description("Orders that have not reached a terminal status, by status")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.observability.metrics.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.observability.metrics.interval:PT15S}")
    public void refresh() {
        try {
            List<StatusCount> counts = jdbc.sql("""
                            SELECT status, count(*) AS total
                            FROM ordering.orders
                            WHERE status IN (%s)
                            GROUP BY status
                            """.formatted(LIVE_STATUSES))
                    .query((resultSet, rowNumber) -> new StatusCount(
                            resultSet.getString("status"),
                            resultSet.getLong("total")))
                    .list();
            ordersByStatus.register(
                    counts.stream()
                            .map(count -> MultiGauge.Row.of(Tags.of("status", count.status()), count.total()))
                            .map(row -> (MultiGauge.Row<?>) row)
                            .toList(),
                    true);

            Long oldest = jdbc.sql("""
                            SELECT coalesce(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest_age_seconds
                            FROM ordering.orders
                            WHERE status IN (%s)
                            """.formatted(LIVE_STATUSES))
                    .query(Long.class)
                    .single();
            oldestLiveOrderAgeSeconds.set(oldest == null ? 0L : oldest);
        } catch (RuntimeException failure) {
            log.warn("Could not refresh order flow metrics: {}", failure.toString());
        }
    }

    private record StatusCount(String status, long total) {
    }
}
