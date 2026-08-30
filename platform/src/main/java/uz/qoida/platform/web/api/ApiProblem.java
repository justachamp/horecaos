package uz.qoida.platform.web.api;

import java.net.URI;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 Problem Details responses in the one shape every Qoida
 * surface uses (ADR 0031).
 *
 * <p>{@code detail} is written for a developer reading a response, and must
 * never contain PII, secrets, SQL, or a stack trace. An authorization failure
 * names the missing capability, never the policy that produced the decision.
 */
public final class ApiProblem {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private ApiProblem() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setType(URI.create(code.typeUri()));
        problem.setProperty("code", code.name());

        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    public static ProblemDetail withFieldErrors(ErrorCode code, String detail, List<FieldError> errors) {
        ProblemDetail problem = of(code, detail);
        problem.setProperty("errors", errors);
        return problem;
    }

    public static ProblemDetail withProperties(ErrorCode code, String detail, java.util.Map<String, Object> extra) {
        ProblemDetail problem = of(code, detail);
        extra.forEach(problem::setProperty);
        return problem;
    }

    /** A field-level validation failure with a stable code, not a prose message. */
    public record FieldError(String field, String code, String message) { }
}
