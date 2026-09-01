package uz.horecaos.platform.web.api;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import uz.horecaos.platform.iam.api.AuthorizationService;

/**
 * One error handler for every API surface (ADR 0031).
 *
 * <p>Bespoke per-module handlers were the alternative, and they force every
 * client to write per-endpoint error handling. The whole point of a stable code
 * registry is lost if two controllers describe the same failure differently.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than sitting beside
 * it. Spring MVC's own binding and content-negotiation failures — a missing
 * {@code @RequestParam}, a non-UUID path segment, an unparseable body, a
 * disallowed method — are raised by the framework itself, not by this
 * platform's code, and previously matched no {@code @ExceptionHandler} here.
 * With nothing to catch them, {@code ExceptionHandlerExceptionResolver}
 * declined and the request fell to {@code DefaultHandlerExceptionResolver},
 * which renders the container's default error body rather than this class's
 * Problem Details shape — see {@code SecurityConfiguration}'s
 * {@code dispatcherTypeMatchers(ERROR)} comment for how that was first found.
 * {@code ResponseEntityExceptionHandler} already maps every one of those
 * exceptions to a {@link ProblemDetail}; overriding its {@code handleXxx}
 * methods keeps that coverage while attaching this platform's {@code code} and
 * {@code correlationId} extensions so the shape matches every other error this
 * API returns.
 */
@RestControllerAdvice
public class GlobalApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail apiException(ApiException exception) {
        return ApiProblem.withProperties(exception.errorCode(), detailOrTitle(exception), exception.properties());
    }

    /**
     * A {@code @Valid @RequestBody} failed bean validation.
     *
     * <p>Overrides the base class rather than adding a sibling
     * {@code @ExceptionHandler(MethodArgumentNotValidException.class)}: both map
     * the same exception type, and {@code ExceptionHandlerMethodResolver} rejects
     * two methods claiming one type as an ambiguous mapping. Behaviour is
     * unchanged from before this class extended {@code ResponseEntityExceptionHandler}.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiProblem.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiProblem.FieldError(
                        error.getField(),
                        error.getCode() == null ? "INVALID" : toStableCode(error.getCode()),
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ApiProblem.withFieldErrors(
                ErrorCode.VALIDATION_FAILED, "One or more request fields are invalid", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * A required {@code @RequestParam} was not present on the request.
     *
     * <p>The reproduction that surfaced this whole class of bug: the storefront
     * menu requires {@code channel} (ADR 0036), and until this override existed
     * the container's default error body answered instead of this platform's
     * Problem Details — see the class javadoc. The missing name is named twice,
     * once in {@code detail} for a human and once in {@code errors} for a client
     * that already knows how to read a field-level failure, so this looks like
     * every other validation failure rather than a special case.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String parameter = exception.getParameterName();
        ApiProblem.FieldError error = new ApiProblem.FieldError(
                parameter, "REQUIRED", "Required request parameter '" + parameter + "' is missing");
        ProblemDetail problem = ApiProblem.withFieldErrors(
                ErrorCode.VALIDATION_FAILED,
                "Required request parameter '" + parameter + "' is not present",
                List.of(error));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * A path or query value could not be converted to the type the handler
     * declares — the other classic alongside a missing parameter: a non-UUID
     * segment in a {@code {tenantId}} path variable.
     *
     * <p>{@link org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}
     * is a {@link TypeMismatchException}, and the base class dispatches both
     * through this one method; overriding it here covers both without a second
     * override. The offending value is never echoed into the response — a
     * caller who sent something unparseable does not need it read back, and
     * ADR 0031 keeps {@code detail} to what is needed to act on the failure.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = exception.getPropertyName() != null ? exception.getPropertyName() : "value";
        String requiredType = exception.getRequiredType() != null
                ? exception.getRequiredType().getSimpleName()
                : "the expected type";
        ApiProblem.FieldError error = new ApiProblem.FieldError(field, "MALFORMED", "Must be a valid " + requiredType);
        ProblemDetail problem = ApiProblem.withFieldErrors(
                ErrorCode.VALIDATION_FAILED, "'" + field + "' must be a valid " + requiredType, List.of(error));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * The request body could not be parsed as JSON.
     *
     * <p>Overrides the base class for the same ambiguous-mapping reason as
     * {@link #handleMethodArgumentNotValid}. The parser's own message is never
     * returned — it can quote raw request content — so the detail stays the
     * generic sentence this platform used before this class extended
     * {@code ResponseEntityExceptionHandler}.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ApiProblem.of(
                ErrorCode.MALFORMED_BODY, "The request body is malformed or contains an unsupported value");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** Overrides the base class for the same ambiguous-mapping reason as {@link #handleMethodArgumentNotValid}. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ApiProblem.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Only application/json is accepted");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * The path matched a registered endpoint, but not with this HTTP method —
     * for example {@code DELETE} on a route only mapped for {@code GET}.
     *
     * <p>{@code headers} carries the {@code Allow} header the base exception
     * already computed from the route's supported methods (RFC 9110 requires
     * it on a 405), so it is passed through rather than dropped.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ApiProblem.of(
                ErrorCode.METHOD_NOT_ALLOWED,
                "Method '" + exception.getMethod() + "' is not supported on this resource");
        return handleExceptionInternal(exception, problem, headers, status, request);
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
    @ExceptionHandler(uz.horecaos.platform.web.authorization.ScopeNotFoundException.class)
    ProblemDetail scopeNotFound(uz.horecaos.platform.web.authorization.ScopeNotFoundException exception) {
        return ApiProblem.of(ErrorCode.RESOURCE_NOT_FOUND, detailOrTitle(ErrorCode.RESOURCE_NOT_FOUND, exception));
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
        return ApiProblem.of(ErrorCode.RESOURCE_CONFLICT, "The requested value conflicts with an existing resource");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied() {
        return ApiProblem.of(ErrorCode.TENANT_ACCESS_DENIED, "The authenticated principal cannot access this resource");
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
     * <p>This exception is HorecaOS's own, not Spring Security's, so without a
     * handler of its own it would have fallen through to a 500. That was
     * unreachable while every capability declaration ran in shadow mode.
     */
    @ExceptionHandler(AuthorizationService.AccessDeniedException.class)
    ProblemDetail insufficientCapability(AuthorizationService.AccessDeniedException exception) {
        return apiException(ApiException.insufficientCapability(
                exception.capability().code(), exception.scope().type().name()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(IllegalArgumentException exception) {
        return ApiProblem.of(ErrorCode.INVALID_REQUEST, detailOrTitle(ErrorCode.INVALID_REQUEST, exception));
    }

    /**
     * {@code ApiException}'s own message, or a generic fallback.
     *
     * <p>{@code Throwable#getMessage()} is nullable in the JDK and {@code
     * ApiException} accepts a null one, but every Problem Details response must
     * carry real detail text — never the literal word "null" serialized to a
     * client.
     */
    private static String detailOrTitle(ApiException exception) {
        return detailOrTitle(exception.errorCode(), exception);
    }

    private static String detailOrTitle(ErrorCode code, Throwable exception) {
        @Nullable String message = exception.getMessage();
        return message == null || message.isBlank() ? code.title() : message;
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
