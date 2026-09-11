package uz.horecaos.platform.iam.application.passwordresets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.PasswordRejectedException;
import uz.horecaos.platform.iam.api.audit.StaffSecurityAudit;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcPasswordResetStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcPasswordResetStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A staff member's password reset (ADR 0098): asked for from a sign-in page,
 * emailed by the relay as a one-time link, inspected, and spent once.
 *
 * <p>The link's token exists only in the email. This class sees it once, when
 * the staff member presents it, and compares its SHA-256 with the one the
 * relay kept; nothing here stores, logs or returns a token.
 *
 * <p><b>{@link #request} answers nothing.</b> Not "queued", not "no such
 * account" -- it is {@code void}, and that is the enumeration defence made
 * structural rather than left to a controller to remember: there is no value
 * for a handler to accidentally turn into a different status code for an
 * account that exists.
 *
 * <p><b>No method here holds a database connection across a call to Keycloak.</b>
 * Every path is a short transaction, then the remote call, then another short
 * transaction. The pool is ten connections wide and shared by every module, so
 * a transaction that spans a three-second connect and a ten-second read turns a
 * Keycloak brownout into a platform-wide outage -- on endpoints that need no
 * token at all. {@code ExternalCallTransactionBoundaryTests} asserts it.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** How long an emailed link works. Short, because the person asking is at the screen. */
    public static final Duration LINK_LIFETIME = Duration.ofMinutes(60);

    /**
     * How long a delivered, still-live link survives a second request for the
     * same account.
     *
     * <p>Five minutes: long enough that a stranger posting a known address
     * cannot keep replacing the link its owner is holding, short enough that
     * somebody who genuinely never received the first email is not left
     * waiting. Nobody is told the difference -- the endpoint answers 202
     * either way (ADR 0098 Decision 1).
     */
    public static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(5);

    public static final Set<String> LOCALES = Set.of("uz", "ru", "en");

    /**
     * Who the audit trail says asked for a reset.
     *
     * <p>The surface, not the account. Nobody is authenticated on the request
     * endpoint, so recording the staff member as the actor would assert that
     * they personally asked -- a statement an anonymous stranger with their
     * address can make on their behalf, ten times a minute. The storefront's
     * pre-authentication paths already settled this question the same way
     * ({@code ActorRef.service("storefront-verification")}).
     */
    private static final String REQUEST_ENDPOINT = "staff-password-reset-request";

    private final JdbcPasswordResetStore store;
    private final StaffAccounts accounts;
    private final StaffSecurityAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PasswordResetService(
            JdbcPasswordResetStore store,
            StaffAccounts accounts,
            StaffSecurityAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Queues a reset for the account this login resolves to, and does nothing
     * at all when it resolves to none.
     *
     * <p>A second request replaces the first -- the store's upsert clears the
     * stored hash, so the link already emailed stops working -- <em>unless</em>
     * a link sent inside {@link #REQUEST_COOLDOWN} is still live, in which case
     * this is a silent no-op and the link in the person's mailbox survives.
     * Without that exception this endpoint is a mail-bombing lever: it is
     * unauthenticated, so anybody who knows a staff address can post it every
     * few seconds and each post both kills the link the owner is holding and
     * queues another email to them. The cooldown lives in the upsert's own
     * {@code WHERE}, not in a read this method makes first, because two
     * requests can be in flight at once.
     *
     * <p>The identity provider is asked <em>outside</em> the transaction, and
     * it is asked for a subject id rather than an account: the two extra admin
     * round trips {@code findByLogin} would make are pure waste here, and the
     * symmetry of {@link StaffAccounts#findSubjectIdByLogin} is what keeps a
     * login that names an account from costing measurably more than one that
     * does not.
     *
     * <p>An identity provider that cannot be reached is swallowed rather than
     * raised. The endpoint answers 202 whatever happens, so raising here would
     * turn an outage into a 500 that distinguishes nothing useful and tells a
     * caller the platform is unwell; the staff member asks again.
     *
     * @param correlationId the hashed caller address, not a per-request value:
     *     nobody here is authenticated, so what an investigator needs to join
     *     these facts by is the machine that asked
     */
    public void request(String login, StaffConsole console, @Nullable String locale, String correlationId) {
        Optional<String> subjectId;
        try {
            subjectId = accounts.findSubjectIdByLogin(login);
        } catch (RuntimeException unavailable) {
            return;
        }
        if (subjectId.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        String language = locale != null && LOCALES.contains(locale) ? locale : "ru";
        transactions.executeWithoutResult(status -> queue(subjectId.get(), console, language, correlationId, now));
    }

    private void queue(String subjectId, StaffConsole console, String language, String correlationId, Instant now) {
        Optional<UUID> queued =
                store.request(Ids.newId(), subjectId, console.name(), language, now, now.minus(REQUEST_COOLDOWN));
        if (queued.isPresent()) {
            audit.record(StaffSecurityFact.byService(
                    "iam.password_reset.requested",
                    REQUEST_ENDPOINT,
                    "iam.password_reset",
                    queued.get(),
                    "A reset was asked for from a sign-in page; the requester was not authenticated (ADR 0098)",
                    Map.of("console", console.name(), "locale", language),
                    correlationId,
                    now));
            return;
        }
        // A live link was left alone. Recorded, because a burst of these from
        // one caller is exactly the abuse the cooldown exists to stop and the
        // only place it becomes visible; the row is the target, so an
        // investigator still reaches the account without this fact naming it.
        store.forSubject(subjectId)
                .ifPresent(row -> audit.record(StaffSecurityFact.byService(
                        "iam.password_reset.request_suppressed",
                        REQUEST_ENDPOINT,
                        "iam.password_reset",
                        row.id(),
                        "A reset was asked for again while a link sent minutes ago was still live (ADR 0098)",
                        Map.of("console", console.name(), "reason", "COOLDOWN"),
                        correlationId,
                        now)));
    }

    /**
     * What the holder of a live link is shown before choosing a password: which
     * console it belongs to, the account masked, and when it stops working.
     * Marks it opened.
     *
     * <p>Two statements, each its own transaction, and the masked login read
     * from Keycloak after both. {@code markOpened} guards itself with {@code
     * status = 'SENT' AND opened_at IS NULL}, so nothing here needs a row lock
     * -- and a lock held across the two admin calls the mask costs would park a
     * pooled connection for as long as Keycloak takes to answer.
     */
    public ResetInspection inspect(String token) {
        Instant now = clock.instant();
        Row row = live(token, now);
        store.markOpened(row.id(), now);
        return new ResetInspection(
                row.console(),
                maskedLogin(row.subjectId()),
                Objects.requireNonNull(row.expiresAt()).toString(),
                row.locale());
    }

    /**
     * Sets the new password, spends the link, and ends every session the
     * account holds.
     *
     * <p>The order is the security property, and it is not the obvious one.
     * <b>The link is spent first</b>, in a transaction of its own that commits
     * before Keycloak is asked for anything:
     *
     * <ol>
     *   <li>the row is read and checked live;
     *   <li>{@code markAccepted} spends it -- {@code WHERE status = 'SENT'},
     *       so of two concurrent accepts exactly one proceeds and the loser is
     *       told the link is invalid before it has changed any password;
     *   <li>{@code setPassword} runs outside any transaction;
     *   <li>{@code logoutEverywhere} runs outside any transaction, and its
     *       failure is logged and audited, never propagated.
     * </ol>
     *
     * <p>Spending first is what makes a later failure unable to resurrect the
     * link. When the revocation lived inside the accept's transaction, a
     * timeout or a 502 from Keycloak rolled the spend back <em>after</em> the
     * password had already changed: the row went back to {@code SENT} with its
     * hash restored, the audit fact was discarded, and the caller was told the
     * reset failed when it had not. Now the only thing a failed revocation can
     * do is be recorded.
     *
     * <p>The one exception is a password the realm's policy refuses, which
     * provably changed nothing at Keycloak: that restores the link, because a
     * person who typed a password the realm dislikes has to be able to type
     * another one. Every other failure after the spend leaves the link spent
     * -- the safe direction, at the price of asking again.
     *
     * <p>Ending the sessions goes last and separately because a reset that is
     * refused must not sign out somebody who asked for nothing, and because the
     * password change is the part the staff member is waiting for: when the
     * revocation fails the answer is still 204, with {@code sessionsEnded} false
     * on the accepted fact and a {@code sessions_not_ended} fact beside it for
     * an operator to act on. Telling the caller it failed would be false --
     * their password did change -- and would invite them to retry a link that
     * no longer exists.
     */
    public void accept(String token, String password, String correlationId) {
        Instant now = clock.instant();
        Row row = live(token, now);
        if (accounts.find(row.subjectId()).isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This account no longer exists. Ask for a new link.",
                    Map.of("reason", "ACCOUNT_MISSING"));
        }
        if (!store.markAccepted(row.id(), now)) {
            // Somebody else spent it, or a fresh request replaced it, between
            // the read above and here. Nothing was changed at Keycloak.
            throw invalid();
        }
        try {
            accounts.setPassword(row.subjectId(), password);
        } catch (PasswordRejectedException refused) {
            store.restoreSent(
                    row.id(), Objects.requireNonNull(row.tokenHash()), Objects.requireNonNull(row.expiresAt()), now);
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The password does not meet the policy",
                    Map.of("field", "password", "policy", refused.policy()));
        }

        boolean sessionsEnded = endSessions(row, correlationId, now);
        audit.record(StaffSecurityFact.byStaffMember(
                "iam.password_reset.accepted",
                row.subjectId(),
                "iam.password_reset",
                row.id(),
                "The staff member set a new password from the emailed link (ADR 0098)",
                Map.of("status", "ACCEPTED", "sessionsEnded", sessionsEnded),
                correlationId,
                now));
    }

    /**
     * @return whether every other session was actually ended; false is recorded
     *     rather than raised, because the password has already changed
     */
    private boolean endSessions(Row row, String correlationId, Instant now) {
        try {
            accounts.logoutEverywhere(row.subjectId());
            return true;
        } catch (RuntimeException failed) {
            log.error(
                    "The password was reset but the account's sessions were not ended (reset {}, correlation {})",
                    row.id(),
                    correlationId,
                    failed);
            audit.record(StaffSecurityFact.byStaffMember(
                    "iam.password_reset.sessions_not_ended",
                    row.subjectId(),
                    "iam.password_reset",
                    row.id(),
                    "The password was reset but the account's other sessions could not be ended (ADR 0098)",
                    Map.of("status", "ACCEPTED", "failure", failed.getClass().getSimpleName()),
                    correlationId,
                    now));
            return false;
        }
    }

    /** SHA-256 of a token, hex; what the relay stores and what a presented token is compared by. */
    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Row live(String token, Instant now) {
        Row row = store.byTokenHash(hash(token.strip())).orElseThrow(PasswordResetService::invalid);
        Instant expiresAt = row.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This link has expired. Ask for a new one.",
                    Map.of("reason", "EXPIRED"));
        }
        return row;
    }

    private static ApiException invalid() {
        return new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "This link is not valid. If you already set a new password, sign in.",
                Map.of("reason", "INVALID"));
    }

    private @Nullable String maskedLogin(String subjectId) {
        try {
            return accounts.find(subjectId)
                    .map(account -> mask(account.email()))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            // The page still works without it; only the reassurance of seeing
            // which account this is goes missing while the provider is down.
            return null;
        }
    }

    /**
     * {@code operator@example.uz} as {@code o***r@example.uz}, and a bare user
     * name the same way: enough to recognise, not enough to use.
     */
    static String mask(String login) {
        int at = login.indexOf('@');
        String local = at <= 0 ? login : login.substring(0, at);
        String domain = at <= 0 ? "" : login.substring(at);
        if (local.isEmpty()) {
            return "***";
        }
        String shown = local.length() <= 2
                ? local.charAt(0) + "***"
                : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return shown + domain;
    }

    /**
     * What the holder of a live link is shown.
     *
     * <p>{@code maskedLogin} is masked rather than absent because the person
     * has to be able to tell which of two accounts they are resetting, and
     * masked rather than whole because possession of the link is the only
     * thing that authorises reading this.
     */
    public record ResetInspection(String console, @Nullable String maskedLogin, String expiresAt, String locale) {}
}
