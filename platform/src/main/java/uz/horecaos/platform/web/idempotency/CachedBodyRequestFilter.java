package uz.horecaos.platform.web.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import uz.horecaos.platform.web.CorrelationIdFilter;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Buffers request and response bodies so ADR 0031 idempotency can hash the
 * request before the controller runs and store the response after it does.
 *
 * <p>A servlet request body can normally be read once. The idempotency check
 * must hash it before handler invocation, and the stored response must be
 * replayable afterwards, so both directions are buffered for the mutating API
 * surface only.
 *
 * <p><strong>The buffer is capped, and the cap is not optional.</strong> This
 * filter is ordered at {@code HIGHEST_PRECEDENCE + 20}, and Spring Security's
 * chain registers at {@code -100}, so every byte read here is read before
 * anything has decided who the caller is. An unauthenticated POST to any
 * {@code /api/} path would otherwise pin its whole body in heap on the strength
 * of a {@code Content-Length} the caller chose. The largest body the API accepts
 * is a courier's batch of sixty observations, so a mebibyte is three orders of
 * magnitude of headroom over the real surface and still small enough that the
 * connection limit, not the heap, is what bounds a flood.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CachedBodyRequestFilter extends OncePerRequestFilter {

    static final String CACHED_BODY_ATTRIBUTE = "horecaos.cachedRequestBody";

    /** One mebibyte. See the class comment for why that is the right order. */
    static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    private final int maxBodyBytes;

    public CachedBodyRequestFilter(
            @Value("${horecaos.web.request-body.max-bytes:" + DEFAULT_MAX_BODY_BYTES + "}") int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws jakarta.servlet.ServletException, IOException {

        if (request.getContentLengthLong() > maxBodyBytes) {
            // The declared length is enough to refuse on. Reading first to find
            // out would spend exactly the memory this check exists to protect.
            refuse(response);
            return;
        }

        // One byte past the cap, so a body that declared nothing — a chunked
        // upload, or a lying Content-Length — is still caught by what actually
        // arrives rather than by what it claimed.
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            refuse(response);
            return;
        }
        request.setAttribute(CACHED_BODY_ATTRIBUTE, body);

        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        chain.doFilter(new CachedBodyRequest(request, body), cachedResponse);
        cachedResponse.copyBodyToResponse();
    }

    /**
     * Written by hand rather than raised as an {@code ApiException}.
     *
     * <p>{@link uz.horecaos.platform.web.api.GlobalApiErrorHandler} sits inside the
     * dispatcher servlet, and this filter runs before it — an exception thrown
     * here reaches the container's error page, not ADR 0031's problem document.
     * The shape ADR 0031 promises is reproduced so a client parses this refusal
     * the same way it parses every other one, correlation identifier included:
     * {@code CorrelationIdFilter} runs at {@code HIGHEST_PRECEDENCE}, so the MDC
     * is already populated by the time a body is refused here.
     *
     * <p>{@link ErrorCode} has no constant for this yet and adding one is a
     * registry change, so the code is written as a literal that matches the
     * registry's naming and URI conventions. It has to move into the enum before
     * a client is told to branch on it.
     */
    private static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // No keep-alive: the remainder of the body was never read, and a
        // container that has to swallow an arbitrary tail to reuse the
        // connection is doing the flood's work for it.
        response.setHeader(HttpHeaders.CONNECTION, "close");

        // Concatenated rather than serialised because CorrelationIdFilter admits
        // only `[A-Za-z0-9._:-]+` and substitutes a UUID for anything else, so
        // there is no character here that could escape the string.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        response.getWriter().write("""
                {"type":"https://docs.horecaos.uz/problems/request-body-too-large",\
                "title":"Request body too large",\
                "status":413,\
                "code":"REQUEST_BODY_TOO_LARGE",\
                "detail":"The request body exceeds the maximum accepted size"%s}""".formatted(
                        correlationId == null || correlationId.isBlank()
                                ? ""
                                : ",\"correlationId\":\"%s\"".formatted(correlationId)));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the mutating API surface needs buffering; reads pay nothing.
        String method = request.getMethod();
        boolean mutating = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return !mutating || !request.getRequestURI().startsWith("/api/");
    }

    /** Serves the buffered body so the controller can still bind it. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffered = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffered.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return buffered.read();
                }

                // Without this, InputStream's default multi-byte read calls
                // read() once per byte, which is the slow path for every framework
                // reader that requests a buffer at a time rather than one byte.
                @Override
                public int read(byte[] b, int off, int len) {
                    return buffered.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
