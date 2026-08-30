package uz.horecaos.platform.audit.infrastructure.persistence;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps audit partitions ahead of the clock (ADR 0027).
 *
 * <p>The migration created partitions for a few years plus a default. The
 * default exists so an audited action can never fail for want of a partition,
 * but rows landing there are a symptom, not a design: they have to be moved
 * before that range can get a partition of its own.
 *
 * <p>Creating next year's partition well in advance is the cheap way to stop
 * that happening at all. Running it daily is deliberate over-provisioning of a
 * very cheap operation.
 *
 * <p>It runs here rather than at deploy time, where the migration role already
 * holds everything outright, because a partition provisioner has to run on the
 * calendar and deployments do not happen on one. The privilege that costs is
 * narrowed instead of the schedule: the DDL is V0075's
 * {@code audit.ensure_event_partition}, and this class holds nothing but EXECUTE
 * on it. {@code ReportingPartitionManager} makes the same trade against
 * {@code reporting.ensure_fact_partition}.
 */
@Component
@ConditionalOnProperty(name = "horecaos.audit.partitions.enabled", havingValue = "true", matchIfMissing = true)
public class AuditPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionManager.class);

    /** How far ahead partitions are kept. Two years leaves room for a missed run. */
    private static final int YEARS_AHEAD = 2;

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditPartitionManager(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.audit.partitions.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.audit.partitions.interval:P1D}")
    public void ensurePartitions() {
        int currentYear = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();

        for (int year = currentYear; year <= currentYear + YEARS_AHEAD; year++) {
            ensurePartition(year);
        }
    }

    /**
     * Creating one year's partition. Idempotent, so a repeated run is harmless.
     *
     * <p>This class used to issue the {@code CREATE TABLE ... PARTITION OF} and
     * the {@code GRANT} itself, which worked for as long as the application
     * connected as the role that owns the database. It does not any more. Under
     * {@code horecaos_application} the CREATE is {@code permission denied for schema
     * audit}, and the GRANT — a role with no grant option granting to itself — is
     * worse than a failure: PostgreSQL answers it with {@code WARNING: no
     * privileges were granted}, moves nothing, and reports success. A partition
     * created that way would be unreadable and unwritable by the role that made
     * it, and nothing would say so.
     *
     * <p>So the DDL lives in {@code audit.ensure_event_partition}, a V0075
     * {@code SECURITY DEFINER} function owned by the migration role, and the year
     * crosses that boundary as an {@code integer}. There is no table name in the
     * call because there is no parameter for one: the function builds the only
     * relation name it will ever touch from four digits of {@code to_char}.
     *
     * <p>The race between replicas is handled there too, and better than it was
     * here. Every container runs this job and their daily timers drift towards
     * each other, so check-then-act is not idempotent where it matters: two
     * replicas see no partition, both issue the DDL, and the loser fails on a
     * race whose outcome was correct. Inside the function the failed CREATE is a
     * subtransaction that rolls back on its own, so the re-check runs against a
     * catalogue that already contains the winner's table — where the Java version
     * needed the job to be running outside a transaction for the same recovery to
     * be possible at all.
     *
     * @return whether this call was the one that created it
     */
    public boolean ensurePartition(int year) {
        boolean created = jdbc.sql("SELECT audit.ensure_event_partition(:year)")
                .param("year", year)
                .query(Boolean.class)
                .single();

        if (created) {
            log.info("Created audit partition audit_events_{}", year);
        }
        return created;
    }

    /** Rows in the default partition mean a range had no partition when they arrived. */
    public long defaultPartitionRowCount() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events_default").query(Long.class).single();
    }
}
