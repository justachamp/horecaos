package uz.horecaos.platform.tenancy.application.invitations;

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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore.Row;

/**
 * Sends queued owner invitations (ADR 0097).
 *
 * <p>Claim, then send, then record -- three steps, never one transaction. The
 * claim pushes the row a lease into the future, so a slow mail server holds
 * no lock and a crashed replica's claim simply comes due again. The token is
 * made here, emailed, and only its hash recorded; if recording fails after the
 * email went, the row comes due again and a second email carries a new,
 * working link, which beats an owner holding a link the platform cannot match.
 *
 * <p>The address is read from the identity provider for each send and kept in
 * no variable longer than the send. Logs carry counts and codes.
 */
@Component
public class OwnerInvitationRelay {

    private static final Logger log = LoggerFactory.getLogger(OwnerInvitationRelay.class);

    /** After this many real attempts a failing send stops retrying and waits for a person. */
    static final int MAX_ATTEMPTS = 8;

    static final Duration LEASE = Duration.ofMinutes(5);

    /** How long to wait when no mail server is configured: long enough not to spin, short enough to notice. */
    static final Duration UNCONFIGURED_WAIT = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcOwnerInvitationStore store;
    private final StaffAccounts accounts;
    private final PlatformMailer mailer;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String operationsOrigin;

    public OwnerInvitationRelay(
            JdbcOwnerInvitationStore store,
            StaffAccounts accounts,
            PlatformMailer mailer,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.frontends.operations-origin:http://localhost:4200}") String operationsOrigin) {
        this.store = store;
        this.accounts = accounts;
        this.mailer = mailer;
        this.audit = audit;
        this.clock = clock;
        this.operationsOrigin = operationsOrigin.endsWith("/")
                ? operationsOrigin.substring(0, operationsOrigin.length() - 1)
                : operationsOrigin;
    }

    @Scheduled(
            initialDelayString = "${horecaos.tenancy.owner-invitations.initial-delay:PT45S}",
            fixedDelayString = "${horecaos.tenancy.owner-invitations.interval:PT30S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The owner invitation relay could not run", failure);
        }
    }

    /** @return how many invitations this pass sent, for a deterministic test */
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
                // One invitation's failure must not stop the rest; the lease
                // brings this one back.
                log.error(
                        "An owner invitation could not be processed ({})",
                        failure.getClass().getSimpleName());
            }
        }
        if (!claimed.isEmpty()) {
            log.info("Owner invitation relay: {} claimed, {} sent", claimed.size(), sent);
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
            store.markFailed(row.id(), row.attempts(), "OWNER_ACCOUNT_MISSING");
            return false;
        }
        if (account.get().hasPassword()) {
            store.markNotNeeded(row.id(), row.attempts());
            return false;
        }

        String token = newToken();
        Instant expiresAt = now.plus(OwnerInvitationService.LINK_LIFETIME);
        MailOutcome outcome = mailer.send(InvitationEmail.render(
                account.get().email(),
                row.locale(),
                store.tenantName(row.tenantId()),
                operationsOrigin + "/invite#token=" + token,
                OwnerInvitationService.LINK_LIFETIME.toHours()));

        switch (outcome) {
            case MailOutcome.Sent ignored -> {
                if (store.markSent(row.id(), row.attempts(), OwnerInvitationService.hash(token), expiresAt, now)) {
                    audit.record(AuditFact.of("tenant.owner_invitation.sent", AuditClass.BUSINESS)
                            .by(ActorRef.systemJob("owner-invitation-relay"))
                            .at(ResourceScope.tenant(row.tenantId()))
                            .target("tenant.owner_invitation", row.id())
                            .because("The owner's invitation was emailed (ADR 0097)")
                            .changed(Map.of("attempt", row.attempts(), "expiresAt", expiresAt.toString()))
                            .correlatedBy(UUID.randomUUID().toString())
                            .occurredAt(now)
                            .build());
                    return true;
                }
                return false;
            }
            case MailOutcome.NotConfigured ignored ->
                store.markRetry(row.id(), row.attempts(), now.plus(UNCONFIGURED_WAIT), "MAIL_NOT_CONFIGURED", false);
            case MailOutcome.Rejected rejected -> store.markFailed(row.id(), row.attempts(), rejected.code());
            case MailOutcome.Failed failed -> retry(row, now, failed.code());
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
