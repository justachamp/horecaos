package uz.horecaos.platform.iam.application.sessions;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffSignInStore;

/**
 * Two housekeeping passes over {@code iam.staff_sign_ins} (ADR 0176).
 *
 * <p><strong>Idle expiry</strong> closes, as {@link EndReason#EXPIRED}, a row still open whose
 * session has not been seen for longer than Keycloak keeps an idle offline session (its default is
 * thirty days; the realm file sets none). It is what makes a row the platform believed open
 * eventually read as ended when nothing ended it, such as a session in another company that an
 * act here did not touch. It deletes nothing.
 *
 * <p><strong>Retention</strong> deletes rows older than {@code iam.staff_sign_in_retention_days}
 * (default 180). Destructive, so it runs in {@link Mode#REPORT_ONLY} first as ADR 0029 requires:
 * a pass counts what it would delete, logs the count (a number, no identity) and sets a gauge,
 * and writes nothing, until the mode is switched on a deployment that has seen the report. One
 * global pass cannot honour a window per tenant, so it uses the longest value any tenant stored.
 */
@Component
public class StaffSignInSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaffSignInSweeper.class);

    /**
     * The ADR 0030 key the retention is configured by, and its default. Declared only in the
     * tenancy registry ({@code ConfigurationKeys.IAM_STAFF_SIGN_IN_RETENTION_DAYS}), as {@code
     * iam.staff_mfa_requirement} is: iam importing the registry's {@code ConfigurationKey} type
     * would close an {@code iam -> tenancy -> audit -> iam} cycle, so this module holds the code and
     * the default as constants and {@code IamConfigurationKeyTests} keeps them equal to the
     * registry's declaration.
     */
    public static final String RETENTION_KEY_CODE = "iam.staff_sign_in_retention_days";

    public static final int DEFAULT_RETENTION_DAYS = 180;

    public enum Mode {
        /** Count and report; delete nothing. The default. */
        REPORT_ONLY,
        /** Delete rows past retention. */
        ENFORCE
    }

    private final JdbcStaffSignInStore store;
    private final Clock clock;
    private final Mode mode;
    private final int defaultRetentionDays;
    private final Duration idleExpiry;
    private final int batchSize;
    private final AtomicLong due = new AtomicLong();

    public StaffSignInSweeper(
            JdbcStaffSignInStore store,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.iam.staff-sign-ins.retention.mode:REPORT_ONLY}") Mode mode,
            @Value("${horecaos.iam.staff-sign-ins.retention.days:180}") int defaultRetentionDays,
            @Value("${horecaos.iam.staff-sign-ins.idle-expiry:P30D}") Duration idleExpiry,
            @Value("${horecaos.iam.staff-sign-ins.batch-size:500}") int batchSize) {
        this.store = store;
        this.clock = clock;
        this.mode = mode;
        this.defaultRetentionDays = defaultRetentionDays;
        this.idleExpiry = idleExpiry;
        this.batchSize = batchSize;
        Gauge.builder("horecaos.iam.staff_sign_ins.retention_due", due, AtomicLong::doubleValue)
                .description("Staff sign-in rows past retention and not yet deleted")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.iam.staff-sign-ins.initial-delay:PT5M}",
            fixedDelayString = "${horecaos.iam.staff-sign-ins.interval:PT6H}")
    public void sweepOnce() {
        try {
            expireIdle();
            applyRetention();
        } catch (RuntimeException failure) {
            log.error("The staff sign-in sweep could not run", failure);
        }
    }

    /** @return how many open rows were closed as expired */
    public int expireIdle() {
        Instant now = clock.instant();
        int expired = store.expireIdleBefore(now.minus(idleExpiry), now, batchSize);
        if (expired > 0) {
            log.info("Staff sign-in idle expiry: {} sessions closed as expired", expired);
        }
        return expired;
    }

    /** @return how many rows were deleted, or would have been in report-only mode */
    public int applyRetention() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(effectiveRetentionDays()));
        if (mode == Mode.REPORT_ONLY) {
            int pending = store.countSignedInBefore(cutoff);
            due.set(pending);
            if (pending > 0) {
                log.info(
                        "Staff sign-in retention (report-only): {} rows are past retention and would be deleted",
                        pending);
            }
            return pending;
        }
        int deleted = store.deleteSignedInBefore(cutoff, batchSize);
        due.set(Math.max(0, store.countSignedInBefore(cutoff)));
        if (deleted > 0) {
            log.info("Staff sign-in retention: {} rows deleted", deleted);
        }
        return deleted;
    }

    int effectiveRetentionDays() {
        Long longest = store.longestTenantRetentionDays(RETENTION_KEY_CODE);
        return longest == null ? defaultRetentionDays : Math.max(defaultRetentionDays, Math.toIntExact(longest));
    }
}
