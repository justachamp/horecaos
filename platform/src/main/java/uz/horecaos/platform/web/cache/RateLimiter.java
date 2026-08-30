package uz.horecaos.platform.web.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Application-level rate limiting (ADR 0033).
 *
 * <p>The edge handles coarse per-IP limits, because a request rejected there
 * costs nothing. This exists for the limits the edge cannot express: it cannot
 * see tenant, principal, capability, or plan, so it cannot enforce "this tenant
 * may create ten onboarding runs per hour".
 *
 * <p>The port hides whether enforcement is in-process or shared, so moving to
 * Valkey later is configuration rather than a change at every call site.
 */
public interface RateLimiter {

    Decision check(Key key, Policy policy);

    /** Composes the dimensions the edge cannot see. */
    record Key(String operation, String tenantId, String principalSubject) {

        public Key {
            Objects.requireNonNull(operation, "An operation is required");
        }

        public String canonical() {
            return "%s|%s|%s".formatted(operation, tenantId, principalSubject);
        }
    }

    /**
     * @param failOpen whether an unavailable limiter backend allows the request.
     *                 Reads generally fail open; expensive writes fail closed.
     *                 The choice is per limit rather than global, because the
     *                 cost of being wrong differs completely between them.
     */
    record Policy(long permits, Duration window, boolean failOpen) {

        public static Policy perMinute(long permits) {
            return new Policy(permits, Duration.ofMinutes(1), true);
        }

        public static Policy strictPerMinute(long permits) {
            return new Policy(permits, Duration.ofMinutes(1), false);
        }
    }

    /** The outcome, carrying what ADR 0031 needs for a 429 response. */
    record Decision(boolean allowed, long remaining, Duration retryAfter) {

        public static Decision allowed(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }

        public static Decision denied(Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }
    }
}
