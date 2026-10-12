package uz.horecaos.platform.iam.application.sessions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffSignInStore;

/**
 * The platform's record of staff sessions (ADR 0176): writes a sign-in row, moves {@code
 * last_seen_at}, and is <strong>the single place a row is marked ended</strong>, so the history
 * always says why.
 *
 * <p>Keycloak stays the authority on whether a session is alive. A row here says what the session
 * was (a device label out of three closed sets, and times) and never decides it is still alive.
 *
 * <p><strong>The record never fails a sign-in.</strong> {@link #recordSignIn}, {@link #touch} and
 * the sign-out and reset markers swallow their own failures into a counter and a log line that
 * names no one: a person who typed the right password is signed in whether or not their history
 * was written. The calls that must be atomic with an audit fact ({@link #endAllInTenant}, {@link
 * #endOne}) are the opposite, they throw, and their caller owns the transaction.
 *
 * <p>Never receives, stores or logs a {@code User-Agent}, an address or a token: the only inputs
 * are a subject, a session id, organization ids and an already-parsed {@link DeviceLabel}.
 */
@Service
public class StaffSessionLedger {

    private static final Logger log = LoggerFactory.getLogger(StaffSessionLedger.class);

    /** A refresh happens every few minutes; a "last seen" coarser than this is all the screen needs. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(10);

    private final JdbcStaffSignInStore store;
    private final DeviceLabelParser parser;
    private final Clock clock;
    private final MeterRegistry meters;

    public StaffSessionLedger(JdbcStaffSignInStore store, DeviceLabelParser parser, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.parser = parser;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Records the sign-in for each tenant the token names in which the person has a record.
     *
     * <p>A token that names no organization (a platform-scope account) or carries no session id is
     * not recorded: there is no tenant to attribute a row to, or no session to point the row at.
     * Never throws.
     */
    public void recordSignIn(
            String subjectId, Set<String> organizationIds, @Nullable String sessionId, @Nullable String userAgent) {
        try {
            if (sessionId == null || organizationIds.isEmpty()) {
                count("sign_in", "skipped");
                return;
            }
            DeviceLabel label = parser.parse(userAgent);
            int written = store.recordSignIn(subjectId, organizationIds, sessionId, label, clock.instant());
            count("sign_in", written > 0 ? "recorded" : "skipped");
        } catch (RuntimeException failed) {
            // Names no one: not the subject, not the session, not the exception message (a
            // constraint violation message echoes the row).
            count("sign_in", "failed");
            log.warn(
                    "A staff sign-in could not be recorded ({})",
                    failed.getClass().getSimpleName());
        }
    }

    /** A refresh: moves {@code last_seen_at} forward, at most once in ten minutes. Never throws. */
    public void touch(@Nullable String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            Instant now = clock.instant();
            store.touch(sessionId, now, now.minus(TOUCH_INTERVAL));
        } catch (RuntimeException failed) {
            count("touch", "failed");
            log.warn(
                    "A staff session's last-seen time could not be written ({})",
                    failed.getClass().getSimpleName());
        }
    }

    /** The person signed out of this device. Never throws. */
    public void signedOut(@Nullable String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            store.endSession(sessionId, EndReason.SIGNED_OUT, clock.instant());
        } catch (RuntimeException failed) {
            count("sign_out", "failed");
            log.warn(
                    "A staff sign-out could not be recorded ({})",
                    failed.getClass().getSimpleName());
        }
    }

    /**
     * ADR 0098's reset ended every session of the account. The caller invokes this <em>only</em>
     * after {@code logoutEverywhere} has succeeded: a history that says sessions ended when they
     * did not is the failure ADR 0098 calls worse than none. Marks the subject's rows in every
     * tenant, because the unauthenticated reset path has no tenant. Never throws: the rows then
     * read as ended because Keycloak no longer lists the session, and the idle sweep closes them.
     */
    public void endedByPasswordReset(String subjectId) {
        try {
            store.endAllEverywhere(subjectId, EndReason.PASSWORD_RESET, clock.instant());
        } catch (RuntimeException failed) {
            count("password_reset_mark", "failed");
            log.warn(
                    "A password reset could not be written to the sign-in history ({})",
                    failed.getClass().getSimpleName());
        }
    }

    /**
     * The person ended every session from the card. Throws, because it runs in the transaction
     * that also writes the audit fact, and the two succeed or fail together.
     *
     * @return how many rows were ended in this tenant
     */
    public int endAllInTenant(UUID tenantId, String subjectId) {
        return store.endAllInTenant(tenantId, subjectId, EndReason.SIGNED_OUT_EVERYWHERE, clock.instant());
    }

    /** The person ended one session from the list. Throws, for the reason above. */
    public int endOne(String subjectId, String sessionId) {
        return store.endSessionOf(subjectId, sessionId, EndReason.ENDED_ONE, clock.instant());
    }

    private void count(String operation, String outcome) {
        Counter.builder("horecaos.iam.staff_session.ledger")
                .description("Staff session ledger writes, by operation and outcome. Labels identify no one.")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
