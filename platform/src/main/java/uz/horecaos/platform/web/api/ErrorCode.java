package uz.horecaos.platform.web.api;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * The stable, machine-readable error vocabulary (ADR 0031).
 *
 * <p>Clients branch on {@code code}, never on {@code title} or {@code detail},
 * so titles may be reworded and details localised without breaking anyone.
 * Adding a code is a compatible change; changing the meaning of one is not.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    MALFORMED_BODY(HttpStatus.BAD_REQUEST, "Malformed request body"),

    /**
     * ADR 0031 requires every effectful mutation to carry an idempotency key,
     * so its absence is a client error rather than a silently accepted retry
     * hazard.
     */
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency key required"),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /**
     * ADR 0051: the caller presented a real customer session that has ended —
     * expired, or signed out.
     *
     * <p>Deliberately distinct from {@link #UNAUTHENTICATED}, and the distinction
     * is the whole reason the code exists. Both are 401 and a client that branched
     * on status alone would treat them identically, which is how a customer whose
     * token expired halfway through a basket gets shown the screen a first-time
     * visitor sees — their place lost, and no explanation that anything happened.
     * A client seeing this one knows there was a session, so it can say so, keep
     * what it was holding, and ask the customer to sign in again rather than
     * starting them over.
     */
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "Session expired"),

    /** ADR 0025: the principal lacks the required capability at the required scope. */
    INSUFFICIENT_CAPABILITY(HttpStatus.FORBIDDEN, "Insufficient capability"),

    /**
     * ADR 0021: the tenant's plan does not include this feature. Deliberately
     * distinct from {@link #INSUFFICIENT_CAPABILITY} because the remediation is
     * completely different: upgrade a plan rather than grant a permission.
     */
    ENTITLEMENT_REQUIRED(HttpStatus.FORBIDDEN, "Entitlement required"),

    TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Tenant access denied"),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "Resource conflict"),

    /** The caller's expected version no longer matches the aggregate. */
    STALE_VERSION(HttpStatus.CONFLICT, "Stale version"),

    /** The same idempotency key arrived with a different request body. */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused"),

    /** A request with this idempotency key is still running; it never runs twice. */
    IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT, "Idempotency key in progress"),

    /**
     * ADR 0050: an action whose register entry requires a configured approval
     * policy was attempted with no valid policy at the target scope or an
     * ancestor. The operation is unchanged; an operator must configure its
     * control before retrying it.
     */
    APPROVAL_POLICY_REQUIRED(HttpStatus.CONFLICT, "Approval policy required"),

    /** ADR 0018: the quote's inputs changed; never silently charge the difference. */
    PRICE_CHANGED(HttpStatus.CONFLICT, "Price changed"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),

    /**
     * ADR 0045: the request is well formed and names real things, and the state
     * it arrived into refuses it.
     *
     * <p>Deliberately distinct from {@link #VALIDATION_FAILED}, which says the
     * client sent something wrong and should send something else, and from
     * {@link #RESOURCE_CONFLICT}, which says two writers raced. A courier's
     * handset posting observations after sign-off has sent a perfectly valid
     * batch and there is nothing to correct in it; the answer is that collection
     * has stopped, and the app should stop rather than retry. Answering that with
     * a 400 tells a client to fix a payload that is already right.
     */
    UNPROCESSABLE_STATE(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable in the current state"),

    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The documentation URI clients can follow; stable per code. */
    public String typeUri() {
        return "https://docs.horecaos.uz/problems/"
                + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
