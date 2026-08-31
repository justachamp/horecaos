package uz.horecaos.platform.web.api;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An application failure that maps to a specific {@link ErrorCode} (ADR 0031).
 *
 * <p>Throwing this instead of a generic exception is what keeps error responses
 * stable across surfaces: the handler does not have to guess a status from an
 * exception type it has never seen.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> properties;

    public ApiException(ErrorCode errorCode, @Nullable String message) {
        this(errorCode, message, Map.of());
    }

    public ApiException(ErrorCode errorCode, @Nullable String message, Map<String, Object> properties) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "An error code is required");
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    /** The caller's expected version no longer matches the stored aggregate. */
    public static ApiException staleVersion(long expected, long actual) {
        return new ApiException(
                ErrorCode.STALE_VERSION,
                "The resource has changed since version %d was read".formatted(expected),
                Map.of("expectedVersion", expected, "currentVersion", actual));
    }

    /** ADR 0025: the principal lacks a capability at the required scope. */
    public static ApiException insufficientCapability(String capability, String scope) {
        return new ApiException(
                ErrorCode.INSUFFICIENT_CAPABILITY,
                "Requires %s at %s scope".formatted(capability, scope),
                Map.of("requiredCapability", capability, "requiredScope", scope));
    }

    /** ADR 0021: the tenant's plan does not include the feature. */
    public static ApiException entitlementRequired(String entitlementKey) {
        return new ApiException(
                ErrorCode.ENTITLEMENT_REQUIRED,
                "The current plan does not include %s".formatted(entitlementKey),
                Map.of("entitlementKey", entitlementKey));
    }
}
