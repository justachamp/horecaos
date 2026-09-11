package uz.horecaos.platform.iam.application.passwordresets;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.iam.api.audit.StaffSecurityAudit;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender.Delivery;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcPasswordResetStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcPasswordResetStore.Row;

/**
 * Sends queued password resets (ADR 0098).
 *
 * <p>ADR 0097's {@code OwnerInvitationRelay} in every mechanical respect, and
 * deliberately so: claim, then send, then record -- three steps, never one
 * transaction. The claim pushes the row a lease into the future, so a slow
 * mail server holds no lock and a crashed replica's claim simply comes due
 * again. The token is made here, emailed, and only its hash recorded; if
 * recording fails after the email went, the row comes due again and a second
 * email carries a new, working link, which beats a staff member holding a link
 * the platform cannot match.
 *
 * <p>The address is read from the identity provider for each send and kept in
 * no variable longer than the send. Logs carry counts and codes.
 */
@Component
public class PasswordResetRelay {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRelay.class);

    /** After this many real attempts a failing send stops retrying; the staff member asks again. */
    static final int MAX_ATTEMPTS = 8;

    static final Duration LEASE = Duration.ofMinutes(5);

    /**
     * How long to wait when no mail server is configured.
     *
     * <p>Shorter than ADR 0097's fifteen minutes, because a reset link only
     * lives an hour: a deployment whose mail settings arrive mid-wait should
     * not spend a third of the link's life queued behind the wait itself.
     */
    static final Duration UNCONFIGURED_WAIT = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcPasswordResetStore store;
    private final StaffAccounts accounts;
    private final StaffEmailSender mailer;
    private final StaffSecurityAudit audit;
    private final Clock clock;
    private final Map<StaffConsole, String> origins;

    public PasswordResetRelay(
            JdbcPasswordResetStore store,
            StaffAccounts accounts,
            StaffEmailSender mailer,
            StaffSecurityAudit audit,
            Clock clock,
            @Value("${horecaos.frontends.operations-origin:http://localhost:4200}") String operationsOrigin,
            @Value("${horecaos.frontends.control-plane-origin:http://localhost:4300}") String controlPlaneOrigin) {
        this.store = store;
        this.accounts = accounts;
        this.mailer = mailer;
        this.audit = audit;
        this.clock = clock;
        this.origins = Map.of(
                StaffConsole.OPERATIONS, trimmed(operationsOrigin),
                StaffConsole.CONTROL_PLANE, trimmed(controlPlaneOrigin));
    }

    private static String trimmed(String origin) {
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    @Scheduled(
            initialDelayString = "${horecaos.iam.password-resets.initial-delay:PT45S}",
            fixedDelayString = "${horecaos.iam.password-resets.interval:PT30S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The password reset relay could not run", failure);
        }
    }

    /** @return how many resets this pass sent, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<Row> claimed = store.claimDue(now, LEASE, 20);
        int sent = 0;
        for (Row row : claimed) {
            try {
                if (deliver(row, now)) {
                    sent++;
                }
            } catch (RuntimeException failure) {
                // One reset's failure must not stop the rest; the lease brings
                // this one back.
                log.error(
                        "A password reset could not be processed ({})",
                        failure.getClass().getSimpleName());
            }
        }
        if (!claimed.isEmpty()) {
            log.info("Password reset relay: {} claimed, {} sent", claimed.size(), sent);
        }
        return sent;
    }

    private boolean deliver(Row row, Instant now) {
        Optional<StaffAccount> account;
        try {
            account = accounts.find(row.subjectId());
        } catch (RuntimeException unavailable) {
            retry(row, now, "IDENTITY_UNAVAILABLE");
            return false;
        }
        if (account.isEmpty()) {
            store.markFailed(row.id(), row.attempts(), "STAFF_ACCOUNT_MISSING");
            return false;
        }

        String token = newToken();
        Instant expiresAt = now.plus(PasswordResetService.LINK_LIFETIME);
        StaffConsole console = StaffConsole.valueOf(row.console());
        String origin = java.util.Objects.requireNonNull(origins.get(console), "every console has an origin");
        Delivery delivery = mailer.send(PasswordResetEmail.render(
                account.get().email(),
                row.locale(),
                origin + "/reset-password#token=" + token,
                PasswordResetService.LINK_LIFETIME.toMinutes()));

        switch (delivery.status()) {
            case SENT -> {
                if (store.markSent(row.id(), row.attempts(), PasswordResetService.hash(token), expiresAt, now)) {
                    audit.record(StaffSecurityFact.bySystemJob(
                            "iam.password_reset.sent",
                            "password-reset-relay",
                            "iam.password_reset",
                            row.id(),
                            "A staff member's password reset link was emailed (ADR 0098)",
                            Map.of(
                                    "attempt", row.attempts(),
                                    "console", row.console(),
                                    "expiresAt", expiresAt.toString()),
                            UUID.randomUUID().toString(),
                            now));
                    return true;
                }
                return false;
            }
            case NOT_CONFIGURED ->
                store.markRetry(row.id(), row.attempts(), now.plus(UNCONFIGURED_WAIT), delivery.code(), false);
            case REJECTED -> store.markFailed(row.id(), row.attempts(), delivery.code());
            case FAILED -> retry(row, now, delivery.code());
        }
        return false;
    }

    private void retry(Row row, Instant now, String code) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            store.markFailed(row.id(), row.attempts(), code);
        } else {
            store.markRetry(row.id(), row.attempts(), now.plus(backoff(row.attempts())), code, true);
        }
    }

    /** 1, 2, 4 ... minutes, capped at an hour. */
    static Duration backoff(int attempts) {
        long minutes = 1L << Math.min(Math.max(attempts - 1, 0), 6);
        return Duration.ofMinutes(Math.min(minutes, 60));
    }

    /** 256 random bits, URL-safe: long enough that guessing one is not a strategy. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
