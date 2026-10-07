package uz.horecaos.platform.iam.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;
import uz.horecaos.platform.iam.application.mfa.MfaCodeBudget;
import uz.horecaos.platform.iam.application.mfa.MfaMetrics;
import uz.horecaos.platform.iam.application.mfa.MfaMetrics.Outcome;
import uz.horecaos.platform.iam.application.mfa.MfaMetrics.Step;
import uz.horecaos.platform.iam.application.mfa.MfaPolicy;
import uz.horecaos.platform.iam.application.mfa.SealedTokens;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient.PasswordCheck;
import uz.horecaos.platform.iam.infrastructure.keycloak.TokenOutcome;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Staff sign-in against Keycloak, on a first-party page instead of a redirect
 * (ADR 0062).
 *
 * <p>Three operations, and each has exactly one credential: a username and
 * password for sign-in, a refresh token for refresh and for sign-out. Neither
 * staff app ever holds the {@code horecaos-staff-login} client secret or talks
 * to Keycloak directly; this service and {@link StaffDirectGrantClient} are the
 * whole of that boundary.
 *
 * <p><strong>Failure shape is deliberate.</strong> A wrong password and an
 * unknown username answer identically — {@link ErrorCode#UNAUTHENTICATED},
 * "Invalid credentials" — because telling them apart is a user-enumeration
 * oracle. The one exception the ADR carves out is
 * {@link ErrorCode#ACCOUNT_ACTION_REQUIRED}: the credentials were right and
 * Keycloak still refused, because a required action stands in the way. A
 * failed refresh is neither: there is no password being guessed, so it answers
 * {@link ErrorCode#SESSION_EXPIRED} instead, the same code every other
 * lapsed-session surface on this platform uses.
 */
@Service
public class StaffAuthService {

    private static final String SIGN_IN_OPERATION = "iam.auth.staff.sign-in";

    /**
     * Five attempts a minute per IP-plus-username pair (ADR 0033), and strict:
     * an unavailable limiter must refuse rather than wave a credential-
     * stuffing run through on the one endpoint in this platform whose entire
     * job is checking a password. Keycloak's own brute-force protection
     * (`infra/keycloak/README.md`) is the backstop behind this, not a
     * substitute for it — the realm only sees an attempt after this budget
     * lets it through, and Keycloak has no notion of "per browser IP" at all.
     */
    private static final RateLimiter.Policy SIGN_IN_LIMIT = RateLimiter.Policy.strictPerMinute(5);

    /**
     * How long the ticket a refused sign-in carries stays good: enough to scan a QR code and type
     * the first code, short enough that a ticket left in a browser tab is not an open door.
     */
    static final Duration ENROLMENT_TICKET_LIFETIME = Duration.ofMinutes(15);

    private final StaffDirectGrantClient keycloak;
    private final StaffPasswordCheckClient passwordCheck;
    private final RateLimiter rateLimiter;
    private final MfaCodeBudget codeBudget;
    private final MfaPolicy policy;
    private final StaffAccounts accounts;
    private final SealedTokens tickets;
    private final MfaMetrics metrics;
    private final Clock clock;

    public StaffAuthService(
            StaffDirectGrantClient keycloak,
            StaffPasswordCheckClient passwordCheck,
            RateLimiter rateLimiter,
            MfaCodeBudget codeBudget,
            MfaPolicy policy,
            StaffAccounts accounts,
            SealedTokens tickets,
            MfaMetrics metrics,
            Clock clock) {
        this.keycloak = keycloak;
        this.passwordCheck = passwordCheck;
        this.rateLimiter = rateLimiter;
        this.codeBudget = codeBudget;
        this.policy = policy;
        this.accounts = accounts;
        this.tickets = tickets;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The one place a staff password is checked, and the second step that follows it (ADR 0148).
     *
     * <p>After the per-address budget the calls run in a fixed order, and the order is the
     * design:
     * <ol>
     *   <li><strong>Is the password right?</strong> Asked of the password-only client, which has
     *       no OTP step. A wrong password or an unknown name stops here with the uniform failure,
     *       and costs Keycloak one failure, as it always did.
     *   <li><strong>May a code be tried?</strong> Only when {@code otp} was sent, and only for a
     *       verified password: one attempt is charged to the account's own budget before the code
     *       goes anywhere. Spent, the answer is 429 and Keycloak never sees the code, right or wrong.
     *   <li><strong>Sign in.</strong> Issued: a session, unless the account needs a second factor
     *       it does not have. Refused with no code sent: {@code MFA_REQUIRED}. Refused with one:
     *       {@code MFA_CODE_INVALID}. Both are reachable only by somebody who already knows the
     *       password.
     * </ol>
     *
     * @param otp the six-digit code, or null for a first attempt
     * @param rateLimitKey an opaque, already-hashed handle combining the caller's address and the
     *     username being attempted (ADR 0033, ADR 0029) -- never the raw address or username, and
     *     never stored anywhere but this limiter's in-memory bucket map
     */
    public StaffSession signIn(String username, String password, @Nullable String otp, String rateLimitKey) {
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(SIGN_IN_OPERATION, null, rateLimitKey), SIGN_IN_LIMIT);
        if (!decision.allowed()) {
            throw tooManyAttempts(decision.retryAfter());
        }

        String subjectId =
                switch (passwordCheck.verify(username, password)) {
                    case PasswordCheck.Verified verified -> verified.account().subjectId();
                    case PasswordCheck.Refused refused -> throw signInRefusal(refused.reason());
                };

        if (otp != null) {
            try {
                codeBudget.charge(subjectId);
            } catch (ApiException exhausted) {
                metrics.record(Step.BUDGET, Outcome.EXHAUSTED);
                throw exhausted;
            }
            metrics.record(Step.BUDGET, Outcome.OK);
        }

        return switch (keycloak.signIn(username, password, otp)) {
            case TokenOutcome.Issued issued -> {
                StaffSession session = withRequirement(issued, subjectId, otp != null);
                if (otp != null) {
                    metrics.record(Step.CHALLENGE, Outcome.OK);
                }
                yield session;
            }
            case TokenOutcome.Refused refused -> throw signInRefusal(refused.reason(), otp != null);
        };
    }

    /**
     * What a session that Keycloak issued must still pass: the account's requirement (ADR 0148,
     * Decision 4). Keycloak accepted a password-only grant, or accepted a code for an account it
     * knows, so the question left is whether this account needed a factor it does not have.
     */
    private StaffSession withRequirement(TokenOutcome.Issued issued, String subjectId, boolean codeWasSent) {
        Requirement requirement = policy.requirementFor(subjectId, issued.hasRealmRole("platform-admin"));
        if (requirement == Requirement.NOT_REQUIRED) {
            return toSession(issued, false);
        }
        // A grant with no code that Keycloak accepted means no OTP credential exists: with one,
        // it would have been refused. With a code sent, Keycloak ignores the parameter for an
        // account that has none, so the credential list is the only way to tell.
        boolean holdsFactor = codeWasSent && !accounts.otpCredentials(subjectId).isEmpty();
        if (holdsFactor) {
            return toSession(issued, false);
        }
        if (requirement == Requirement.OFFERED) {
            return toSession(issued, true);
        }
        // Required and absent: the session must not outlive this answer.
        keycloak.revoke(issued.refreshToken());
        Instant expiresAt = clock.instant().plus(ENROLMENT_TICKET_LIFETIME);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("enrolmentTicket", tickets.sealTicket(subjectId, ENROLMENT_TICKET_LIFETIME));
        properties.put("expiresAt", expiresAt.toString());
        throw new ApiException(
                ErrorCode.MFA_ENROLMENT_REQUIRED,
                "This account needs a second factor before it can sign in. Set one up to continue.",
                properties);
    }

    /** Proxies the refresh grant. Not rate-limited: a refresh token is not a guessable secret. */
    public StaffSession refresh(String refreshToken) {
        return switch (keycloak.refresh(refreshToken)) {
            case TokenOutcome.Issued issued -> toSession(issued, false);
            case TokenOutcome.Refused ignored ->
                throw new ApiException(ErrorCode.SESSION_EXPIRED, "Your session has ended. Sign in again.");
        };
    }

    /**
     * Revokes the refresh token at Keycloak. Never throws: a staff member
     * clicking sign-out must always succeed locally, whatever Keycloak does —
     * see {@link StaffDirectGrantClient#revoke(String)}.
     */
    public void signOut(String refreshToken) {
        keycloak.revoke(refreshToken);
    }

    private static StaffSession toSession(TokenOutcome.Issued issued, boolean mfaEnrolmentOffered) {
        return StaffSession.of(issued, mfaEnrolmentOffered);
    }

    private static ApiException signInRefusal(TokenOutcome.FailureReason reason) {
        return switch (reason) {
            case INVALID_CREDENTIALS -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials.");
            case ACCOUNT_ACTION_REQUIRED ->
                new ApiException(
                        ErrorCode.ACCOUNT_ACTION_REQUIRED,
                        "This account needs one more step before it can sign in. "
                                + "Contact a platform administrator.");
        };
    }

    /**
     * A refusal from the login client after the password-only client confirmed the password. The
     * only credential left to be wrong is the code, or its absence -- so this is the one place a
     * sign-in answers anything but the uniform failure for a credential problem, and it is reached
     * by nobody who does not already hold the password.
     */
    private ApiException signInRefusal(TokenOutcome.FailureReason reason, boolean codeWasSent) {
        if (reason == TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED) {
            return signInRefusal(reason);
        }
        if (codeWasSent) {
            metrics.record(Step.CHALLENGE, Outcome.INVALID);
            return new ApiException(
                    ErrorCode.MFA_CODE_INVALID, "That code is not valid. Check the code and try again.");
        }
        metrics.record(Step.CHALLENGE, Outcome.REQUIRED);
        return new ApiException(ErrorCode.MFA_REQUIRED, "Enter the code from your authenticator app to sign in.");
    }

    private static ApiException tooManyAttempts(Duration retryAfter) {
        return new ApiException(
                ErrorCode.RATE_LIMIT_EXCEEDED,
                "Too many sign-in attempts. Try again shortly.",
                Map.of("retryAfterSeconds", Math.max(1, retryAfter.toSeconds())));
    }

    /**
     * A fresh Keycloak-issued token pair, handed straight to the browser as
     * bearer credentials.
     *
     * @param refreshTokenExpiresAt null when the refresh token has no fixed
     *                              expiry to report — see
     *                              {@code TokenOutcome.Issued}'s own doc for
     *                              why that is the normal, not the missing,
     *                              case
     * @param mfaEnrolmentOffered   true when the account has no second factor and the
     *                              platform rule is in its {@code PROMPT} phase: offer the
     *                              enrolment screen, do not require it (ADR 0148)
     */
    public record StaffSession(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            @Nullable Instant refreshTokenExpiresAt,
            String tokenType,
            boolean mfaEnrolmentOffered) {

        public StaffSession(
                String accessToken,
                String refreshToken,
                Instant accessTokenExpiresAt,
                @Nullable Instant refreshTokenExpiresAt,
                String tokenType) {
            this(accessToken, refreshToken, accessTokenExpiresAt, refreshTokenExpiresAt, tokenType, false);
        }

        /** The session a Keycloak grant opened. */
        public static StaffSession of(TokenOutcome.Issued issued, boolean mfaEnrolmentOffered) {
            return new StaffSession(
                    issued.accessToken(),
                    issued.refreshToken(),
                    issued.accessTokenExpiresAt(),
                    issued.refreshTokenExpiresAt(),
                    issued.tokenType(),
                    mfaEnrolmentOffered);
        }

        /** A record's generated {@code toString} would print both tokens. */
        @Override
        public String toString() {
            return "StaffSession[accessTokenExpiresAt=%s, refreshTokenExpiresAt=%s]"
                    .formatted(accessTokenExpiresAt, refreshTokenExpiresAt);
        }
    }
}
