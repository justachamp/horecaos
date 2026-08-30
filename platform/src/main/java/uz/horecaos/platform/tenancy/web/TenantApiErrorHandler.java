package uz.horecaos.platform.tenancy.web;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.horecaos.platform.tenancy.application.TenantResourceConflictException;
import uz.horecaos.platform.tenancy.application.TenantResourceNotFoundException;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Maps tenancy-specific failures onto the shared ADR 0031 error codes.
 *
 * <p>Everything a controller shares with the rest of the platform - validation,
 * optimistic locking, access denial, malformed bodies - is handled once in
 * {@code GlobalApiErrorHandler}. Only failures this module alone can raise
 * belong here.
 */
@RestControllerAdvice(
        assignableTypes = {
            TenantControlPlaneController.class,
            SalesChannelController.class,
            ServiceScheduleController.class,
            LocationServiceOperationsController.class,
            LegalEntityController.class
        })
public class TenantApiErrorHandler {

    @ExceptionHandler(TenantResourceNotFoundException.class)
    ProblemDetail notFound(TenantResourceNotFoundException exception) {
        return ApiProblem.of(ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(TenantResourceConflictException.class)
    ProblemDetail conflict(TenantResourceConflictException exception) {
        return ApiProblem.of(ErrorCode.RESOURCE_CONFLICT, exception.getMessage());
    }

    /**
     * A business conflict raised as a bare {@link IllegalStateException} rather
     * than the module's own {@link TenantResourceConflictException}.
     *
     * <p>{@code LegalEntity.requireStatus} and {@code
     * JdbcLegalEntityStore.explain} (a duplicate TIN, a duplicate code, two
     * assignments overlapping one location on one day) all raise this rather
     * than a typed exception, so without this handler an entirely ordinary
     * business refusal — activate an already-archived entity, register a
     * taxpayer number that already exists — reached no handler here and fell
     * through to a 500. It is exactly the same failure a stale-transition guard
     * elsewhere in this domain model can raise, so the mapping is the same one
     * {@link TenantResourceConflictException} gets.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail stateConflict(IllegalStateException exception) {
        return ApiProblem.of(ErrorCode.RESOURCE_CONFLICT, exception.getMessage());
    }

    /**
     * A refused argument is a client mistake, not a server fault.
     *
     * <p>ADR 0036 puts a closed set behind {@code system_type}; naming a type
     * outside it must come back as a 400 listing the set, because the alternative
     * is a 500 and an operator who cannot tell a typo from an outage.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        return ApiProblem.of(ErrorCode.VALIDATION_FAILED, exception.getMessage());
    }
}
