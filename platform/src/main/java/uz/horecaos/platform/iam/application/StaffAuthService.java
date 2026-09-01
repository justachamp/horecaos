package uz.horecaos.platform.iam.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
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

    private final StaffDirectGrantClient keycloak;
    private final RateLimiter rateLimiter;

    public StaffAuthService(StaffDirectGrantClient keycloak, RateLimiter rateLimiter) {
        this.keycloak = keycloak;
        this.rateLimiter = rateLimiter;
    }

    /**
     * The one place a staff password is checked.
     *
     * @param rateLimitKey an opaque, already-hashed handle combining the
     *                      caller's address and the username being attempted
     *                      (ADR 0033, ADR 0029) — never the raw address or
     *                      username, and never stored anywhere but this
     *                      limiter's in-memory bucket map
     */
    public StaffSession signIn(String username, String password, String rateLimitKey) {
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(SIGN_IN_OPERATION, null, rateLimitKey), SIGN_IN_LIMIT);
        if (!decision.allowed()) {
            throw tooManyAttempts(decision.retryAfter());
        }

        return switch (keycloak.signIn(username, password)) {
            case TokenOutcome.Issued issued -> toSession(issued);
            case TokenOutcome.Refused refused -> throw signInRefusal(refused.reason());
        };
    }

    /** Proxies the refresh grant. Not rate-limited: a refresh token is not a guessable secret. */
    public StaffSession refresh(String refreshToken) {
        return switch (keycloak.refresh(refreshToken)) {
            case TokenOutcome.Issued issued -> toSession(issued);
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

    private static StaffSession toSession(TokenOutcome.Issued issued) {
        return new StaffSession(
                issued.accessToken(),
                issued.refreshToken(),
                issued.accessTokenExpiresAt(),
                issued.refreshTokenExpiresAt(),
                issued.tokenType());
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
     */
    public record StaffSession(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            @Nullable Instant refreshTokenExpiresAt,
            String tokenType) {

        /** A record's generated {@code toString} would print both tokens. */
        @Override
        public String toString() {
            return "StaffSession[accessTokenExpiresAt=%s, refreshTokenExpiresAt=%s]"
                    .formatted(accessTokenExpiresAt, refreshTokenExpiresAt);
        }
    }
}
