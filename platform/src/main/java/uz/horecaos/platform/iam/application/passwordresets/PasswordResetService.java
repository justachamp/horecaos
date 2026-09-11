package uz.horecaos.platform.iam.application.passwordresets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.PasswordRejectedException;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
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
 */
@Service
public class PasswordResetService {

    /** How long an emailed link works. Short, because the person asking is at the screen. */
    public static final Duration LINK_LIFETIME = Duration.ofMinutes(60);

    public static final Set<String> LOCALES = Set.of("uz", "ru", "en");

    private final JdbcPasswordResetStore store;
    private final StaffAccounts accounts;
    private final AuditRecorder audit;
    private final Clock clock;

    public PasswordResetService(
            JdbcPasswordResetStore store, StaffAccounts accounts, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Queues a reset for the account this login resolves to, and does nothing
     * at all when it resolves to none.
     *
     * <p>A second request replaces the first: the store's upsert clears the
     * stored hash, so the link already emailed stops working. That is both the
     * cap on outstanding resets per account and the reason asking twice cannot
     * be used to send two live links to one address.
     *
     * <p>An identity provider that cannot be reached is swallowed rather than
     * raised. The endpoint answers 202 whatever happens, so raising here would
     * turn an outage into a 500 that distinguishes nothing useful and tells a
     * caller the platform is unwell; the staff member asks again.
     */
    @Transactional
    public void request(String login, StaffConsole console, @Nullable String locale, String correlationId) {
        Optional<StaffAccount> account;
        try {
            account = accounts.findByLogin(login);
        } catch (RuntimeException unavailable) {
            return;
        }
        if (account.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        String language = locale != null && LOCALES.contains(locale) ? locale : "ru";
        UUID id = store.request(Ids.newId(), account.get().subjectId(), console.name(), language, now);
        audit.record(AuditFact.of("iam.password_reset.requested", AuditClass.SECURITY)
                .by(ActorRef.user(account.get().subjectId(), null))
                .at(ResourceScope.platform())
                .target("iam.password_reset", id)
                .because("A staff member asked to reset their password from a sign-in page (ADR 0098)")
                .changed(Map.of("console", console.name(), "locale", language))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * What the holder of a live link is shown before choosing a password: which
     * console it belongs to, the account masked, and when it stops working.
     * Marks it opened.
     */
    @Transactional
    public ResetInspection inspect(String token) {
        Instant now = clock.instant();
        Row row = live(token, now);
        store.markOpened(row.id(), now);
        return new ResetInspection(
                row.console(),
                maskedLogin(row.subjectId()),
                java.util.Objects.requireNonNull(row.expiresAt()).toString(),
                row.locale());
    }

    /**
     * Sets the new password, spends the link, and ends every session the
     * account holds.
     *
     * <p>Order matters and is the security property: the password is set
     * first, because a refused one must leave everything as it was; the link
     * is spent next, in the transaction that read it under a row lock; the
     * sessions go last. If the process dies between the second and the third,
     * the staff member has their new password and somebody else's session
     * outlives it -- which is why the logout failure is logged as a failure
     * rather than swallowed, and why it is not the first step: ending sessions
     * for a reset that is then refused would sign out a person who asked for
     * nothing.
     */
    @Transactional
    public void accept(String token, String password, String correlationId) {
        Instant now = clock.instant();
        Row row = live(token, now);
        if (accounts.find(row.subjectId()).isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This account no longer exists. Ask for a new link.",
                    Map.of("reason", "ACCOUNT_MISSING"));
        }
        try {
            accounts.setPassword(row.subjectId(), password);
        } catch (PasswordRejectedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The password does not meet the policy",
                    Map.of("field", "password", "policy", refused.policy()));
        }
        if (!store.markAccepted(row.id(), now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This link changed while it was being used");
        }
        accounts.logoutEverywhere(row.subjectId());
        audit.record(AuditFact.of("iam.password_reset.accepted", AuditClass.SECURITY)
                .by(ActorRef.user(row.subjectId(), null))
                .at(ResourceScope.platform())
                .target("iam.password_reset", row.id())
                .because("The staff member set a new password from the emailed link (ADR 0098)")
                .changed(Map.of("status", "ACCEPTED", "sessionsEnded", true))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
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
        Row row = store.byTokenHashForUpdate(hash(token.strip()))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This link is not valid. If you already set a new password, sign in.",
                        Map.of("reason", "INVALID")));
        Instant expiresAt = row.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "This link has expired. Ask for a new one.",
                    Map.of("reason", "EXPIRED"));
        }
        return row;
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
