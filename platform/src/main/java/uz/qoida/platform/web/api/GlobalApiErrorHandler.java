package uz.qoida.platform.web.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import uz.qoida.platform.iam.api.AuthorizationService;

/**
 * One error handler for every API surface (ADR 0031).
 *
 * <p>Bespoke per-module handlers were the alternative, and they force every
 * client to write per-endpoint error handling. The whole point of a stable code
 * registry is lost if two controllers describe the same failure differently.
 */
@RestControllerAdvice
public class GlobalApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail apiException(ApiException exception) {
        return ApiProblem.withProperties(
                exception.errorCode(), exception.getMessage(), exception.properties());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        List<ApiProblem.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiProblem.FieldError(
                        error.getField(),
                        error.getCode() == null ? "INVALID" : toStableCode(error.getCode()),
                        error.getDefaultMessage()))
                .toList();

        return ApiProblem.withFieldErrors(
                ErrorCode.VALIDATION_FAILED, "One or more request fields are invalid", errors);
    }

    /**
     * A request named a tenant, brand or location hierarchy that does not exist.
     *
     * <p>Answered as not-found rather than forbidden, and without echoing the
     * identifiers back. Distinguishing "no such brand" from "not your brand"
     * would let a caller confirm which identifiers are real by watching which
     * status they get, which is precisely the probing the hierarchy check exists
     * to stop.
     */
    @ExceptionHandler(uz.qoida.platform.web.authorization.ScopeNotFoundException.class)
    ProblemDetail scopeNotFound(uz.qoida.platform.web.authorization.ScopeNotFoundException exception) {
        return ApiProblem.of(ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail staleVersion() {
        return ApiProblem.of(
                ErrorCode.STALE_VERSION,
                "The resource has changed since it was read. Re-read it and retry with the current version.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception) {
        // The database message can name constraints, columns, and stored values,
        // so it is logged rather than returned.
        log.warn("Data integrity violation mapped to {}", ErrorCode.RESOURCE_CONFLICT, exception);
        return ApiProblem.of(
                ErrorCode.RESOURCE_CONFLICT, "The requested value conflicts with an existing resource");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied() {
        return ApiProblem.of(
                ErrorCode.TENANT_ACCESS_DENIED,
                "The authenticated principal cannot access this resource");
    }

    /**
     * ADR 0025: the principal lacks the declared capability at the declared
     * scope.
     *
     * <p>Distinct from {@link #accessDenied()} because the remediation is
     * different and the client cannot tell the two apart from the status alone:
     * this one is fixed by granting a role, the other by using a principal that
     * belongs to the tenant. The response names the capability and the scope
     * level so an operator knows what to grant, and never the grants the
     * principal does hold or the policy that produced the decision.
     *
     * <p>This exception is Qoida's own, not Spring Security's, so without a
     * handler of its own it would have fallen through to a 500. That was
     * unreachable while every capability declaration ran in shadow mode.
     */
    @ExceptionHandler(AuthorizationService.AccessDeniedException.class)
    ProblemDetail insufficientCapability(AuthorizationService.AccessDeniedException exception) {
        return apiException(ApiException.insufficientCapability(
                exception.capability().code(), exception.scope().type().name()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable() {
        return ApiProblem.of(
                ErrorCode.MALFORMED_BODY,
                "The request body is malformed or contains an unsupported value");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMediaType() {
        return ApiProblem.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Only application/json is accepted");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(IllegalArgumentException exception) {
        return ApiProblem.of(ErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    private static String toStableCode(String springValidationCode) {
        return switch (springValidationCode) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Size", "Length" -> "OUT_OF_RANGE";
            case "Pattern" -> "MALFORMED";
            case "Positive", "PositiveOrZero", "Min", "Max" -> "OUT_OF_RANGE";
            case "Email" -> "MALFORMED";
            default -> "INVALID";
        };
    }
}
