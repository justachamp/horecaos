package uz.horecaos.platform.web.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Applies ADR 0031 idempotency to every endpoint declaring {@link Idempotent} or
 * {@code @RequiresCapability(mutating = true)}.
 *
 * <p>The key is client-supplied. Deriving it from a request hash was rejected in
 * ADR 0019 because two legitimately different carts can normalise to the same
 * hash; the hash here only detects a client reusing one key for two different
 * requests.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);
    private static final String RECORD_ATTRIBUTE = "horecaos.idempotencyRecordId";

    /** The width of {@code platform.idempotency_records.scope_key}. */
    private static final int MAX_SCOPE_KEY_LENGTH = 128;

    private final IdempotencyService idempotency;
    private final CurrentActor currentActor;
    private final ResponseBodyProtection responseProtection;

    public IdempotencyInterceptor(
            IdempotencyService idempotency, CurrentActor currentActor, ResponseBodyProtection responseProtection) {
        this.idempotency = idempotency;
        this.currentActor = currentActor;
        this.responseProtection = responseProtection;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        if (!requiresIdempotency(handler)) {
            return true;
        }

        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "This operation requires an %s header".formatted(IDEMPOTENCY_KEY_HEADER),
                    Map.of("header", IDEMPOTENCY_KEY_HEADER));
        }

        IdempotencyOutcome outcome = idempotency.begin(IdempotencyRequest.of(
                scopeKeyFor(handler, request),
                key,
                tenantIdOf(request),
                currentActor.get().subject(),
                bodyOf(request)));

        return switch (outcome) {
            case IdempotencyOutcome.Proceed proceed -> {
                request.setAttribute(RECORD_ATTRIBUTE, proceed.recordId());
                yield true;
            }
            case IdempotencyOutcome.Replay replay -> {
                writeReplay(response, replay, (HandlerMethod) handler, tenantIdOf(request));
                yield false;
            }
            case IdempotencyOutcome.InProgress ignored ->
                throw new ApiException(
                        ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
                        "A request with this idempotency key is still in progress");
            case IdempotencyOutcome.Conflict ignored ->
                throw new ApiException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This idempotency key was already used for a different request");
        };
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception failure) {

        UUID recordId = (UUID) request.getAttribute(RECORD_ATTRIBUTE);
        if (recordId == null) {
            return;
        }

        try {
            int status = response.getStatus();
            if (failure != null || status >= 500) {
                // Only an unexpected failure releases the key. A business
                // rejection is a settled outcome and is recorded below, so a
                // retry returns the same rejection instead of trying again.
                idempotency.release(recordId);
                return;
            }
            storeResponse(recordId, status, responseBodyOf(response), (HandlerMethod) handler, tenantIdOf(request));
        } catch (RuntimeException bookkeepingFailure) {
            log.error("Failed to finalise idempotency record {}", recordId, bookkeepingFailure);
        }
    }

    /**
     * Writes the response for a later replay, encrypted when the handler's own
     * response type says it carries personal data (ADR 0029).
     *
     * <p>Nothing about this reads the endpoint's annotations. The classification
     * comes from the response type, so an endpoint that starts answering with an
     * address is protected by the change that makes it do so.
     */
    private void storeResponse(
            UUID recordId, int status, String body, HandlerMethod handler, @Nullable UUID tenantId) {

        Optional<DataClass> classification = responseProtection.classificationOf(handler);
        if (classification.isEmpty()) {
            idempotency.complete(recordId, status, body, false);
            return;
        }
        if (tenantId == null) {
            // Envelope keys are per tenant, so there is no key to encrypt this
            // under. Fail closed: the record still bars a second execution, which
            // is what the key is for, and the body is simply not kept. A static
            // check refuses such an endpoint at build time, so reaching this is a
            // bug -- and the bug must not be a readable address.
            log.error(
                    "Refusing to store a classified response body with no tenant for {}#{}",
                    handler.getBeanType().getSimpleName(),
                    handler.getMethod().getName());
            idempotency.complete(recordId, status, null, false);
            return;
        }
        idempotency.complete(
                recordId, status, responseProtection.protect(tenantId, classification.get(), recordId, body), true);
    }

    /**
     * Two declarations, because the endpoints that need replay protection are not
     * the endpoints that need a capability.
     *
     * <p>{@code @RequiresCapability(mutating = true)} was the only trigger, which
     * made idempotency a side effect of an authorization decision. The storefront
     * is where that breaks: a customer acts on their own cart rather than under a
     * delegated grant, so those handlers declare no capability — and reading the
     * trigger only off the capability would leave checkout and payment-session
     * creation with no key at all, which on the payment path means a second
     * attempt against the same order.
     */
    private boolean requiresIdempotency(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        if (method.getMethodAnnotation(Idempotent.class) != null) {
            return true;
        }
        RequiresCapability declaration = method.getMethodAnnotation(RequiresCapability.class);
        return declaration != null && declaration.mutating();
    }

    /**
     * Namespaces the key by operation <em>and by the resource it acts on</em>.
     *
     * <p>The handler alone is not enough. Two calls to the same endpoint for two
     * different orders are two different operations, and a key naming only the
     * endpoint makes them one: the second is answered with the first's cached
     * response, so a customer opening a payment session for order B is handed
     * order A's checkout link, amount and merchant transaction id, and order B
     * never gets an attempt at all. The bodies do not save it either — on these
     * endpoints the body is a handful of optional fields that is {@code {}} for
     * almost every caller, so the request hash discriminates nothing.
     *
     * <p>The path variables are what distinguish the resource, so they go in the
     * key, sorted so that map iteration order cannot make the same request
     * produce two different keys.
     */
    @SuppressWarnings("unchecked")
    private String scopeKeyFor(Object handler, HttpServletRequest request) {
        HandlerMethod method = (HandlerMethod) handler;
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

        String resource = variables == null || variables.isEmpty()
                ? ""
                : variables.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + '=' + entry.getValue())
                        .collect(Collectors.joining(",", " ", ""));

        return withinColumn("%s %s#%s%s"
                .formatted(
                        request.getMethod(),
                        method.getBeanType().getSimpleName(),
                        method.getMethod().getName(),
                        resource));
    }

    /**
     * Keeps the key inside {@code scope_key varchar(128)}.
     *
     * <p>Naming the resource made the key outgrow the column. Three UUID path
     * variables are 135 characters before the endpoint is named at all, so an
     * endpoint under {@code /tenants/{tenantId}/brands/{brandId}/orders/{orderId}}
     * exceeded it on every request, and the insert failed as a data-integrity
     * violation that the error handler renders as a 409. The symptom was a
     * payment session answering "conflicts with an existing resource" to a
     * first-ever request, and the same for a checkout, a catalog edit and a
     * courier handover.
     *
     * <p>Keeping only the leading characters would be worse than the overflow:
     * the readable prefix is the endpoint, so two orders on one endpoint would
     * reduce to the same key and the second customer would be handed the first
     * one's response. So the tail becomes a digest of the whole key instead — the
     * prefix stays legible to somebody reading the table, and everything dropped
     * from it is still what distinguishes one resource from another.
     *
     * <p>Bounded here rather than by widening the column, because a column wide
     * enough for today's longest path is still a column a longer path outgrows,
     * and the failure mode is a 409 on a payment.
     */
    private static String withinColumn(String scopeKey) {
        if (scopeKey.length() <= MAX_SCOPE_KEY_LENGTH) {
            return scopeKey;
        }
        String digest = sha256(scopeKey);
        return scopeKey.substring(0, MAX_SCOPE_KEY_LENGTH - digest.length() - 1) + '~' + digest;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    @SuppressWarnings("unchecked")
    private @Nullable UUID tenantIdOf(HttpServletRequest request) {
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null) {
            return null;
        }
        String tenantId = variables.get("tenantId");
        return tenantId == null ? null : UUID.fromString(tenantId);
    }

    private String bodyOf(HttpServletRequest request) {
        byte[] body = (byte[]) request.getAttribute(CachedBodyRequestFilter.CACHED_BODY_ATTRIBUTE);
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    private String responseBodyOf(HttpServletResponse response) {
        ContentCachingResponseWrapper cached =
                WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        return cached == null ? "" : new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the recorded response, decrypting it when it was stored as an
     * envelope.
     *
     * <p>A decrypt that fails is not answered with the ciphertext or with a
     * quiet empty body. The envelope binds to its tenant and its row, so a
     * failure here means the record is not the one it claims to be — and
     * replaying a body that failed its integrity check is how one caller is
     * handed another's address.
     */
    private void writeReplay(
            HttpServletResponse response,
            IdempotencyOutcome.Replay replay,
            HandlerMethod handler,
            @Nullable UUID tenantId)
            throws IOException {

        String body = replay.responseBody() == null ? "" : replay.responseBody();
        if (replay.responseBodyProtected()) {
            if (tenantId == null) {
                // storeResponse only ever marks a record protected when it had a
                // tenant to encrypt the body under, so a protected record with no
                // tenant on replay means this endpoint answered with and without a
                // tenant across two requests for the same scope key -- a routing
                // bug, not an outcome to paper over with a guessed key.
                throw new IllegalStateException("A protected idempotent response has no tenant to decrypt it with");
            }
            try {
                body = responseProtection.reveal(tenantId, replay.recordId(), replay.responseBody());
            } catch (RuntimeException failure) {
                log.error(
                        "A stored response body failed to decrypt for {}#{}",
                        handler.getBeanType().getSimpleName(),
                        handler.getMethod().getName(),
                        failure);
                throw new ApiException(
                        ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
                        "The recorded response for this idempotency key could not be replayed");
            }
        }
        response.setStatus(replay.responseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(REPLAYED_HEADER, "true");
        response.getWriter().write(body);
        response.flushBuffer();
    }
}
