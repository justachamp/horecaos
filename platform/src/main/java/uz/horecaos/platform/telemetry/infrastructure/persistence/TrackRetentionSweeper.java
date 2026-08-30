package uz.horecaos.platform.telemetry.infrastructure.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;


/**
 * Retention as a job that runs, rather than a paragraph in an ADR (ADR 0045).
 *
 * <p>Three responsibilities, one schedule, and the ordering matters.
 *
 * <p><strong>Partitions ahead of the clock.</strong> An observation that lands in
 * the default partition is not lost, but it is also not droppable — a default
 * partition cannot be dropped without taking every future row with it — so a
 * missed partition silently converts a thirty-day retention into a permanent one.
 * Two weeks of headroom is deliberate over-provisioning of a very cheap
 * operation, the same trade ADR 0027's audit partition manager makes.
 *
 * <p><strong>Live rows an hour after sign-off.</strong> The compensating control
 * that pays for storing coordinates unencrypted. An hour rather than immediately
 * because a dispatcher finishing a call about an order that has just been
 * delivered still needs the pin; a day would make the table a history, which is
 * the thing it is not allowed to become.
 *
 * <p><strong>Partitions past the retention window, dropped whole.</strong> A
 * {@code DELETE} sweep over hundreds of thousands of rows a day on a box that
 * also runs PostgreSQL, Kafka, Keycloak, MinIO, and OpenBao would leave bloat
 * behind it and would eventually be turned off by whoever is on call. Dropping a
 * partition is O(1) and cannot be half-done.
 *
 * <p><strong>A partition holds every tenant's rows, so the window that governs it
 * is the longest anybody configured.</strong> ADR 0030 lets a tenant set its own
 * {@code telemetry.track_retention_days}, and the startup check refuses anything
 * below the derived floor — so a stored value can only be longer than the
 * platform default, never shorter. Dropping at the platform default would then
 * delete evidence a tenant is required to hold, and dropping per tenant is not
 * something a shared partition can do. Taking the maximum is the only answer that
 * is correct rather than convenient; the review ceiling in
 * {@code TrackRetentionFloor} is what stops one tenant's excessive value quietly
 * becoming everybody's.
 *
 * <p>Report-only mode exists because ADR 0029 asks for it: a retention job's
 * first run in a new environment should say what it would delete before it
 * deletes anything, and an operator should be able to read that list.
 *
 * <p><strong>None of the three statements is DDL any more.</strong> The
 * application connects as {@code horecaos_application}, which holds no DDL rights on
 * anything, so the first and third go through V0075's {@code SECURITY DEFINER}
 * functions. Moving the whole job to deploy time was the alternative and is
 * rejected: a retention window does not pause between releases — thirty days is
 * thirty days whether or not anyone deployed — so retention that runs at deploy
 * time is retention that silently lengthens with every quiet month, which is
 * exactly the failure this job exists to prevent. The schedule stays and the
 * grant narrows instead. What the application holds is EXECUTE on two functions
 * that between them cannot be pointed at another table, cannot be told what
 * "expired" means, and cannot be made to drop anything a day early.
 */
@Component
@ConditionalOnProperty(name = "horecaos.telemetry.retention.enabled",
        havingValue = "true", matchIfMissing = true)
public class TrackRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(TrackRetentionSweeper.class);

    /** How long a pin outlives its duty session. */
    public static final Duration LIVE_ROW_GRACE = Duration.ofHours(1);

    /** How far ahead daily partitions are kept. */
    private static final int DAYS_AHEAD = 14;

    private final JdbcClient jdbc;
    private final JdbcTelemetryStore store;
    private final Clock clock;
    private final boolean reportOnly;
    private final int configuredRetentionDays;

    public TrackRetentionSweeper(JdbcClient jdbc, JdbcTelemetryStore store, Clock clock,
            @Value("${horecaos.telemetry.retention.report-only:false}") boolean reportOnly,
            @Value("${horecaos.telemetry.retention.days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.store = store;
        this.clock = clock;
        this.reportOnly = reportOnly;
        this.configuredRetentionDays = retentionDays;
    }

    @Scheduled(
            initialDelayString = "${horecaos.telemetry.retention.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.telemetry.retention.interval:PT1H}")
    public void sweep() {
        ensurePartitions();
        expireLivePositions();
        dropExpiredPartitions();
    }

    /** Creates today's partition and the fortnight after it. Idempotent. */
    public void ensurePartitions() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (int offset = 0; offset <= DAYS_AHEAD; offset++) {
            ensurePartition(today.plusDays(offset));
        }
    }

    /**
     * The DDL is V0075's {@code fulfillment.ensure_track_partition}, not this
     * class's.
     *
     * <p>It was this class's, and it stopped working the day the application
     * connected as {@code horecaos_application}: the CREATE became {@code permission
     * denied for schema fulfillment} and the GRANT that followed it became
     * something worse than a failure — a role with no grant option granting to
     * itself draws {@code WARNING: no privileges were granted}, moves nothing,
     * and reports success. Because {@link #sweep()} calls this first, the throw
     * took {@link #expireLivePositions()} and {@link #dropExpiredPartitions()}
     * with it, and ADR 0029's retention of courier tracks simply stopped while
     * the job kept logging a stack trace once an hour.
     *
     * <p>The day crosses into the function as a {@code date}, so the identifier
     * is a {@code to_char} of a value the type system has already proven and not
     * a string this class formatted.
     *
     * @return whether this call was the one that created it
     */
    public boolean ensurePartition(LocalDate day) {
        boolean created = jdbc.sql("SELECT fulfillment.ensure_track_partition(:day)")
                .param("day", day)
                .query(Boolean.class)
                .single();

        if (created) {
            log.info("Created courier track partition for {}", day);
        }
        return created;
    }

    /**
     * Deletes the pins of sessions that closed more than an hour ago.
     *
     * @return how many were removed, logged so a retention rule that quietly
     *         stopped running looks different from one with nothing to do
     */
    public int expireLivePositions() {
        if (reportOnly) {
            log.info("Telemetry retention is report-only: live positions past the {} grace "
                    + "would be deleted", LIVE_ROW_GRACE);
            return 0;
        }
        int removed = store.deleteLivePositionsForSessionsClosedBefore(
                clock.instant().minus(LIVE_ROW_GRACE));
        if (removed > 0) {
            log.info("Removed {} live courier positions past the {} grace after sign-off",
                    removed, LIVE_ROW_GRACE);
        }
        return removed;
    }

    /**
     * Asks for the sweep. Does not define it.
     *
     * <p>This is the one operation here that destroys personal data on purpose,
     * and after V0075 the judgement behind it does not live in this class. It
     * lives in {@code fulfillment.sweep_expired_track_partitions}, owned by the
     * migration role, and the difference is not bookkeeping:
     *
     * <ul>
     *   <li>No table is named, because the function has no parameter for one. It
     *       enumerates the children of one parent through {@code pg_inherits}, so
     *       what can possibly be dropped is a property of the schema rather than
     *       of anything this class computes.
     *   <li>The cutoff is the database's {@code current_date}. The injected
     *       {@link Clock} that this class uses everywhere else is deliberately
     *       not consulted: a retention window an injected clock can move is not a
     *       retention control, and a test that advances a fixture clock must not
     *       be able to delete a real day early.
     *   <li>{@link #effectiveRetentionDays()} is passed in and is one term of a
     *       {@code GREATEST} on the far side, alongside the ADR 0045 platform
     *       floor and the longest window any tenant configured — which the
     *       function re-derives for itself rather than trusting. So this argument
     *       can only ever lengthen the window. Getting it wrong, or being made to
     *       get it wrong, drops less and never more.
     * </ul>
     *
     * <p>The default partition is never dropped and never considered. Rows in it
     * are a symptom — a day that had no partition when observations arrived — and
     * they are reported rather than deleted, because deleting the default would
     * take every future misrouted row with it. It has no upper bound to be older
     * than the cutoff, so it falls out of the function's test rather than needing
     * to be excluded by name; it is excluded by name as well.
     *
     * @return the partitions dropped, or in report-only mode the ones that would
     *         have been
     */
    public List<String> dropExpiredPartitions() {
        int retentionDays = effectiveRetentionDays();

        List<String> expired = jdbc.sql(
                "SELECT fulfillment.sweep_expired_track_partitions(:days, :reportOnly)")
                .param("days", retentionDays)
                .param("reportOnly", reportOnly)
                .query(String.class)
                .list();

        if (expired.isEmpty()) {
            return expired;
        }
        if (reportOnly) {
            log.info("Telemetry retention is report-only: {} courier track partitions are past "
                    + "the {}-day window and would be dropped: {}",
                    expired.size(), retentionDays, expired);
        } else {
            log.info("Dropped {} courier track partitions past the {}-day retention window: {}",
                    expired.size(), retentionDays, expired);
        }
        return expired;
    }

    /** Rows here mean a day had no partition when its observations arrived. */
    public long defaultPartitionRowCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_location_tracks_default")
                .query(Long.class)
                .single();
    }

    /**
     * The window this job actually drops at: the platform default, or the longest
     * value any tenant configured, whichever is greater.
     *
     * <p>Read with plain SQL rather than through the ADR 0030 resolver, because
     * the resolver answers "what applies at this scope" and a partition has no
     * scope — it holds every tenant's rows at once.
     */
    public int effectiveRetentionDays() {
        Long longest = jdbc.sql("""
                SELECT max(integer_value) FROM tenant.configuration_values
                 WHERE key_code = :keyCode AND is_explicit_null = false
                """)
                .param("keyCode", TelemetryConfigurationKeys.TRACK_RETENTION_DAYS_CODE)
                .query(Long.class)
                .optional()
                .orElse(null);

        return longest == null
                ? configuredRetentionDays
                : Math.max(configuredRetentionDays, Math.toIntExact(longest));
    }
}
