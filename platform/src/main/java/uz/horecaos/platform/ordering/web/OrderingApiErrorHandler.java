package uz.horecaos.platform.ordering.web;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.horecaos.platform.migration.api.TargetWritesFencedException;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Maps ordering-specific failures onto the shared ADR 0031 error codes.
 *
 * <p>Everything a controller shares with the rest of the platform — validation,
 * optimistic locking, access denial, malformed bodies — is handled once in
 * {@code GlobalApiErrorHandler}. Only failures this module alone can raise belong
 * here.
 */
@RestControllerAdvice(assignableTypes = {StorefrontOrderingController.class, OperationsOrderController.class})
public class OrderingApiErrorHandler {

    /**
     * The migration fenced this branch's orders, so no order was taken.
     *
     * <p>A {@code RuntimeException} by inheritance, so without this it would
     * surface as a 500 during a cutover — every storefront checkout on the fenced
     * branch reporting an outage, and somebody paged about a system that is
     * working exactly as designed. It is a conflict rather than a fault: the write
     * was refused because something else owns this capability right now, which is
     * temporary and is resolved by the cutover finishing or the scope resuming,
     * not by a retry against this platform.
     *
     * <p>The scope id travels as a property, because the first question anyone
     * asks about a fenced checkout is which scope fenced it, and it turns a
     * support ticket into a row somebody can look at. It is a control-plane
     * identifier, not a tenant's data.
     *
     * <p>The scope's state and write mode are deliberately left in the message
     * rather than lifted into properties. They are {@code migration.domain} types
     * — the control plane's internal vocabulary — and reading them here would make
     * ordering depend on a part of the migration module that is not published to
     * it. The exception already spells both into its message, which is what an
     * operator reads anyway.
     */
    @ExceptionHandler(TargetWritesFencedException.class)
    ProblemDetail fenced(TargetWritesFencedException exception) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("capability", exception.capability().name());
        properties.put("migrationScopeId", exception.scopeId());
        return ApiProblem.withProperties(
                ErrorCode.RESOURCE_CONFLICT,
                // Throwable#getMessage is nullable by the JDK's own contract, even
                // though this exception's own message always carries the scope's
                // state and write mode.
                Objects.requireNonNullElse(exception.getMessage(), "Target writes are fenced"),
                properties);
    }
}
