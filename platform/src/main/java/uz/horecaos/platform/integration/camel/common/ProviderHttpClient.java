package uz.horecaos.platform.integration.camel.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * One JSON-over-HTTP call to a provider, classified into a {@link ProviderOutcome}.
 *
 * <p>The whole point of this class is that adapters never see an exception. Every
 * failure — connect refused, read timeout, 429, 500, malformed body — arrives as
 * one of the four outcomes, decided by {@link ProviderExceptionClassifier} using
 * the one fact adapters cannot recover after the fact: whether the request was
 * actually put on the wire before it failed.
 */
@Component
public class ProviderHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttpClient.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** The key the HTTP status is held under when the caller asked to hear a non-2xx status as an answer. */
    public static final String STATUS_KEY = "$status";

    /**
     * The most of a provider's answer this client will hold in memory.
     *
     * <p>Generous for what actually arrives — a created invoice, a booked
     * shipment, or an error body the failure path reduces to a handful of fields
     * anyway — and small enough that a provider answering 200 and then streaming
     * without end cannot take the heap with it.
     */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * The fields of a provider error body worth carrying into an outcome, in the
     * order an operator reads them.
     *
     * <p>An allowlist, because the rest of the body is the request coming back.
     * Providers on these routes answer a rejected booking by repeating what they
     * were sent, and what they were sent is a customer's address, name, and
     * phone number — which the {@code detail} is then persisted with, returned
     * through {@code /pos/sync-runs}, and written into an onboarding step record,
     * all outside envelope encryption.
     */
    private static final List<String> DIAGNOSTIC_FIELDS = List.of(
            "error",
            "error_code",
            "errorCode",
            "code",
            "error_note",
            "message",
            "reason",
            "title",
            "description",
            "request_id",
            "trace_id",
            "correlation_id");

    /**
     * Fields whose value is the provider's own opaque handle for the call, never
     * anything about the customer, and therefore not worth mangling.
     */
    private static final Set<String> OPAQUE_HANDLES = Set.of("request_id", "trace_id", "correlation_id");

    /**
     * Long digit runs and addresses, masked out of whatever text does survive.
     *
     * <p>Five digits rather than four keeps a numeric provider error code
     * readable while removing every phone number, card fragment, and passport
     * number, which are the shapes that actually turn up embedded in a message.
     */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{5,}");

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /** Long enough for "HorecaOS's integrator registration is unknown", short enough to be no story. */
    private static final int MAX_FIELD_CHARS = 120;

    private static final int MAX_DETAIL_CHARS = 500;

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final ProviderExceptionClassifier classifier;

    public ProviderHttpClient(ObjectMapper objectMapper, ProviderExceptionClassifier classifier) {
        this.objectMapper = objectMapper;
        this.classifier = classifier;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Redirects are not followed: a provider redirecting a POST is
                // either misconfigured or hostile, and replaying a booking
                // against an unverified host is not a risk worth taking.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ProviderOutcome post(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            @Nullable Object body,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "POST", path, "", path, headers, body, onSuccess);
    }

    public ProviderOutcome patch(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            @Nullable Object body,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "PATCH", path, "", path, headers, body, onSuccess);
    }

    public ProviderOutcome put(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            @Nullable Object body,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "PUT", path, "", path, headers, body, onSuccess);
    }

    public ProviderOutcome get(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "GET", path, "", path, headers, null, onSuccess);
    }

    /**
     * A GET whose request is carried in the query string, for a provider that offers no other
     * way to ask (a geocoder reads its address from {@code ?geocode=} and has no body).
     *
     * <p><strong>The query is never logged and never reaches an outcome.</strong> It is kept
     * apart from {@code path} for exactly that reason: this class's own failure logging names the
     * path, and a path that carried an address and a key would put both into the log aggregator
     * on the first timeout. The query is percent-encoded here, so a malformed one cannot raise
     * an {@code IllegalArgumentException} whose message is the request URI.
     *
     * <p>{@code query} keeps its iteration order, so a caller that wants a stable URL passes a
     * {@code LinkedHashMap}.
     */
    public ProviderOutcome get(
            ProviderCall call,
            String path,
            Map<String, String> query,
            Map<String, String> headers,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "GET", path, encodeQuery(query), path, headers, null, onSuccess);
    }

    private static String encodeQuery(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder("?");
        query.forEach((name, value) -> {
            if (encoded.length() > 1) {
                encoded.append('&');
            }
            // URLEncoder writes a space as '+', which is right inside a form body and ambiguous
            // inside a URI; %20 is unambiguous everywhere.
            encoded.append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("+", "%20"));
        });
        return encoded.toString();
    }

    /**
     * A GET whose answers include some non-2xx statuses the caller wants to read as
     * answers: {@code 404} to "do you hold this document" says it does not, which is
     * information rather than a fault, and {@link ProviderOutcome} cannot otherwise tell a
     * 404 from any other 4xx. The status arrives in the parsed map under {@link #STATUS_KEY}.
     */
    public ProviderOutcome getAccepting(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            Set<Integer> acceptedStatuses,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "GET", path, "", path, headers, null, "application/json", acceptedStatuses, onSuccess);
    }

    /**
     * A bodyless DELETE.
     *
     * <p>Here because Click expresses two operations as DELETE that other partners
     * would have expressed as POST — {@code payment/reversal} and card-token
     * deletion — and a reversal is not something to reach for a second HTTP client
     * to perform.
     */
    public ProviderOutcome delete(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "DELETE", path, "", path, headers, null, onSuccess);
    }

    /**
     * A GET whose path carries personal data, logged under a fixed label instead.
     *
     * <p>Here because OSRM's route service takes both coordinates in the path
     * ({@code /route/v1/driving/lon,lat;lon,lat}) and has no POST form, and one of
     * the two is a customer's delivery point. {@link #get} logs the path when the
     * connection fails, which would put a precise location into the log aggregator
     * on every routing outage (ADR 0029: a precise location is as identifying as the
     * address beside it). The label is what a log line may say.
     *
     * @param operation a fixed, personal-data-free name for the call, such as
     *                  {@code osrm.route}
     */
    public ProviderOutcome getWithSensitivePath(
            ProviderCall call,
            String path,
            String operation,
            Map<String, String> headers,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        return exchange(call, "GET", path, "", operation, headers, null, onSuccess);
    }

    /**
     * A form-encoded POST, for the provider whose token endpoint takes {@code
     * application/x-www-form-urlencoded} and nothing else (Faktura.uz's OAuth password
     * grant, ADR 0096). The values are percent-encoded here; the body is never logged,
     * because a token request carries the account's password.
     */
    public ProviderOutcome postForm(
            ProviderCall call,
            String path,
            Map<String, String> headers,
            Map<String, String> form,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        String encoded = form.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return exchange(
                call,
                "POST",
                path,
                "",
                path,
                headers,
                encoded.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded",
                Set.of(),
                onSuccess);
    }

    private ProviderOutcome exchange(
            ProviderCall call,
            String method,
            String path,
            String query,
            String logLabel,
            Map<String, String> headers,
            @Nullable Object body,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {
        byte[] payload;
        try {
            payload = body == null ? null : objectMapper.writeValueAsBytes(body);
        } catch (RuntimeException failure) {
            return classifier.classify(failure, false);
        }
        return exchange(call, method, path, query, logLabel, headers, payload, "application/json", Set.of(), onSuccess);
    }

    private ProviderOutcome exchange(
            ProviderCall call,
            String method,
            String path,
            String query,
            String logLabel,
            Map<String, String> headers,
            byte @Nullable [] payload,
            String contentType,
            Set<Integer> acceptedStatuses,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {

        try {
            Duration deadline = call.timeout() == null ? Duration.ofSeconds(30) : call.timeout();

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(call.baseUrl() + path + query))
                    .timeout(deadline)
                    .header("Content-Type", contentType)
                    .header("Accept", "application/json")
                    .method(
                            method,
                            payload == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(payload));
            headers.forEach(request::header);

            BoundedBody collected = new BoundedBody();
            HttpResponse<Void> response = send(request.build(), collected, deadline);

            return handle(response, collected, acceptedStatuses, onSuccess);

        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return classifier.classify(failure, mayHaveReachedProvider(failure));
        } catch (IOException failure) {
            ProviderOutcome outcome = classifier.classify(failure, mayHaveReachedProvider(failure));
            // The credential is never in scope here, and the message logged is
            // the classifier's rather than the provider's body: provider errors
            // have been known to echo request content back.
            log.warn("Provider call {} {} failed: {} ({})", method, logLabel, outcome.status(), outcome.errorCode());
            return outcome;
        } catch (RuntimeException failure) {
            return classifier.classify(failure, mayHaveReachedProvider(failure));
        }
    }

    /**
     * Sends the request and waits at most {@code deadline} for the <em>whole</em>
     * exchange, body included.
     *
     * <p>{@link HttpRequest.Builder#timeout} does not do this on its own, and that
     * is the trap this method exists for: the JDK cancels that timer as soon as
     * the response headers arrive, so it bounds the headers and nothing after
     * them. A provider that answers 200 and then stops writing is precisely the
     * case it does not cover, and the read that follows is not on a background
     * worker — a {@code direct:} endpoint runs on the caller's thread, which for a
     * checkout is the Tomcat request thread. The request timeout is kept as well
     * because it still names a connect-phase failure precisely, which is what
     * {@link #mayHaveReachedProvider} classifies on.
     *
     * <p>The collecting handler matters for the same reason. {@code ofInputStream}
     * would hand back a stream to be read after this method returns, outside any
     * deadline and with a close every failure path would have to remember.
     */
    private HttpResponse<Void> send(HttpRequest request, BoundedBody collected, Duration deadline)
            throws IOException, InterruptedException {

        CompletableFuture<HttpResponse<Void>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(collected));
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            // Cancelled rather than abandoned, so the connection is released
            // instead of being left filling a buffer nobody will read.
            pending.cancel(true);
            throw new HttpTimeoutException("No complete response within " + deadline);
        } catch (ExecutionException failed) {
            throw unwrap(failed.getCause());
        }
    }

    /**
     * Rethrows the cause as itself.
     *
     * <p>Never wrapped: the classification below turns on the exception type, and
     * a {@code HttpConnectTimeoutException} hidden inside an
     * {@code ExecutionException} reads as a lost reply — which is the difference
     * between a safe retry and a second charge.
     */
    private static IOException unwrap(@Nullable Throwable cause) throws IOException {
        switch (cause) {
            case IOException io -> throw io;
            case RuntimeException runtime -> throw runtime;
            case null -> throw new IOException("The provider call failed without a cause");
            default -> throw new IOException(cause);
        }
    }

    /**
     * Whether the provider might already have acted on this request.
     *
     * <p>This cannot be tracked with a flag set around {@code send()}: connect,
     * write, and read all happen inside that one call, so a flag set afterwards
     * reports "not sent" for a response that was merely lost on the way back.
     * The exception type is the only evidence available.
     *
     * <p>The default is <em>yes</em>. Only connect-phase failures prove nothing
     * reached the provider; for anything else, assuming it did costs a
     * reconciliation query, while assuming it did not costs a second courier.
     */
    private static boolean mayHaveReachedProvider(Throwable failure) {
        // HttpConnectTimeoutException is a subtype of HttpTimeoutException, so it
        // must be tested first — otherwise every connect timeout reads as a read
        // timeout and turns a safe retry into a manual reconciliation.
        return !(failure instanceof HttpConnectTimeoutException
                || failure instanceof ConnectException
                || failure instanceof UnknownHostException);
    }

    /**
     * Turns a response into an outcome.
     *
     * <p>Both the JSON parse and the adapter's {@code onSuccess} mapping run here
     * on purpose: they happen after the provider has answered, so a failure in
     * either is classified as uncertain by the caller's catch rather than as a
     * retryable transport fault. On a partner whose create is immediately live,
     * that difference is a second courier.
     */
    private ProviderOutcome handle(
            HttpResponse<Void> response,
            BoundedBody body,
            Set<Integer> acceptedStatuses,
            Function<Map<String, Object>, ProviderOutcome> onSuccess) {

        int status = response.statusCode();
        byte[] raw = body.bytes();

        if (acceptedStatuses.contains(status)) {
            // A status the caller asked to hear as an ANSWER rather than a failure -- "no such
            // document" to a lookup is the answer, not a fault -- handed over with the status
            // under STATUS_KEY and whatever body could be read. The body may well not be JSON.
            Map<String, Object> answer = new java.util.LinkedHashMap<>();
            try {
                if (raw.length > 0 && !body.truncated()) {
                    answer.putAll(parse(raw));
                }
            } catch (RuntimeException notJson) {
                // Not worth failing an answer over: the status is the answer.
            }
            answer.put(STATUS_KEY, status);
            return onSuccess.apply(answer);
        }

        if (status >= 200 && status < 300) {
            if (body.truncated()) {
                // Not parsed, deliberately. Half a JSON document that happens to
                // parse is a worse answer than an uncertain one, and no provider
                // on these routes reports a created invoice or a booked shipment
                // in more than MAX_RESPONSE_BYTES. The caller's catch turns this
                // into RESPONSE_UNREADABLE, which is what it is: the provider
                // answered and we cannot say what it said.
                throw new IllegalStateException("Provider response exceeded " + MAX_RESPONSE_BYTES + " bytes");
            }
            return onSuccess.apply(raw.length == 0 ? Map.of() : parse(raw));
        }

        // Delegated rather than decided here. This method used to carry its own
        // copy of the status rules, and the copies drifted: 408 and 425 were
        // uncertain in the classifier and retryable here, and this — the wired-in
        // path — was the unsafe one. One implementation cannot disagree with
        // itself.
        return classifier.classifyFailureStatus(
                status,
                describeFailure(raw, body.truncated()),
                retryAfter(response).orElse(null));
    }

    /**
     * A JSON object as a map. Anything else -- an array, a scalar, HTML -- is unreadable, and for a
     * call that may have acted that is {@code UNCERTAIN}: an adapter that expects an object and
     * is answered with an array has not been told what happened (see Noor's unreadable-200 test).
     */
    private Map<String, Object> parse(byte[] raw) {
        return objectMapper.readValue(raw, MAP_TYPE);
    }

    /**
     * Reduces a provider's error body to the part an operator can act on.
     *
     * <p>What is dropped is the request coming back. Nested objects and arrays go
     * first and unconditionally: a partner reporting field-level validation
     * errors returns them keyed by the field that failed with the offending
     * value beside it, and that value is a dropoff address. What survives is a
     * bounded set of top-level scalars, each capped and scrubbed of the shapes
     * that carry a person — long digit runs and e-mail addresses.
     *
     * <p>Not a guarantee, and not claimed as one: a provider is free to write a
     * customer's street into a free-text {@code message}, and no generic rule
     * distinguishes that from "Client is disabled", which downstream classifiers
     * read. Where a partner's error vocabulary is actually known, its adapter is
     * the place to narrow this further.
     *
     * <p>When nothing recognisable is there, the field <em>names</em> are
     * reported instead. They are the provider's schema rather than anyone's data,
     * and they are what tells an operator which key to add here.
     */
    private String describeFailure(byte[] raw, boolean truncated) {
        String suffix = truncated ? " (body truncated)" : "";
        if (raw.length == 0) {
            return "no response body" + suffix;
        }

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(raw, MAP_TYPE);
        } catch (JacksonException notAJsonObject) {
            return "%d byte body, not a JSON object%s".formatted(raw.length, suffix);
        }

        String summary = DIAGNOSTIC_FIELDS.stream()
                .filter(parsed::containsKey)
                .filter(field -> isScalar(parsed.get(field)))
                .map(field -> field + "=" + scrub(field, String.valueOf(parsed.get(field))))
                .collect(Collectors.joining(", "));

        if (summary.isEmpty()) {
            return "%d byte body with no recognised error field; fields: %s%s"
                    .formatted(raw.length, fieldNames(parsed), suffix);
        }
        return truncate(summary, MAX_DETAIL_CHARS) + suffix;
    }

    private static boolean isScalar(@Nullable Object value) {
        return value != null && !(value instanceof Map) && !(value instanceof Collection);
    }

    private static String scrub(String field, String value) {
        String bounded = truncate(value, MAX_FIELD_CHARS);
        if (OPAQUE_HANDLES.contains(field)) {
            return bounded;
        }
        return EMAIL.matcher(DIGIT_RUN.matcher(bounded).replaceAll("[redacted]"))
                .replaceAll("[redacted]");
    }

    /** Field names only, and only ones shaped like names rather than like data. */
    private static String fieldNames(Map<String, Object> parsed) {
        String names = parsed.keySet().stream()
                .filter(name -> FIELD_NAME.matcher(name).matches())
                .limit(20)
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "none" : names;
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    /**
     * Accumulates a response body up to {@link #MAX_RESPONSE_BYTES} and remembers
     * whether there was more.
     *
     * <p>Bytes past the cap are dropped rather than the connection being torn
     * down, because the cap is a memory bound and not a protocol opinion; the
     * request deadline is what bounds the time. Unsynchronised on purpose: the
     * chunks arrive one at a time, and the caller only reads this after the
     * response future has completed, which is the barrier.
     */
    private static final class BoundedBody implements Consumer<Optional<byte[]>> {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        @Override
        public void accept(Optional<byte[]> chunk) {
            chunk.ifPresent(bytes -> {
                int room = MAX_RESPONSE_BYTES - buffer.size();
                if (bytes.length > room) {
                    truncated = true;
                }
                if (room > 0) {
                    buffer.write(bytes, 0, Math.min(bytes.length, room));
                }
            });
        }

        byte[] bytes() {
            return buffer.toByteArray();
        }

        boolean truncated() {
            return truncated;
        }
    }

    private Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
            } catch (NumberFormatException ignored) {
                // Retry-After also allows an HTTP date. Rather than parse it, fall
                // back to the caller's default: a slightly wrong backoff is fine,
                // a crash on a rate-limit response is not.
                return Optional.empty();
            }
        });
    }
}
