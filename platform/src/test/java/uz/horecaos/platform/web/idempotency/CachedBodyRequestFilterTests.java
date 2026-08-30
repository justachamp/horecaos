package uz.horecaos.platform.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The buffer this filter keeps is filled before Spring Security has decided who
 * the caller is — the filter is ordered at {@code HIGHEST_PRECEDENCE + 20} and
 * the security chain registers at {@code -100}. Uncapped, that is an anonymous
 * caller choosing how much of the heap to hold, so the cap is asserted from both
 * directions: the declared length and the bytes that actually arrive.
 */
class CachedBodyRequestFilterTests {

    private static final int CAP = 4096;

    @Test
    void aBodyWithinTheCapIsBufferedAndStillReadableByTheHandler() throws Exception {
        byte[] body = "{\"note\":\"small\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = post(body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenByHandler = new AtomicReference<>();
        FilterChain chain = (passed, passedResponse) ->
                seenByHandler.set(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        new CachedBodyRequestFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenByHandler.get()).isEqualTo("{\"note\":\"small\"}");
        assertThat((byte[]) request.getAttribute(CachedBodyRequestFilter.CACHED_BODY_ATTRIBUTE))
                .isEqualTo(body);
    }

    @Test
    void anOversizedDeclaredLengthIsRefusedWithoutReadingTheBody() throws Exception {
        MockHttpServletRequest request = post(new byte[CAP + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new CachedBodyRequestFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString())
                .as("ADR 0031 clients branch on the code, and this refusal arrives before the "
                        + "handler that would otherwise produce one")
                .contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(chain.getRequest())
                .as("nothing downstream, including Spring Security, should see the request")
                .isNull();
        assertThat(request.getAttribute(CachedBodyRequestFilter.CACHED_BODY_ATTRIBUTE))
                .isNull();
    }

    @Test
    void aBodyThatDeclaresNoLengthIsCappedByWhatActuallyArrives() throws Exception {
        // A chunked upload, or a Content-Length that lied. Refusing on the header
        // alone would leave the only path that matters wide open.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[CAP * 4]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new CachedBodyRequestFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void aBodyExactlyAtTheCapIsAccepted() throws Exception {
        MockHttpServletRequest request = post(new byte[CAP]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new CachedBodyRequestFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void readsAndNonApiPathsAreNeverBuffered() {
        CachedBodyRequestFilter filter = new CachedBodyRequestFilter(CAP);

        assertThat(shouldNotFilter(filter, new MockHttpServletRequest("GET", "/api/v1/orders")))
                .isTrue();
        assertThat(shouldNotFilter(filter, new MockHttpServletRequest("POST", "/actuator/health")))
                .isTrue();
        assertThat(shouldNotFilter(filter, new MockHttpServletRequest("POST", "/api/v1/orders")))
                .isFalse();
    }

    @Test
    void theDefaultCapClearsTheLargestBodyTheApiAccepts() {
        // A courier's batch is sixty observations; nothing else on the mutating
        // surface is a multipart upload. The default has to sit far above that
        // and far below anything that threatens the heap.
        assertThat(CachedBodyRequestFilter.DEFAULT_MAX_BODY_BYTES)
                .isGreaterThan(256 * 1024)
                .isLessThanOrEqualTo(4 * 1024 * 1024);
    }

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    private static boolean shouldNotFilter(CachedBodyRequestFilter filter, HttpServletRequest request) {
        try {
            var method = CachedBodyRequestFilter.class.getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
            method.setAccessible(true);
            return (boolean) method.invoke(filter, request);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }
}
