package uz.horecaos.platform.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0084: the platform's figures, counted from the tables on request.
 *
 * <p>Exact counts, not a page of a list: the health board used to count the
 * first two hundred tenants it was sent and call that the total. Every figure
 * here is a {@code count(*)} or an age, across every tenant, with nothing that
 * names a tenant's customer — the same rule the metrics in this module follow
 * (ADR 0029), because this is the same information read by a person instead
 * of a scraper.
 *
 * <p>The inbox is the one expensive read (its aggregate has no index to serve
 * it, as {@link MessagingBacklogMetrics} records), which is acceptable for a
 * page a person opens and is why the scraper does not do it every fifteen
 * seconds.
 */
@RestController
@Tag(name = "Platform health", description = "ADR 0084: platform-wide figures across every tenant")
public class PlatformHealthController {

    private static final String LIVE_STATUSES = """
            'RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL',
            'CONFIRMED', 'PREPARING', 'READY', 'FULFILLING'
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public PlatformHealthController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/v1/control-plane/platform-health")
    @RequiresCapability(value = Capability.TENANT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Tenants, orders, receipts and queues across the platform",
            description = "Exact counts at the moment of asking: tenants by status, orders placed in "
                    + "the last hour and day and those still in progress, fiscal receipts of the last "
                    + "day by outcome and every blocked one, and each queue's backlog with the age of "
                    + "its oldest waiting message.")
    PlatformHealth health() {
        Instant now = clock.instant();
        OffsetDateTime hourAgo = utc(now.minus(Duration.ofHours(1)));
        OffsetDateTime dayAgo = utc(now.minus(Duration.ofDays(1)));

        Map<String, Long> tenants =
                byStatus(jdbc.sql("SELECT status AS key, count(*) AS total FROM tenant.tenants GROUP BY status"));

        OrderFigures orders = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE created_at >= :hourAgo) AS last_hour, count(*) AS last_day
                          FROM ordering.orders WHERE created_at >= :dayAgo
                        """)
                .param("hourAgo", hourAgo)
                .param("dayAgo", dayAgo)
                .query((row, number) ->
                        new OrderFigures(row.getLong("last_hour"), row.getLong("last_day"), Map.of(), 0))
                .single();
        Map<String, Long> live = byStatus(jdbc.sql("""
                SELECT status AS key, count(*) AS total FROM ordering.orders WHERE status IN (%s) GROUP BY status
                """.formatted(LIVE_STATUSES)));
        long oldestLive = jdbc.sql("""
                        SELECT coalesce(EXTRACT(EPOCH FROM CAST(:now AS timestamptz) - min(created_at)), 0)
                          FROM ordering.orders WHERE status IN (%s)
                        """.formatted(LIVE_STATUSES))
                .param("now", utc(now))
                .query(Long.class)
                .single();

        Map<String, Long> receipts = byStatus(jdbc.sql("""
                        SELECT status AS key, count(*) AS total
                          FROM fiscal.fiscal_documents WHERE created_at >= :dayAgo GROUP BY status
                        """).param("dayAgo", dayAgo));
        long blockedNow = jdbc.sql("SELECT count(*) FROM fiscal.fiscal_documents WHERE status = 'BLOCKED'")
                .query(Long.class)
                .single();

        List<QueueBacklog> outbox = jdbc.sql("""
                        SELECT topic AS name, count(*) AS pending,
                               coalesce(EXTRACT(EPOCH FROM CAST(:now AS timestamptz) - min(created_at)), 0) AS oldest
                          FROM integration.outbox_events
                         WHERE status IN ('PENDING', 'PUBLISHING')
                         GROUP BY topic ORDER BY oldest DESC
                        """)
                .param("now", utc(now))
                .query((row, number) ->
                        new QueueBacklog(row.getString("name"), row.getLong("pending"), row.getLong("oldest")))
                .list();
        List<QueueBacklog> inbox = jdbc.sql("""
                        SELECT consumer_name AS name, count(*) AS pending,
                               coalesce(EXTRACT(EPOCH FROM CAST(:now AS timestamptz) - min(received_at)), 0) AS oldest
                          FROM integration.inbox_messages
                         WHERE status IN ('RECEIVED', 'PROCESSING', 'RETRY_PENDING')
                         GROUP BY consumer_name ORDER BY oldest DESC
                        """)
                .param("now", utc(now))
                .query((row, number) ->
                        new QueueBacklog(row.getString("name"), row.getLong("pending"), row.getLong("oldest")))
                .list();
        long outboxDead = jdbc.sql("SELECT count(*) FROM integration.outbox_events WHERE status = 'DEAD_LETTER'")
                .query(Long.class)
                .single();
        long inboxDead = jdbc.sql("SELECT count(*) FROM integration.inbox_messages WHERE status = 'DEAD_LETTER'")
                .query(Long.class)
                .single();

        return new PlatformHealth(
                now.toString(),
                tenants,
                new OrderFigures(orders.lastHour(), orders.lastDay(), live, oldestLive),
                new ReceiptFigures(receipts, blockedNow),
                new QueueFigures(outbox, inbox, outboxDead, inboxDead));
    }

    private static Map<String, Long> byStatus(JdbcClient.StatementSpec statement) {
        Map<String, Long> counts = new TreeMap<>();
        statement
                .query((row, number) -> Map.entry(row.getString("key"), row.getLong("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Everything the board shows, as of {@code measuredAt}. */
    public record PlatformHealth(
            String measuredAt,
            Map<String, Long> tenantsByStatus,
            OrderFigures orders,
            ReceiptFigures receipts,
            QueueFigures queues) {}

    /**
     * @param liveByStatus orders not yet finished, by status
     * @param oldestLiveAgeSeconds how long the oldest unfinished order has been waiting
     */
    public record OrderFigures(
            long lastHour, long lastDay, Map<String, Long> liveByStatus, long oldestLiveAgeSeconds) {}

    /**
     * @param lastDayByStatus receipts created in the last day, by where they got to
     * @param blocked every receipt waiting on a person now, however old
     */
    public record ReceiptFigures(Map<String, Long> lastDayByStatus, long blocked) {}

    /**
     * @param outbox waiting events by topic, oldest first
     * @param inbox waiting messages by consumer, oldest first
     */
    public record QueueFigures(
            List<QueueBacklog> outbox, List<QueueBacklog> inbox, long outboxDeadLetters, long inboxDeadLetters) {}

    public record QueueBacklog(String name, long pending, long oldestAgeSeconds) {}
}
