package uz.horecaos.platform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two gauges the night alert "order flow stalled" is evaluated from, plus
 * the dead-letter counts the trading-hours alert needs (ADR 0023).
 *
 * <p><strong>Age, not depth.</strong> A backlog of four hundred rows on a busy
 * evening is the relay working; one row that is twenty minutes old is orders not
 * reaching the kitchen. Depth is published too, because it is the first thing an
 * operator wants once woken, but nothing pages on it. The threshold on age is
 * fifteen minutes and ADR 0006's own numbers are why: ten attempts of
 * exponential backoff capped at five minutes total roughly thirteen and a half
 * minutes, so a healthy row cannot reach fifteen even in its worst retry
 * schedule.
 *
 * <p>The age is measured from {@code created_at} rather than from
 * {@code next_attempt_at} on purpose. A row waiting out its backoff is still an
 * order that has not been delivered, and measuring from the next attempt would
 * make a row that has been failing for an hour look one minute old.
 *
 * <p>{@code PUBLISHING} counts as pending. A row a dead worker left claimed is
 * not progressing, and it stays claimed until the five-minute lease expires;
 * excluding it would hide exactly the failure the lease exists to survive.
 *
 * <p>Polled on a timer into fields rather than queried from the gauge callbacks.
 * Micrometer invokes a callback once per meter per scrape, so callback-backed
 * gauges would turn one scrape into one aggregate query per gauge against tables
 * on the order hot path.
 *
 * <p><strong>The inbox is polled on its own, slower timer, and gives up when it
 * gets expensive.</strong> Its aggregate has no index to serve it, so on a large
 * {@code inbox_messages} it is a sequential scan — and a sequential scan every
 * fifteen seconds is a metric that costs more than the thing it measures. The
 * alert it feeds fires on an age of fifteen minutes, so a minute between polls
 * still catches a stall well before the threshold, and even the five-minute
 * ceiling the backoff climbs to does: a row pending fifteen minutes is still
 * pending at twenty.
 *
 * <p>Backing off silently would be the real hazard — an unrefreshed gauge holds
 * its last value, and a stale zero is indistinguishable from a healthy zero. So
 * the staleness of the inbox figures is published alongside them, and the scan's
 * own duration with it, because that is the number that says the index is now
 * overdue.
 *
 * <p>ADR 0029: no tenant, aggregate, or correlation identifier is a label here.
 * Dead letters are grouped by the first segment of the topic — the event
 * catalogue's domain — because that is what separates "a customer's money is
 * stuck" from "a notification template is wrong", which is the only distinction
 * the alert tiers make.
 */
@Component
@ConditionalOnProperty(name = "horecaos.observability.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(MessagingBacklogMetrics.class);

    /**
     * A ceiling on distinct topic domains, so that a misconfigured topic name
     * cannot turn a bounded label into unbounded cardinality on a Prometheus
     * sharing a disk with PostgreSQL. Anything past it is bucketed as "other",
     * which loses a little detail and cannot cost the box its storage.
     */
    private static final int MAX_TOPIC_DOMAINS = 16;

    /**
     * Past this, the inbox scan is costing the database more than the figure is
     * worth. Two seconds is far above what the query takes with an index and far
     * below anything that would hold a pooled connection long enough to matter.
     */
    private static final Duration SLOW_SCAN_BUDGET = Duration.ofSeconds(2);

    /**
     * How long the poll waits after a slow scan: two minutes, then four, then
     * the ceiling.
     *
     * <p>The first rung is longer than the ordinary cadence on purpose, so that
     * backing off actually skips a poll rather than announcing a change that
     * does nothing. The ceiling is chosen against ADR 0023's own threshold rather
     * than against a feeling: the alert fires on an age of fifteen minutes, so a
     * fifteen-minute-old row is still over the line when it is finally observed
     * up to five minutes later. A ceiling as long as the threshold itself would
     * let a stall start and clear unseen.
     */
    private static final Duration COOLDOWN_INITIAL = Duration.ofMinutes(2);

    private static final Duration COOLDOWN_MAX = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong inboxPending = new AtomicLong();
    private final AtomicLong inboxOldestPendingAgeSeconds = new AtomicLong();
    private final MultiGauge outboxDeadLetters;
    private final MultiGauge inboxDeadLetters;

    private volatile Instant lastInboxScan;
    private volatile Instant inboxScanNotBefore = Instant.MIN;
    private volatile Duration inboxCooldown = Duration.ZERO;
    private volatile Duration lastInboxScanDuration = Duration.ZERO;

    public MessagingBacklogMetrics(JdbcClient jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        // Seeded rather than left null so the staleness gauge reads as an age
        // from the start; "never refreshed" and "refreshed long ago" want the
        // same alert.
        this.lastInboxScan = clock.instant();

        Gauge.builder("horecaos.outbox.pending", outboxPending, AtomicLong::doubleValue)
                .description("Outbox rows awaiting publication, including rows a worker has claimed")
                .register(meters);
        Gauge.builder("horecaos.outbox.oldest.pending.age", outboxOldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("Age of the oldest outbox row that has not been published")
                .baseUnit("seconds")
                .register(meters);
        Gauge.builder("horecaos.inbox.pending", inboxPending, AtomicLong::doubleValue)
                .description("Inbox rows received or awaiting retry that have not been processed")
                .register(meters);
        Gauge.builder("horecaos.inbox.oldest.pending.age", inboxOldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("Age of the oldest inbox row that has not been processed")
                .baseUnit("seconds")
                .register(meters);
        // Callbacks here, unlike the four above, because these two answer from a
        // clock rather than from the database — the objection to callback-backed
        // gauges is the query they would run, not the callback itself.
        Gauge.builder(
                        "horecaos.inbox.backlog.staleness",
                        this,
                        self -> self.inboxStaleness().toMillis() / 1000.0)
                .description("Time since the inbox backlog figures above were last recomputed")
                .baseUnit("seconds")
                .register(meters);
        Gauge.builder(
                        "horecaos.inbox.backlog.scan.duration",
                        this,
                        self -> self.lastInboxScanDuration.toMillis() / 1000.0)
                .description("How long the last inbox backlog scan took; the signal that "
                        + "integration.inbox_messages has outgrown a sequential scan")
                .baseUnit("seconds")
                .register(meters);

        this.outboxDeadLetters = MultiGauge.builder("horecaos.outbox.dead.letters")
                .description("Outbox rows in DEAD_LETTER, by topic domain")
                .register(meters);
        this.inboxDeadLetters = MultiGauge.builder("horecaos.inbox.dead.letters")
                .description("Inbox rows in DEAD_LETTER, by topic domain and failure category")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.observability.metrics.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.observability.metrics.interval:PT15S}")
    public void refresh() {
        try {
            refreshOutbox();
        } catch (RuntimeException failure) {
            // Deliberately swallowed after logging. This poll runs on the same
            // scheduler as the rest of the platform's timers, and an exception
            // escaping a @Scheduled method cancels no future execution but does
            // fill the log with a stack trace every fifteen seconds. The gauges
            // keep their last values, which is the correct answer while the
            // database is unreachable: the alert that matters then is the one
            // watching the database, not a backlog figure nobody can read.
            log.warn("Could not refresh messaging backlog metrics: {}", failure.toString());
        }
    }

    /**
     * The inbox half, on its own timer and with a cost ceiling.
     *
     * <p>Offset from the outbox poll rather than sharing it, so the two scans do
     * not arrive at the database together.
     */
    @Scheduled(
            initialDelayString = "${horecaos.observability.metrics.inbox-initial-delay:PT25S}",
            fixedDelayString = "${horecaos.observability.metrics.inbox-interval:PT1M}")
    public void refreshInboxBacklog() {
        if (!inboxScanIsDue(clock.instant())) {
            return;
        }
        // System.nanoTime rather than the clock: this is an elapsed measurement,
        // and the injected clock is fixed in tests and steppable in production.
        long startedAt = System.nanoTime();
        boolean scanned = false;
        try {
            refreshInbox();
            scanned = true;
        } catch (RuntimeException failure) {
            log.warn("Could not refresh inbox backlog metrics: {}", failure.toString());
        } finally {
            recordInboxScan(Duration.ofNanos(System.nanoTime() - startedAt), scanned);
        }
    }

    /** Whether the cool-off from the last slow scan has elapsed. */
    boolean inboxScanIsDue(Instant now) {
        return !now.isBefore(inboxScanNotBefore);
    }

    /**
     * Extends or clears the cool-off, and records how stale the figures now are.
     *
     * <p>Split out so the ladder can be exercised without a table large enough to
     * be slow, which is the one thing a test cannot arrange honestly.
     *
     * <p>The cool-off is decided by how long the scan took and not by whether it
     * worked. A scan that spent its whole budget and then failed is the most
     * expensive outcome there is, and a database that is simply unreachable
     * refuses in milliseconds, so duration separates the two on its own.
     *
     * @param scanned whether the figures were actually refreshed, which is what
     *                the staleness gauge answers from
     */
    void recordInboxScan(Duration took, boolean scanned) {
        lastInboxScanDuration = took;
        Instant now = clock.instant();

        if (scanned) {
            lastInboxScan = now;
        }
        if (took.compareTo(SLOW_SCAN_BUDGET) < 0) {
            if (!inboxCooldown.isZero()) {
                log.info("Inbox backlog scan is back within {}; resuming the normal cadence", SLOW_SCAN_BUDGET);
            }
            inboxCooldown = Duration.ZERO;
            inboxScanNotBefore = Instant.MIN;
            return;
        }

        Duration extended =
                inboxCooldown.isZero() ? COOLDOWN_INITIAL : min(inboxCooldown.multipliedBy(2), COOLDOWN_MAX);
        if (!extended.equals(inboxCooldown)) {
            log.warn(
                    "Inbox backlog scan took {} against integration.inbox_messages; backing off "
                            + "to {} between polls. The table has outgrown a sequential scan and needs "
                            + "an index on (status, received_at).",
                    took,
                    extended);
        }
        inboxCooldown = extended;
        inboxScanNotBefore = now.plus(extended);
    }

    /** How long ago the inbox figures were last recomputed. */
    Duration inboxStaleness() {
        return Duration.between(lastInboxScan, clock.instant());
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private void refreshOutbox() {
        Backlog backlog = jdbc.sql("""
                        SELECT count(*) AS pending,
                               coalesce(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest_age_seconds
                        FROM integration.outbox_events
                        WHERE status IN ('PENDING', 'PUBLISHING')
                        """)
                .query((resultSet, rowNumber) ->
                        new Backlog(resultSet.getLong("pending"), resultSet.getLong("oldest_age_seconds")))
                .single();
        outboxPending.set(backlog.pending());
        outboxOldestPendingAgeSeconds.set(backlog.oldestAgeSeconds());

        List<DeadLetterRow> rows = jdbc.sql("""
                        SELECT split_part(topic, '.', 1) AS topic_domain,
                               count(*) AS total
                        FROM integration.outbox_events
                        WHERE status = 'DEAD_LETTER'
                        GROUP BY 1
                        ORDER BY 2 DESC
                        """)
                .query((resultSet, rowNumber) -> new DeadLetterRow(
                        resultSet.getString("topic_domain"),
                        // Outbox rows carry no classified failure category. The
                        // relay writes only `last_error` free text, so the
                        // category the inbox reports cannot be reported here
                        // without a schema change. Labelled "unclassified"
                        // rather than omitted, so the gap is visible on the
                        // dashboard instead of being mistaken for zero.
                        "unclassified",
                        resultSet.getLong("total")))
                .list();
        register(outboxDeadLetters, rows);
    }

    private void refreshInbox() {
        Backlog backlog = jdbc.sql("""
                        SELECT count(*) AS pending,
                               coalesce(EXTRACT(EPOCH FROM now() - min(received_at)), 0) AS oldest_age_seconds
                        FROM integration.inbox_messages
                        WHERE status IN ('RECEIVED', 'PROCESSING', 'RETRY_PENDING')
                        """)
                .query((resultSet, rowNumber) ->
                        new Backlog(resultSet.getLong("pending"), resultSet.getLong("oldest_age_seconds")))
                .single();
        inboxPending.set(backlog.pending());
        inboxOldestPendingAgeSeconds.set(backlog.oldestAgeSeconds());

        List<DeadLetterRow> rows = jdbc.sql("""
                        SELECT split_part(topic, '.', 1) AS topic_domain,
                               coalesce(last_error_code, 'UNKNOWN') AS failure_category,
                               count(*) AS total
                        FROM integration.inbox_messages
                        WHERE status = 'DEAD_LETTER'
                        GROUP BY 1, 2
                        ORDER BY 3 DESC
                        """)
                .query((resultSet, rowNumber) -> new DeadLetterRow(
                        resultSet.getString("topic_domain"),
                        resultSet.getString("failure_category"),
                        resultSet.getLong("total")))
                .list();
        register(inboxDeadLetters, rows);
    }

    /**
     * Publishes one row per label combination, folding anything past the domain
     * ceiling into "other" and marking the monetary domains, which are the only
     * ones whose dead letters are allowed to make a noise before morning.
     */
    private static void register(MultiGauge gauge, List<DeadLetterRow> rows) {
        List<MultiGauge.Row<?>> published = new ArrayList<>();
        long overflow = 0L;
        int kept = 0;
        for (DeadLetterRow row : rows) {
            if (kept >= MAX_TOPIC_DOMAINS) {
                overflow += row.total();
                continue;
            }
            kept++;
            published.add(MultiGauge.Row.of(
                    Tags.of(
                            "topic_domain", row.topicDomain(),
                            "failure_category", row.failureCategory(),
                            "monetary", Boolean.toString(MonetaryTopics.isMonetary(row.topicDomain()))),
                    row.total()));
        }
        if (overflow > 0) {
            // Marked monetary. Folding is a cardinality defence, and a defence
            // that could silence a monetary dead letter by bucketing it would be
            // worse than the cardinality. The overflow bucket therefore always
            // reads as monetary, which at worst wakes the operator to a
            // notification failure and at best does not lose a payment.
            published.add(MultiGauge.Row.of(
                    Tags.of("topic_domain", "other", "failure_category", "UNKNOWN", "monetary", "true"), overflow));
        }
        // `true` overwrites the previous set, so a domain whose dead letters were
        // resolved stops being reported rather than being frozen at its last
        // value for the life of the process.
        gauge.register(published, true);
    }

    private record Backlog(long pending, long oldestAgeSeconds) {}

    private record DeadLetterRow(String topicDomain, String failureCategory, long total) {}
}
