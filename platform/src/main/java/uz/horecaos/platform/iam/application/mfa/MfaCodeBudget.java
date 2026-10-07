package uz.horecaos.platform.iam.application.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The platform's own limit on one-time-code attempts per account (ADR 0148, Decision 2).
 *
 * <p>Keycloak's lockout cannot be the control for the code step: the password-only probe that
 * sign-in needs first <em>clears</em> its failure count on every success (reproduced by
 * {@code infra/keycloak/spikes/mfa-lockout-probe.py}), so a guesser who holds the password could
 * keep that counter at zero while trying a million codes. This budget is therefore the only
 * brute-force control the second factor has.
 *
 * <p>Four properties carry the weight, and each is a line a later edit must not move:
 * <ol>
 *   <li>It is charged <strong>before</strong> a code reaches Keycloak, so nothing after it can
 *       refund it and a spent budget answers 429 for the right code as well as the wrong one.
 *   <li>It is keyed by the <strong>account</strong>, not the address, so a rotating source
 *       address does not refill it, and signing in by email instead of user name draws on the
 *       same bucket (the subject id is what both resolve to).
 *   <li>It is charged only <strong>after the password was confirmed</strong>, so a wrong
 *       password or an unknown name never spends a budget and the 429 cannot tell accounts apart.
 *   <li>It is <strong>strict</strong>: an unavailable limiter refuses.
 * </ol>
 *
 * <p>The bucket key is a hash of the subject id, never the id itself, and the tenant slot is null
 * on purpose: a staff account is a realm-wide identity that holds jobs in however many tenants,
 * and sign-in happens before any of them is chosen. Burst 5, refilling five an hour, per replica
 * (ADR 0033 accepts roughly N times the budget for N replicas until a shared limiter exists).
 */
@Component
public class MfaCodeBudget {

    static final String OPERATION = "iam.auth.staff.mfa-code";

    private final RateLimiter limiter;
    private final RateLimiter.Policy policy;

    public MfaCodeBudget(
            RateLimiter limiter,
            @Value("${horecaos.iam.mfa.code-budget.permits:5}") long permits,
            @Value("${horecaos.iam.mfa.code-budget.window:PT1H}") Duration window) {
        this.limiter = limiter;
        this.policy = new RateLimiter.Policy(permits, window, false);
    }

    /**
     * Spends one attempt.
     *
     * @throws ApiException {@link ErrorCode#RATE_LIMIT_EXCEEDED} with {@code retryAfterSeconds}
     *     when none is left
     */
    public void charge(String subjectId) {
        RateLimiter.Decision decision = limiter.check(new RateLimiter.Key(OPERATION, null, hash(subjectId)), policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many code attempts for this account. Try again later.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    private static String hash(String subjectId) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(subjectId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
