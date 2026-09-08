package uz.horecaos.platform.audit.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.AuditArchivalNotVerifiedException;
import uz.horecaos.platform.audit.api.AuditArchiveStore;
import uz.horecaos.platform.audit.api.AuditConfigurationKeys;

/**
 * Moves closed audit partitions to protected storage and drops them from the
 * live table, and only in that order (ADR 0027).
 *
 * <p><strong>Closed, not expired.</strong> {@code AuditPartitionManager} keeps
 * partitions two years ahead of the clock; a partition is a candidate here the
 * moment its own upper bound has passed, which is well before either retention
 * key's ten-year window has anything to say. Retention decides how long the
 * archive object must stay locked, not when the live partition is moved off
 * primary storage — the physical model in ADR 0027 already says so:
 * "Retention is enforced by archival to retention-protected storage, not by
 * deletion in place."
 *
 * <p><strong>The order that matters.</strong> {@link #archiveOnce} never calls
 * {@code audit.drop_archived_partition} for a year it has not itself just
 * marked, or found already marked, {@code VERIFIED} in {@code
 * audit.partition_archives} — and that function re-checks the same row for
 * itself rather than trusting the call (see V0188). A failed or unverified
 * archive attempt leaves the row {@code PENDING} and the live partition
 * untouched; the next run tries again. There is no path from "the upload threw"
 * or "the read-back did not match" to a dropped partition.
 *
 * <p><strong>Retention is read with plain SQL, not through {@code
 * tenancy.api.ConfigurationResolver}.</strong> {@code tenancy} already depends on
 * {@code audit.api} — {@code JdbcConfigurationValueAuthor} records an ADR 0027 fact
 * on every configuration write — so this class taking a {@code ConfigurationResolver}
 * would close a cycle Spring Modulith refuses to build.
 * {@code TrackRetentionSweeper.effectiveRetentionDays()} solves the identical
 * problem the identical way, for the identical reason: the resolver answers "what
 * applies at this scope", and a partition has no scope — it holds every tenant's
 * rows at once. Both ADR 0027 retention keys are platform-scope-only, so there is
 * no chain to walk here either; see {@link #retentionDays}.
 *
 * <p>Erasure is deliberately out of scope for this class. Whether a data
 * subject's erasure request must reach an archived audit object is an open
 * product question ADR 0027 now records rather than answers — see this record's
 * Open inputs. Nothing here forecloses it: the archive is a per-tenant-readable
 * NDJSON export addressed by year, not an opaque blob, so a future erasure path
 * has a concrete artifact to act on if the answer turns out to be yes.
 */
@Component
@ConditionalOnProperty(name = "horecaos.audit.archive.enabled", havingValue = "true", matchIfMissing = true)
public class AuditPartitionArchiver {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionArchiver.class);

    private static final Pattern PARTITION_NAME = Pattern.compile("^audit_events_([0-9]{4})$");

    /**
     * Duplicates {@code ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS}' and
     * {@code AUDIT_BUSINESS_RETENTION_DAYS}' registered default (both 3,653 —
     * the owner's ten-year directive). A stored {@code PLATFORM} row always wins
     * over this; it is reached only when no operator has ever set one, the same
     * shape {@code TrackRetentionSweeper}'s own duplicated floor uses and for
     * the same reason: this is a last line of defence for a value that gates
     * how long evidence stays locked, not a value convenient to keep in one
     * place only.
     */
    private static final int DEFAULT_RETENTION_DAYS = 3653;

    private final JdbcClient jdbc;
    private final AuditArchiveStore archive;
    private final Clock clock;

    public AuditPartitionArchiver(JdbcClient jdbc, AuditArchiveStore archive, Clock clock) {
        this.jdbc = jdbc;
        this.archive = archive;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.audit.archive.initial-delay:PT3M}",
            fixedDelayString = "${horecaos.audit.archive.interval:P1D}")
    public void archiveClosedPartitions() {
        archiveOnce();
    }

    /**
     * @return the years actually moved to protected storage and dropped from the
     *     live table on this call — never a year whose archive could not be
     *     verified, whether that happened just now or on an earlier run
     */
    public List<Integer> archiveOnce() {
        List<Integer> droppedThisRun = new ArrayList<>();

        for (int year : closedPartitionYears()) {
            String status = currentStatus(year);

            if ("DROPPED".equals(status)) {
                continue;
            }
            if (status == null || "PENDING".equals(status)) {
                if (!archiveYear(year)) {
                    // Not verified this run. The row stays PENDING and the live
                    // partition stays exactly where it is; retried next run.
                    continue;
                }
            }

            if (dropYear(year)) {
                droppedThisRun.add(year);
            }
        }

        return droppedThisRun;
    }

    /**
     * The calendar years {@code audit.ensure_event_partition} has provisioned
     * whose own upper bound has already passed — read from the partition's name
     * against the database's own partitioning scheme, the same
     * {@code pg_inherits} technique {@code fulfillment.sweep_expired_track_partitions}
     * uses, but as a plain read: nothing here is destructive, so it needs no
     * {@code SECURITY DEFINER} escalation, only the {@code SELECT} on system
     * catalogues every role already holds.
     */
    private List<Integer> closedPartitionYears() {
        // Schema-qualified even though this is an ordinary read under the
        // application's own, unprivileged search_path — never SECURITY DEFINER,
        // so there is no elevation for a shadowed name to buy here. Qualified
        // anyway, to match the discipline the V0075/V0080 functions this class
        // calls into already hold themselves to.
        List<String> names = jdbc.sql("""
                        SELECT c.relname
                          FROM pg_catalog.pg_inherits i
                          JOIN pg_catalog.pg_class c      ON c.oid = i.inhrelid
                          JOIN pg_catalog.pg_class p      ON p.oid = i.inhparent
                          JOIN pg_catalog.pg_namespace pn ON pn.oid = p.relnamespace
                         WHERE pn.nspname = 'audit' AND p.relname = 'audit_events'
                           AND c.relname ~ '^audit_events_[0-9]{4}$'
                         ORDER BY c.relname
                        """).query(String.class).list();

        Instant now = clock.instant();
        List<Integer> closed = new ArrayList<>();
        for (String name : names) {
            Matcher matcher = PARTITION_NAME.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            int year = Integer.parseInt(matcher.group(1));
            if (!now.isBefore(yearStart(year + 1))) {
                closed.add(year);
            }
        }
        return closed;
    }

    /**
     * Exports the year's rows, archives them, and — only if the store proves
     * both durability and the retention lock — records the row as
     * {@code VERIFIED}.
     *
     * @return whether the year is now verified, whether by this call or by
     *     already having been
     */
    private boolean archiveYear(int year) {
        Instant lower = yearStart(year);
        Instant upper = yearStart(year + 1);
        String partitionName = "audit_events_" + year;

        List<String> rows = jdbc.sql("""
                        SELECT to_jsonb(t)::text AS doc
                          FROM audit.audit_events t
                         WHERE recorded_at >= :lower AND recorded_at < :upper
                         ORDER BY recorded_at, id
                        """)
                .param("lower", lower.atOffset(ZoneOffset.UTC))
                .param("upper", upper.atOffset(ZoneOffset.UTC))
                .query(String.class)
                .list();

        byte[] content = String.join("\n", rows).getBytes(StandardCharsets.UTF_8);

        int retentionDays = Math.max(
                retentionDays(AuditConfigurationKeys.SECURITY_RETENTION_DAYS_CODE),
                retentionDays(AuditConfigurationKeys.BUSINESS_RETENTION_DAYS_CODE));
        // Measured from the partition's own upper bound, not from whenever this
        // job happens to run — a run delayed by a quiet month must not silently
        // shorten what an on-time archival would have promised.
        Instant retainUntil = upper.plus(Duration.ofDays(retentionDays));

        markPending(year, partitionName, rows.size());

        AuditArchiveStore.ArchiveReceipt receipt;
        try {
            receipt = archive.archiveAndVerify(objectKey(year), content, retainUntil);
        } catch (AuditArchivalNotVerifiedException notVerified) {
            log.error(
                    "ADR 0027: archival of {} could not be verified; the live partition is not touched",
                    partitionName,
                    notVerified);
            return false;
        }

        markVerified(year, receipt);
        log.info(
                "ADR 0027: {} archived to protected storage ({} rows, {} bytes, locked until {})",
                partitionName,
                rows.size(),
                content.length,
                receipt.retainUntil());
        return true;
    }

    private boolean dropYear(int year) {
        Boolean dropped = jdbc.sql("SELECT audit.drop_archived_partition(:year)")
                .param("year", year)
                .query(Boolean.class)
                .single();

        if (Boolean.TRUE.equals(dropped)) {
            log.info(
                    "ADR 0027: dropped audit_events_{} from the live table; its evidence is verified in "
                            + "protected storage",
                    year);
        }
        return Boolean.TRUE.equals(dropped);
    }

    private @Nullable String currentStatus(int year) {
        return jdbc.sql("SELECT status FROM audit.partition_archives WHERE year = :year")
                .param("year", year)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void markPending(int year, String partitionName, long rowCount) {
        jdbc.sql("""
                        INSERT INTO audit.partition_archives (year, partition_name, status, row_count)
                        VALUES (:year, :partitionName, 'PENDING', :rowCount)
                        ON CONFLICT (year) DO UPDATE
                           SET row_count = excluded.row_count, updated_at = now()
                         WHERE audit.partition_archives.status = 'PENDING'
                        """)
                .param("year", year)
                .param("partitionName", partitionName)
                .param("rowCount", rowCount)
                .update();
    }

    private void markVerified(int year, AuditArchiveStore.ArchiveReceipt receipt) {
        jdbc.sql("""
                        UPDATE audit.partition_archives
                           SET status = 'VERIFIED', bucket = :bucket, object_key = :objectKey,
                               sha256_base64 = :sha256, size_bytes = :sizeBytes,
                               retain_until = :retainUntil, verified_at = now(), updated_at = now()
                         WHERE year = :year
                        """)
                .param("year", year)
                .param("bucket", receipt.bucket())
                .param("objectKey", receipt.key())
                .param("sha256", receipt.sha256Base64())
                .param("sizeBytes", receipt.sizeBytes())
                .param("retainUntil", receipt.retainUntil().atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The stored {@code PLATFORM}-scope value for one ADR 0030 key, or {@link
     * #DEFAULT_RETENTION_DAYS} when nothing has ever been stored — read with
     * plain SQL rather than {@code ConfigurationResolver} for the reason this
     * class's own doc gives. Both keys are declared {@code settableAt(PLATFORM)}
     * only (see {@code ConfigurationKeys} and the equality test that pins it),
     * so there is exactly one row to find and no scope chain to walk.
     */
    private int retentionDays(String keyCode) {
        return jdbc.sql("""
                        SELECT integer_value FROM tenant.configuration_values
                         WHERE key_code = :keyCode AND scope_type = 'PLATFORM' AND is_explicit_null = false
                        """)
                .param("keyCode", keyCode)
                .query(Integer.class)
                .optional()
                .orElse(DEFAULT_RETENTION_DAYS);
    }

    /** Immutable, generated, and year-addressed — never an untrusted name (AGENTS.md's S3 rules). */
    private static String objectKey(int year) {
        return "audit-events/%d/audit_events_%d.ndjson".formatted(year, year);
    }

    private static Instant yearStart(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
