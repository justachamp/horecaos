package uz.horecaos.platform.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Direct unit tests for the two overrides that MockMvc cannot realistically
 * trigger (ADR 0031's residual gap).
 *
 * <p>{@link ConversionNotSupportedException} and {@link HttpMessageNotWritableException}
 * both signal the conversion or serialization machinery itself failing, not a
 * bad request -- provoking one through a real HTTP round trip would mean
 * deliberately breaking a converter or a DTO's serialization, which is not
 * something a MockMvc request can do from the outside. {@code
 * LocalFixtureStorefrontTests} covers the route-level cases (an unmapped
 * path, an unacceptable {@code Accept} header, a missing required header);
 * these two call the overridden handler methods directly, the same way the
 * class itself is exercised by Spring for a real failure, and assert the
 * {@link ApiProblem} shape that comes back.
 */
class GlobalApiErrorHandlerTests {

    private final GlobalApiErrorHandler handler = new GlobalApiErrorHandler();

    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void conversionNotSupportedAnswersAsAnInternalErrorRatherThanClientFault() throws Exception {
        ConversionNotSupportedException exception = new ConversionNotSupportedException(
                "some-value", String.class, new IllegalStateException("converter bug"));

        ResponseEntity<Object> response = Objects.requireNonNull(handler.handleConversionNotSupported(
                exception, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, webRequest()));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ProblemDetail problem = (ProblemDetail) Objects.requireNonNull(response.getBody());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        // The converter's own message can name an internal type or property path,
        // so it must never reach the client; only a generic sentence does.
        assertThat(problem.getDetail()).doesNotContain("converter bug");
    }

    @Test
    void httpMessageNotWritableAnswersAsAnInternalErrorRatherThanClientFault() throws Exception {
        HttpMessageNotWritableException exception =
                new HttpMessageNotWritableException("Could not write JSON: unexpected getter failure");

        ResponseEntity<Object> response = Objects.requireNonNull(handler.handleHttpMessageNotWritable(
                exception, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, webRequest()));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ProblemDetail problem = (ProblemDetail) Objects.requireNonNull(response.getBody());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(problem.getDetail()).doesNotContain("getter failure");
    }
}
