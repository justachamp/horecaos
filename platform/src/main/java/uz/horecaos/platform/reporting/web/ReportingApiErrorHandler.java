package uz.horecaos.platform.reporting.web;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.horecaos.platform.reporting.application.MetricSigningService;
import uz.horecaos.platform.reporting.application.ReportingRefusals;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Maps reporting's refusals onto the shared ADR 0031 error codes.
 *
 * <p>Each of these is a case where a number would be worse than no number, so
 * each carries enough in its properties for a client to say why rather than
 * showing a generic failure. A report that fails with "bad request" and nothing
 * else teaches an operator that reporting is flaky, which is the reputation this
 * ADR is trying to avoid.
 *
 * <p>The codes are the existing shared vocabulary rather than new ones. ADR 0043
 * names {@code UNKNOWN_METRIC} and {@code CAPABILITY_REQUIRED}; the first is
 * carried as a {@code reason} property on a validation failure and the second is
 * already {@code INSUFFICIENT_CAPABILITY}. Adding codes to
 * {@code web.api.ErrorCode} is a change to a file every module shares and is left
 * to whoever owns that vocabulary.
 */
@RestControllerAdvice(assignableTypes = {ReportingController.class, MetricSignatureController.class})
public class ReportingApiErrorHandler {

    /** ADR 0043: an unknown metric id is rejected and never ignored. */
    @ExceptionHandler(MetricRegistry.UnknownMetricException.class)
    ProblemDetail unknownMetric(MetricRegistry.UnknownMetricException exception) {
        return ApiProblem.withProperties(
                ErrorCode.VALIDATION_FAILED,
                exception.getMessage(),
                Map.of("reason", "UNKNOWN_METRIC", "metricCode", exception.code()));
    }

    /**
     * ADR 0038: a money total across two legal entities reconciles to neither tax
     * filing, so it is refused rather than narrowed or footnoted.
     */
    @ExceptionHandler(ReportingRefusals.CombinedEntityTotalException.class)
    ProblemDetail combinedEntityTotal(ReportingRefusals.CombinedEntityTotalException exception) {
        return ApiProblem.withProperties(
                ErrorCode.VALIDATION_FAILED,
                exception.getMessage(),
                Map.of("reason", "LEGAL_ENTITY_GROUPING_REQUIRED", "metricCodes", exception.metricCodes()));
    }

    @ExceptionHandler(ReportingRefusals.MixedBoundaryRegimeException.class)
    ProblemDetail mixedBoundary(ReportingRefusals.MixedBoundaryRegimeException exception) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("reason", "MIXED_BUSINESS_DAY_BOUNDARY");
        properties.put("recutCompletedThrough", String.valueOf(exception.recutCompletedThrough()));
        return ApiProblem.withProperties(ErrorCode.RESOURCE_CONFLICT, exception.getMessage(), properties);
    }

    /** Defined but unbuilt. Refused rather than answered with a zero somebody believes. */
    @ExceptionHandler(ReportingRefusals.MetricNotBuiltException.class)
    ProblemDetail notBuilt(ReportingRefusals.MetricNotBuiltException exception) {
        return ApiProblem.withProperties(
                ErrorCode.VALIDATION_FAILED,
                exception.getMessage(),
                Map.of("reason", "METRIC_NOT_BUILT", "metricCode", exception.metricCode()));
    }

    @ExceptionHandler(ReportingRefusals.NonScalarMetricException.class)
    ProblemDetail nonScalar(ReportingRefusals.NonScalarMetricException exception) {
        return ApiProblem.withProperties(
                ErrorCode.VALIDATION_FAILED,
                exception.getMessage(),
                Map.of("reason", "NON_SCALAR_METRIC", "metricCode", exception.metricCode()));
    }

    /** A signature is recorded once. Replacing it would lose who actually decided. */
    @ExceptionHandler(MetricSigningService.AlreadySignedException.class)
    ProblemDetail alreadySigned(MetricSigningService.AlreadySignedException exception) {
        return ApiProblem.withProperties(
                ErrorCode.RESOURCE_CONFLICT, exception.getMessage(), Map.of("reason", "METRIC_ALREADY_SIGNED"));
    }
}
