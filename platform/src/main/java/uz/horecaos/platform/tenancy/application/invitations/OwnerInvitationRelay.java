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
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationEventStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcOwnerInvitationStore.Row;

/**
 * Sends queued owner invitations (ADR 0097).
 *
 * <p>Claim, then send, then settle -- the send is never inside a transaction.
 * The claim pushes the row a lease into the future, so a slow mail server holds
 * no lock and a crashed replica's claim simply comes due again. The token is
 * made here, emailed, and only its hash recorded; if the settle fails after the
 * email went, the row comes due again and a second email carries a new,
 * working link, which beats an owner holding a link the platform cannot match.
 *
 * <p>The settle itself <em>is</em> one transaction, and has to be: the state
 * write and the history entry that explains it (ADR 0100) are two statements
 * that must both stand or neither. Committed apart, a pod killed between them
 * leaves a SENT invitation whose timeline never mentions being sent, and the
 * application role holds {@code SELECT, INSERT} on that table, so nobody can
 * ever put the missing line back.
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
    private final JdbcOwnerInvitationEventStore events;
    private final StaffAccounts accounts;
    private final PlatformMailer mailer;
    private final AuditRecorder audit;

    /**
     * Demarcated here rather than with {@code @Transactional}, because every
     * settle is reached from {@link #runOnce()} inside this same bean and a
     * self-invoked annotated method never goes through the proxy -- the reason
     * {@code TenantControlPlaneService}, {@code OnboardingService} and {@code
     * PaymentAttemptService} each carry one of these too.
     */
    private final TransactionTemplate transactions;

    private final Clock clock;
    private final String operationsOrigin;

    public OwnerInvitationRelay(
            JdbcOwnerInvitationStore store,
            JdbcOwnerInvitationEventStore events,
            StaffAccounts accounts,
            PlatformMailer mailer,
            AuditRecorder audit,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${horecaos.frontends.operations-origin:http://localhost:4200}") String operationsOrigin) {
        this.store = store;
        this.events = events;
        this.accounts = accounts;
        this.mailer = mailer;
        this.audit = audit;
        this.transactions = transactions;
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
                // brings this one back. The identifier, never the address: it
                // is the only handle an operator has on which invitation this
                // was, and the exception is how they find out why.
                log.error("Owner invitation {} could not be processed", row.id(), failure);
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
            settle(
                    row,
                    now,
                    JdbcOwnerInvitationEventStore.SEND_FAILED,
                    "OWNER_ACCOUNT_MISSING",
                    row.attempts(),
                    () -> store.markFailed(row.id(), row.attempts(), "OWNER_ACCOUNT_MISSING"));
            return false;
        }
        if (account.get().hasPassword()) {
            settle(
                    row,
                    now,
                    JdbcOwnerInvitationEventStore.NOT_NEEDED,
                    null,
                    row.attempts(),
                    () -> store.markNotNeeded(row.id(), row.attempts()));
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
                if (settle(
                        row,
                        now,
                        JdbcOwnerInvitationEventStore.SENT,
                        null,
                        row.attempts(),
                        () -> store.markSent(
                                row.id(), row.attempts(), OwnerInvitationService.hash(token), expiresAt, now))) {
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
            case MailOutcome.NotConfigured ignored -> {
                // A deployment with no mail server configured comes back every
                // fifteen minutes for as long as that is true, and nothing about
                // it has changed in between. One line per distinct reason, then,
                // until the reason changes -- and it changes on a resend, which
                // clears last_error_code, or on the day mail starts working.
                // The alternative counts: the attempt is rolled back with the
                // row (nothing was attempted), so a line per pass would say
                // "attempt 1" ninety-six times a day forever beside a panel
                // reading "attempts: 0", and ADR 0100's sizing of this table --
                // a handful of rows per tenant, no retention job -- would stop
                // being true.
                boolean newReason = !"MAIL_NOT_CONFIGURED".equals(row.lastErrorCode());
                settle(
                        row,
                        now,
                        newReason ? JdbcOwnerInvitationEventStore.SEND_DEFERRED : null,
                        "MAIL_NOT_CONFIGURED",
                        // Nothing was attempted, so the line belongs to no
                        // attempt: zero, which is what the panel shows beside it.
                        0,
                        () -> store.markRetry(
                                row.id(), row.attempts(), now.plus(UNCONFIGURED_WAIT), "MAIL_NOT_CONFIGURED", false));
            }
            case MailOutcome.Rejected rejected ->
                settle(
                        row,
                        now,
                        JdbcOwnerInvitationEventStore.SEND_FAILED,
                        rejected.code(),
                        row.attempts(),
                        () -> store.markFailed(row.id(), row.attempts(), rejected.code()));
            case MailOutcome.Failed failed -> retry(row, now, failed.code());
        }
        return false;
    }

    private void retry(Row row, Instant now, String code) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            settle(
                    row,
                    now,
                    JdbcOwnerInvitationEventStore.SEND_FAILED,
                    code,
                    row.attempts(),
                    () -> store.markFailed(row.id(), row.attempts(), code));
        } else {
            // Every real attempt keeps its own line: there are at most
            // MAX_ATTEMPTS of them and each one is a different attempt, so the
            // history is bounded and none of them repeats another.
            settle(
                    row,
                    now,
                    JdbcOwnerInvitationEventStore.SEND_DEFERRED,
                    code,
                    row.attempts(),
                    () -> store.markRetry(row.id(), row.attempts(), now.plus(backoff(row.attempts())), code, true));
        }
    }

    /**
     * Applies one outcome: the guarded state write, and in the same transaction
     * the history entry that explains it (ADR 0100).
     *
     * <p>Two things follow from that, and both are the point. The entry is
     * appended only when the write matched this attempt -- a write that matched
     * nothing is a relay whose lease a newer attempt or an operator's resend has
     * taken over, and its outcome is no longer this invitation's news, so a
     * NOT_NEEDED or SEND_FAILED line for it would be a claim about the past that
     * the append-only table could never take back. And neither statement can
     * commit without the other, so the row and its history cannot disagree.
     *
     * @param type the entry to append, or null for a state write that says
     *        nothing new the history does not already carry
     * @param attempt the attempt the entry belongs to; zero when nothing was
     *        attempted
     * @return true when the state write applied
     */
    private boolean settle(
            Row row, Instant now, @Nullable String type, @Nullable String code, int attempt, BooleanSupplier write) {
        boolean applied = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!write.getAsBoolean()) {
                return false;
            }
            if (type != null) {
                record(row, type, code, attempt, now);
            }
            return true;
        }));
        if (!applied) {
            log.info(
                    "Owner invitation {}: attempt {} finished {} after a newer attempt had taken over, so nothing"
                            + " was recorded",
                    row.id(),
                    row.attempts(),
                    code == null ? type : code);
        }
        return applied;
    }

    /** Appends what this attempt did to the invitation's history (ADR 0100). */
    private void record(Row row, String type, @Nullable String outcomeCode, int attempt, Instant now) {
        events.append(new JdbcOwnerInvitationEventStore.Entry(
                row.tenantId(),
                row.id(),
                type,
                attempt,
                row.locale(),
                outcomeCode,
                ActorRef.Type.SYSTEM_JOB.name(),
                "owner-invitation-relay",
                null,
                now));
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
