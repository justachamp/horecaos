package uz.horecaos.platform.iam.application.mfa;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.OtpCredential;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.iam.api.audit.StaffSecurityAudit;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration;
import uz.horecaos.platform.iam.application.StaffAuthService.StaffSession;
import uz.horecaos.platform.iam.application.mfa.MfaMetrics.Outcome;
import uz.horecaos.platform.iam.application.mfa.MfaMetrics.Step;
import uz.horecaos.platform.iam.application.mfa.SealedTokens.Opened;
import uz.horecaos.platform.iam.application.mfa.SealedTokens.Purpose;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordCheck;
import uz.horecaos.platform.iam.infrastructure.keycloak.TokenOutcome;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.CacheRegistry;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * A staff member's second factor, held and verified by Keycloak and managed from the product
 * (ADR 0148): enrolling an authenticator, adding a second, removing one, reading where a person
 * stands, and the administrator's reset.
 *
 * <p><strong>The platform stores no secret and verifies no code.</strong> It generates the
 * authenticator secret, seals it into a ten-minute token so no table holds a pending one, hands
 * it to Keycloak when the person confirms the first code, and forgets it. Every code that matters
 * is checked by Keycloak's own direct grant; what this class adds is that the password is
 * re-proved first, that the platform's own attempt budget is charged before any code is sent, and
 * that a credential whose first code fails is deleted again.
 *
 * <p>Every enrol, add, remove and reset is audited and emailed (ADR 0148, Decision 7). An audit
 * fact never contains a secret, a code or an authenticator label, and neither does an email.
 */
@Service
public class StaffMfaService implements StaffMfaAdministration {

    private static final Logger log = LoggerFactory.getLogger(StaffMfaService.class);

    /** What the person typed, if anything: the authenticator's own name on the screen listing them. */
    static final String DEFAULT_LABEL = "Authenticator";

    /** Two devices: the second is the recovery path that needs nobody else (Decision 1). */
    public static final int MAXIMUM_AUTHENTICATORS = 2;

    /** Ten minutes, single purpose (Decision 3). */
    static final Duration ENROLMENT_LIFETIME = Duration.ofMinutes(10);

    private static final String ISSUER = "HorecaOS";

    /** Characters of the account name shown in the authenticator app: what fits the QR code beside the key. */
    static final int ACCOUNT_LABEL_LIMIT = 24;

    /** The console's QR encoder: versions 1 to 5 at level L, byte mode, 106 bytes (qr-encode.ts). */
    static final int QR_BYTE_LIMIT = 106;

    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SECRET_LENGTH = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** The enrolment endpoints take a password and a code, so they are paced like sign-in: per account, strict. */
    private static final RateLimiter.Policy ENROLMENT_PACE = RateLimiter.Policy.strictPerMinute(10);

    private final StaffAccounts accounts;
    private final StaffPasswordCheckClient passwordCheck;
    private final StaffDirectGrantClient keycloak;
    private final MfaCodeBudget codeBudget;
    private final MfaPolicy policy;
    private final SealedTokens tokens;
    private final StaffAccountFacts facts;
    private final MfaMetrics metrics;
    private final StaffSecurityAudit audit;
    private final StaffEmailSender mail;
    private final RateLimiter limiter;
    private final CacheManager caches;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public StaffMfaService(
            StaffAccounts accounts,
            StaffPasswordCheckClient passwordCheck,
            StaffDirectGrantClient keycloak,
            MfaCodeBudget codeBudget,
            MfaPolicy policy,
            SealedTokens tokens,
            StaffAccountFacts facts,
            MfaMetrics metrics,
            StaffSecurityAudit audit,
            StaffEmailSender mail,
            RateLimiter limiter,
            CacheManager caches,
            TransactionTemplate transactions,
            Clock clock) {
        this.accounts = accounts;
        this.passwordCheck = passwordCheck;
        this.keycloak = keycloak;
        this.codeBudget = codeBudget;
        this.policy = policy;
        this.tokens = tokens;
        this.facts = facts;
        this.metrics = metrics;
        this.audit = audit;
        this.mail = mail;
        this.limiter = limiter;
        this.caches = caches;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ================================================================== enrolment

    /**
     * The account an enrolment ticket was issued to sign in for: the only identity a caller who
     * presents one acts as.
     */
    public String subjectOfTicket(@Nullable String ticket) {
        return tokens.openTicket(ticket);
    }

    /**
     * What a person scans and what the next call carries back.
     *
     * @param secret the same secret as {@code otpauthUri} carries, in the Base32 text an
     *     authenticator app accepts typed in, for a person who cannot scan
     */
    public record Enrolment(String sealedSecret, String otpauthUri, String secret, Instant expiresAt) {

        /** The secret is the whole of the factor: never printed. */
        @Override
        public String toString() {
            return "Enrolment[expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * Step one: re-proves the password, generates a secret and returns it sealed.
     *
     * <p>Nothing is written anywhere. An enrolment that is never confirmed leaves no trace in the
     * platform and none in Keycloak.
     *
     * @param viaTicket whether the caller presented an enrolment ticket instead of a session: a
     *     ticket opens the first enrolment only, never the addition of a second device
     */
    public Enrolment begin(String subjectId, boolean viaTicket, String password) {
        pace(subjectId);
        StaffAccount account = accountOf(subjectId);
        List<OtpCredential> held = accounts.otpCredentials(subjectId);
        refuseWhenNoRoom(held, viaTicket);
        requirePassword(account, password);

        String secret = newSecret();
        String sealed = tokens.seal(
                Purpose.ENROLMENT, subjectId, Map.of("secret", secret, "held", fingerprint(held)), ENROLMENT_LIFETIME);
        return new Enrolment(
                sealed,
                otpauthUri(account.username(), secret),
                base32(secret.getBytes(StandardCharsets.UTF_8)),
                clock.instant().plus(ENROLMENT_LIFETIME));
    }

    /** What confirming an enrolment leaves: nothing, or a session when the enrolment began from a ticket. */
    public record Confirmation(@Nullable StaffSession session) {}

    /**
     * Step two: registers the credential, proves it, and keeps it only if the proof succeeds.
     *
     * <p>The order is the safety. The password is re-proved; the account's code budget is charged
     * before any code reaches Keycloak; the credential is registered; a real direct grant with the
     * password and the first code then proves that Keycloak, not this class, accepts the code. If
     * it does not, the credential is deleted and the person is told. A grant that succeeds is
     * the session when the enrolment began from a ticket, and is revoked on the spot otherwise.
     */
    public Confirmation confirm(
            String subjectId,
            boolean viaTicket,
            String sealedSecret,
            String code,
            String password,
            @Nullable String label) {
        pace(subjectId);
        Opened opened = tokens.open(Purpose.ENROLMENT, subjectId, sealedSecret);
        StaffAccount account = accountOf(subjectId);
        List<OtpCredential> held = accounts.otpCredentials(subjectId);
        // The sealed token names the authenticators the account held when it was made. If that has
        // changed -- this very token was already confirmed, or another enrolment landed first --
        // it is spent, which is the replay protection a token with no table can have.
        if (!fingerprint(held).equals(opened.claims().get("held"))) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "This enrolment has expired or is not valid. Start the enrolment again.");
        }
        refuseWhenNoRoom(held, viaTicket);
        requirePassword(account, password);

        spendBudget(subjectId);
        String secret = opened.claims().get("secret");
        if (secret == null) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "This enrolment has expired or is not valid. Start the enrolment again.");
        }
        String typed = label == null || label.isBlank() ? DEFAULT_LABEL : label.strip();

        OtpCredential created = accounts.addOtpCredential(subjectId, secret, typed);
        TokenOutcome proof;
        try {
            proof = keycloak.signIn(account.username(), password, code);
        } catch (RuntimeException unreachable) {
            deleteQuietly(subjectId, created);
            throw unreachable;
        }
        if (!(proof instanceof TokenOutcome.Issued issued)) {
            deleteQuietly(subjectId, created);
            evict(subjectId);
            metrics.record(Step.CONFIRM, Outcome.INVALID);
            throw new ApiException(
                    ErrorCode.MFA_CONFIRMATION_CODE_INVALID,
                    "That code did not match, so no authenticator was added. Check the code and try again.");
        }

        StaffSession session = null;
        if (viaTicket) {
            session = StaffSession.of(issued, false);
        } else {
            keycloak.revoke(issued.refreshToken());
        }
        evict(subjectId);
        metrics.record(Step.CONFIRM, Outcome.OK);

        boolean first = held.isEmpty();
        transactions.executeWithoutResult(status -> record(
                first ? "iam.staff.mfa.enrolled" : "iam.staff.mfa.authenticator_added",
                subjectId,
                subjectId,
                first
                        ? "The person enrolled a second factor from the product (ADR 0148)"
                        : "The person added a second authenticator from the product (ADR 0148)",
                Map.of("authenticators", held.size()),
                Map.of("authenticators", held.size() + 1)));
        tell(account, first ? StaffMfaEmails.Kind.ENROLLED : StaffMfaEmails.Kind.ADDED);
        return new Confirmation(session);
    }

    // ============================================================ own authenticators

    /**
     * Removes one of the caller's authenticators. Needs the password and a valid code, charged to
     * the same budget as sign-in, and never takes the last one: with nothing left the account is
     * unprotected, and that is an administrator's decision, not a button.
     *
     * <p>Keycloak accepts a code from any of the account's authenticators, so it cannot say that
     * the code came from "another" one. What the platform can and does guarantee is that a code
     * was proved and that at least one authenticator remains afterwards.
     */
    public void remove(String subjectId, String credentialId, String password, String code) {
        pace(subjectId);
        StaffAccount account = accountOf(subjectId);
        List<OtpCredential> held = accounts.otpCredentials(subjectId);
        OtpCredential target = held.stream()
                .filter(credential -> credential.id().equals(credentialId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such authenticator."));
        if (held.size() < 2) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This is the only authenticator on the account. Add a second one first, or ask an "
                            + "administrator to reset your second factor.");
        }
        requirePassword(account, password);
        spendBudget(subjectId);

        TokenOutcome proof = keycloak.signIn(account.username(), password, code);
        if (!(proof instanceof TokenOutcome.Issued issued)) {
            metrics.record(Step.CONFIRM, Outcome.INVALID);
            throw new ApiException(
                    ErrorCode.MFA_CONFIRMATION_CODE_INVALID, "That code did not match. Nothing was removed.");
        }
        keycloak.revoke(issued.refreshToken());

        transactions.executeWithoutResult(status -> {
            if (!accounts.removeOtpCredential(subjectId, target.id())) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such authenticator.");
            }
            record(
                    "iam.staff.mfa.authenticator_removed",
                    subjectId,
                    subjectId,
                    "The person removed one of their authenticators from the product (ADR 0148)",
                    Map.of("authenticators", held.size()),
                    Map.of("authenticators", held.size() - 1));
        });
        evict(subjectId);
        metrics.record(Step.CONFIRM, Outcome.OK);
        tell(account, StaffMfaEmails.Kind.REMOVED);
    }

    /** The caller's own authenticators, read fresh: a person who just added one must see it. */
    public MfaStatus ownStatus(String subjectId) {
        accountOf(subjectId);
        return statusOf(subjectId, accounts.otpCredentials(subjectId));
    }

    // =========================================================== administration

    @Override
    public boolean isPlatformAccount(String subjectId) {
        return facts.holdsPlatformGrant(subjectId);
    }

    @Override
    public Optional<MfaStatus> status(String subjectId) {
        if (accounts.find(subjectId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(statusOf(subjectId, cachedCredentials(subjectId)));
    }

    @Override
    public ResetResult reset(ResetCommand command) {
        StaffAccount account =
                accounts.find(command.subjectId()).orElseThrow(() -> new IllegalStateException("No such account"));
        List<OtpCredential> held = accounts.otpCredentials(command.subjectId());
        int[] removed = new int[1];
        transactions.executeWithoutResult(status -> {
            removed[0] = accounts.removeAllOtpCredentials(command.subjectId());
            // Two calls, as the password reset's own session end is: the sessions a person is in
            // right now, and the offline grants every device that stayed signed in holds.
            accounts.logoutEverywhere(command.subjectId());
            record(
                    "iam.staff.mfa.reset",
                    command.actorSubject(),
                    command.subjectId(),
                    command.reason(),
                    Map.of("authenticators", held.size()),
                    Map.of("authenticators", 0));
        });
        evict(command.subjectId());
        metrics.record(Step.RESET, Outcome.OK);
        boolean notified = tell(account, StaffMfaEmails.Kind.RESET);
        return new ResetResult(removed[0], true, notified);
    }

    // ================================================================== internals

    private MfaStatus statusOf(String subjectId, List<OtpCredential> held) {
        List<Authenticator> authenticators = held.stream()
                .map(credential -> new Authenticator(credential.id(), credential.label(), credential.createdAt()))
                .toList();
        return new MfaStatus(!authenticators.isEmpty(), authenticators, policy.requirementFor(subjectId, false));
    }

    private StaffAccount accountOf(String subjectId) {
        return accounts.find(subjectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such account."));
    }

    private void refuseWhenNoRoom(List<OtpCredential> held, boolean viaTicket) {
        if (viaTicket && !held.isEmpty()) {
            // A ticket is issued to an account with no factor. One that holds a factor now is a
            // ticket that has outlived its purpose, and adding a device is for someone signed in.
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "This enrolment has expired or is not valid. Start the enrolment again.");
        }
        if (held.size() >= MAXIMUM_AUTHENTICATORS) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An account holds at most two authenticators. Remove one before adding another.");
        }
    }

    /** The password-only client, as at sign-in: it must be right, and it must be this account's. */
    private void requirePassword(StaffAccount account, String password) {
        PasswordCheck check = passwordCheck.verify(account.username(), password);
        if (!(check instanceof PasswordCheck.Verified verified)
                || !verified.account().subjectId().equals(account.subjectId())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INVALID, "That is not your current password.");
        }
    }

    private void spendBudget(String subjectId) {
        try {
            codeBudget.charge(subjectId);
        } catch (ApiException exhausted) {
            metrics.record(Step.BUDGET, Outcome.EXHAUSTED);
            throw exhausted;
        }
        metrics.record(Step.BUDGET, Outcome.OK);
    }

    private void pace(String subjectId) {
        RateLimiter.Decision decision = limiter.check(
                new RateLimiter.Key("iam.auth.staff.mfa-enrolment", null, hash(subjectId)), ENROLMENT_PACE);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many attempts. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    private void deleteQuietly(String subjectId, OtpCredential created) {
        try {
            accounts.removeOtpCredential(subjectId, created.id());
        } catch (RuntimeException failure) {
            // The credential was registered and its proof failed, and the compensation did not
            // land either. Said loudly, by id and not by person: an operator must look.
            log.error(
                    "An authenticator whose enrolment failed could not be deleted again (credential {})",
                    created.id(),
                    failure);
        }
    }

    private List<OtpCredential> cachedCredentials(String subjectId) {
        Cache cache = caches.getCache(CacheRegistry.IAM_STAFF_MFA_CREDENTIALS.cacheName());
        if (cache != null) {
            Cache.ValueWrapper hit = cache.get(subjectId);
            if (hit != null && hit.get() instanceof List<?> cached) {
                @SuppressWarnings("unchecked")
                List<OtpCredential> typed = (List<OtpCredential>) cached;
                return typed;
            }
        }
        List<OtpCredential> fresh = accounts.otpCredentials(subjectId);
        if (cache != null) {
            cache.put(subjectId, List.copyOf(fresh));
        }
        return fresh;
    }

    private void evict(String subjectId) {
        Cache cache = caches.getCache(CacheRegistry.IAM_STAFF_MFA_CREDENTIALS.cacheName());
        if (cache != null) {
            cache.evict(subjectId);
        }
    }

    /** One ADR 0027 fact, inside the caller's transaction when there is one. Never a secret, a code or a label. */
    private void record(
            String action,
            String actorSubject,
            String targetSubject,
            String because,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(StaffSecurityFact.byStaffMember(
                action,
                actorSubject,
                "iam.staff_account",
                targetOf(targetSubject),
                because,
                before,
                after,
                correlationId(),
                clock.instant()));
    }

    /**
     * Emails the person about the change, best effort: the change has happened and the evidence
     * is written, so a mail server that is down must not turn it into an error. A phone-only
     * account has no address and is not emailed.
     */
    private boolean tell(StaffAccount account, StaffMfaEmails.Kind kind) {
        String to = account.email();
        if (to == null || to.isBlank()) {
            return false;
        }
        try {
            String language = facts.uiLocale(account.subjectId()).orElse("ru");
            return mail.send(StaffMfaEmails.render(to, language, kind)).status() == StaffEmailSender.Status.SENT;
        } catch (RuntimeException failure) {
            log.warn(
                    "A second-factor notification could not be sent ({})",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    static UUID targetOf(String subjectId) {
        try {
            return UUID.fromString(subjectId);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(subjectId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String correlationId() {
        String fromRequest = MDC.get("correlationId");
        return fromRequest == null || fromRequest.isBlank() ? UUID.randomUUID().toString() : fromRequest;
    }

    /** Keycloak's own representation: twenty characters whose UTF-8 bytes are the HMAC key. */
    private String newSecret() {
        StringBuilder secret = new StringBuilder(SECRET_LENGTH);
        for (int i = 0; i < SECRET_LENGTH; i++) {
            secret.append(SECRET_ALPHABET.charAt(random.nextInt(SECRET_ALPHABET.length())));
        }
        return secret.toString();
    }

    /**
     * The key URI an authenticator app reads (the de-facto "Key Uri Format"), sized to fit the
     * console's QR encoder, whose byte-mode capacity is 106 bytes.
     *
     * <p>Only what has no default is spelled out. {@code algorithm=SHA1}, {@code digits=6} and
     * {@code period=30} are the format's defaults and the realm's policy (ADR 0148, Decision 1),
     * and writing them out would cost twenty-nine bytes of the 106. The account name is shortened
     * to {@link #ACCOUNT_LABEL_LIMIT} characters for the same reason: it labels the entry in the
     * person's app, it is not an identifier anything reads back.
     */
    static String otpauthUri(String account, String secret) {
        String key = base32(secret.getBytes(StandardCharsets.UTF_8));
        String shown = account.length() > ACCOUNT_LABEL_LIMIT ? account.substring(0, ACCOUNT_LABEL_LIMIT) : account;
        String uri = keyUri(shown, key);
        // Percent-encoding can triple a character, so the limit above is a start and the bytes decide.
        while (uri.getBytes(StandardCharsets.UTF_8).length > QR_BYTE_LIMIT && !shown.isEmpty()) {
            shown = shown.substring(0, shown.length() - 1);
            uri = keyUri(shown, key);
        }
        return uri;
    }

    private static String keyUri(String account, String key) {
        String label = URLEncoder.encode(ISSUER + ":" + account, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + key + "&issuer=" + ISSUER;
    }

    /** RFC 4648 Base32, unpadded: the form an authenticator app reads. */
    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** An identifier of exactly which authenticators the account holds, compared when a token is opened. */
    private static String fingerprint(List<OtpCredential> held) {
        String ids = held.stream().map(OtpCredential::id).sorted().reduce("", (left, right) -> left + "|" + right);
        return hash(ids).substring(0, 16);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Whether the actor holds a support session's assist role in the tenant. A tenant owner's own
     * reset is performed by platform support under an ADR 0081 session and by nobody inside the
     * tenant (ADR 0148, Decision 5), so the tenant route asks this before it touches an owner.
     */
    public boolean mayResetTenantOwner(String actorSubject, UUID tenantId) {
        return facts.holdsRoleInTenant(actorSubject, tenantId, PlatformRole.SUPPORT_SESSION_ASSIST.code());
    }

    /** Whether the subject holds the owner role in the tenant, which makes their reset support's to perform. */
    public boolean isTenantOwner(String subjectId, UUID tenantId) {
        return facts.holdsRoleInTenant(subjectId, tenantId, PlatformRole.TENANT_OWNER.code());
    }
}
