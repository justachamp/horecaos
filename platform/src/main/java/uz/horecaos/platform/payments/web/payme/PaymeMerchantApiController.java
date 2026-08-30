package uz.horecaos.platform.payments.web.payme;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.domain.CallbackKind;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeCredentials;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeErrors;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeMerchantApi;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeRpcException;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcProviderCallbackStore;

/**
 * The single endpoint Payme calls (ADR 0013).
 *
 * <p>One URL, every method in the body, never in the path. The binding segment
 * selects the cashbox, because the Basic credential is per cashbox and one shared
 * URL therefore could not tell HorecaOS which key to check a request against.
 *
 * <p>The order of operations is the interesting part and every step of it is
 * forced by something:
 *
 * <ol>
 * <li><strong>Resolve the binding.</strong> An unknown segment answers
 * {@code -32504}, the same answer a wrong key gets. Distinguishing them would turn
 * this endpoint into an oracle for which cashboxes exist, and there is no tenant to
 * record the arrival against anyway.</li>
 * <li><strong>Refuse a non-POST</strong> with {@code -32300}.</li>
 * <li><strong>Read the id out of the body, leniently.</strong> Not to act on it —
 * only to have something to echo. Payme's first sandbox test is a bad credential
 * and the response it expects carries the request's own id, which cannot be known
 * without looking at the body.</li>
 * <li><strong>Authenticate, before anything is reported about the body.</strong> A
 * caller who cannot authenticate learns only {@code -32504}: not whether the JSON
 * parsed, not whether the method exists, not whether the order does. This is also
 * the order Payme's own PHP template uses, and it applies to every method
 * including {@code GetStatement}.</li>
 * <li><strong>Then report a parse failure</strong> ({@code -32700}) or a
 * structurally wrong envelope ({@code -32600}), and only then dispatch.</li>
 * </ol>
 *
 * <p>Nothing throws out of here. The response is always HTTP 200 and always a
 * JSON-RPC body, including for a fault nobody predicted, which is answered
 * {@code -32400} — the code Payme would have synthesised from a 500 anyway, but
 * delivered immediately and with the request id attached.
 */
@RestController
@RequestMapping(PaymeMerchantApiController.BASE)
public class PaymeMerchantApiController {

    private static final Logger log = LoggerFactory.getLogger(PaymeMerchantApiController.class);

    /**
     * The ceiling on an inbound Payme body. Generous by two orders of magnitude
     * for the seven documented methods, and small enough that the public endpoint
     * cannot be used to make this process allocate.
     */
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    /**
     * The provider-callback root, which is load-bearing in two places rather than
     * cosmetic.
     *
     * <p>It is what the endpoint-authorization test recognises as a machine-called
     * path and exempts from ADR 0025's capability declaration: there is no actor here
     * to hold a capability, because Payme's Basic credential belongs to a cashbox
     * rather than to a person. And it keeps the security exemption outside
     * {@code /api/v1}, where a careless wildcard could otherwise exempt part of the
     * authenticated API.
     */
    static final String BASE = "/providers/payme";

    /**
     * The pattern the filter chain exempts, kept beside the mapping so the two
     * cannot drift.
     *
     * <p>A mapping the security configuration did not match would be answered by the
     * platform's bearer-token chain with a bodyless 401 — the exact failure this
     * whole arrangement exists to avoid, and one that would be discovered in a Payme
     * sandbox rather than in a build.
     */
    static final String PATH_PATTERN = BASE + "/*";

    private static final String PATH = "/{binding}";

    /** The success entry in the callback ledger. Payme has no success code of its own. */
    private static final String OK = "0";

    /** The ledger's {@code provider_reference} when the body named nothing at all. */
    private static final String UNIDENTIFIED = "unidentified";

    private static final int REFERENCE_LIMIT = 128;

    private final PaymentBindingResolver bindings;
    private final PaymeCredentials credentials;
    private final PaymeMerchantApi merchantApi;
    private final JdbcProviderCallbackStore callbacks;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymeMerchantApiController(
            PaymentBindingResolver bindings,
            PaymeCredentials credentials,
            PaymeMerchantApi merchantApi,
            JdbcProviderCallbackStore callbacks,
            ObjectMapper objectMapper,
            Clock clock) {
        this.bindings = bindings;
        this.credentials = credentials;
        this.merchantApi = merchantApi;
        this.callbacks = callbacks;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Mapped for every HTTP method on purpose.
     *
     * <p>A {@code @PostMapping} would answer a GET with 405, and Payme reads any
     * status other than 200 as {@code -32400}. {@code -32300} exists precisely for
     * a non-POST arrival, and returning it needs a mapping that receives one.
     */
    @RequestMapping(PATH)
    public ResponseEntity<Map<String, Object>> handle(
            @PathVariable("binding") String segment, HttpServletRequest request) {

        Arrival arrival = new Arrival();
        Map<String, Object> envelope;

        try {
            // Two statements rather than one nested call, because the request id is
            // discovered by `run` and Java evaluates arguments left to right: an
            // inlined call would echo the id as it was before the body was read,
            // which is to say always null.
            Map<String, Object> result = run(segment, request, arrival);
            envelope = PaymeJsonRpc.success(arrival.requestId, result);
            arrival.responseCode = OK;
        } catch (PaymeRpcException answered) {
            envelope = PaymeJsonRpc.error(arrival.requestId, answered);
            arrival.responseCode = Integer.toString(answered.code());
        } catch (RuntimeException | IOException unexpected) {
            // The catch-all, and it is load-bearing rather than defensive
            // programming. Letting this escape would produce an HTTP 500, which
            // Payme reads as -32400 — the same code, arrived at slowly, without the
            // request id, and wrapped in an error page Payme cannot parse.
            log.error("A Payme request failed unexpectedly; answering -32400.", unexpected);
            PaymeRpcException internal = PaymeErrors.internalError();
            envelope = PaymeJsonRpc.error(arrival.requestId, internal);
            arrival.responseCode = Integer.toString(internal.code());
        }

        record(arrival);
        // HTTP 200, always, with the content type set rather than negotiated.
        // Setting it explicitly takes the response out of content negotiation,
        // which would otherwise answer 406 to a request whose Accept header does
        // not name JSON — and Payme does not document what it sends.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(envelope);
    }

    /**
     * Everything that may fail, so that the caller above has exactly one place to
     * turn a failure into a body.
     */
    private Map<String, Object> run(String segment, HttpServletRequest request, Arrival arrival) throws IOException {

        arrival.binding = bindings.byCallbackSegment(segment)
                .filter(candidate -> candidate.providerType() == PaymentProviderType.PAYME)
                .orElse(null);
        if (arrival.binding == null) {
            // No tenant, so nothing is recorded and nothing is revealed. A probe of
            // a guessed segment gets the same sentence a wrong key gets.
            log.warn("A Payme request arrived on an unknown callback segment.");
            throw PaymeErrors.insufficientPrivilege();
        }

        if (!HttpMethod.POST.matches(request.getMethod())) {
            throw PaymeErrors.methodNotPost();
        }

        // Read from the servlet stream rather than through a message converter. The
        // documented request carries `Content-Type: text/json` and Payme's own PHP
        // template's test call sends `application/json`; a converter bound to either
        // answers 415 to the other, and a 415 is a -32400 as far as Payme is
        // concerned.
        //
        // Bounded, and read before authenticating on purpose. The order is forced:
        // a JSON-RPC error response has to echo the request id, so -32504 cannot be
        // answered without parsing the body it is refusing. That makes the read
        // itself reachable by anyone who finds the callback segment, so the cap is
        // what stops an unauthenticated stranger asking this process to buffer an
        // arbitrary number of bytes. A real Payme request is a few hundred.
        arrival.body = readBounded(request);

        JsonNode root = null;
        JacksonException unparseable = null;
        try {
            root = objectMapper.readTree(arrival.body);
        } catch (JacksonException failure) {
            // Held, not thrown. The authentication check comes first and its answer
            // must not depend on whether the body was well formed.
            unparseable = failure;
        }

        if (root != null && root.isObject()) {
            arrival.requestId = requestIdOf(root);
            arrival.method =
                    root.path("method").isString() ? root.path("method").asString() : null;
            arrival.providerReference = providerReferenceOf(root, arrival.requestId);
        }

        credentials.authenticate(arrival.binding, request.getHeader(HttpHeaders.AUTHORIZATION));
        arrival.authenticated = true;

        if (unparseable != null) {
            // -32700 is the documented code for a body that will not parse. Payme's
            // PHP template answers -32600 here, which is the code for a body that
            // parsed and is structurally wrong; the docs distinguish the two and so
            // does this.
            throw PaymeErrors.parseError();
        }
        if (root == null || !root.isObject()) {
            throw PaymeErrors.invalidRequest("the request must be a JSON object");
        }
        if (arrival.method == null || arrival.method.isBlank()) {
            throw PaymeErrors.invalidRequest("method must be a non-empty string");
        }
        if (!root.path("params").isObject()) {
            // Named parameters only: the protocol page states that `params` is
            // always an object and never an array. `jsonrpc` is deliberately not
            // required — the docs' own request table omits it and their worked
            // example does not carry it, so demanding it would reject genuine
            // Payme traffic.
            throw PaymeErrors.invalidRequest("params must be an object");
        }

        return merchantApi.dispatch(arrival.binding, arrival.method, root.path("params"));
    }

    // -----------------------------------------------------------------------
    // The inbox
    // -----------------------------------------------------------------------

    /**
     * Records the arrival, and never fails the response because of it.
     *
     * <p>The ledger exists so that an argument about what Payme sent and what HorecaOS
     * answered can be settled — both providers surface these codes in their own
     * support tooling — and so that a burst of {@code -32504} against one binding is
     * visible as the security signal it is. But it is subordinate to answering:
     * Payme repeats a call whose response was lost, so losing a response because a
     * bookkeeping insert failed would turn a write failure into a retry storm.
     *
     * <p><strong>Nothing of the body is stored but its hash</strong> (ADR 0029). A
     * Payme request carries the amount, the timestamps, Payme's transaction id, and
     * an {@code account} object which in HorecaOS's schema is a single opaque order
     * reference — no name, no phone, no card, no address. There is therefore no
     * personal data here to protect, and the right handling of a payload that needs
     * no protection is not to keep a second copy of it. The hash is enough to prove
     * that two arrivals differed, which is all the deduplication key needs.
     */
    private void record(Arrival arrival) {
        if (arrival.binding == null) {
            return;
        }
        try {
            CallbackKind kind = "SetFiscalData".equals(arrival.method)
                    ? CallbackKind.PAYME_SET_FISCAL_DATA
                    : CallbackKind.PAYME_RPC;

            callbacks.record(
                    UUID.randomUUID(),
                    arrival.binding.tenantId(),
                    PaymentProviderType.PAYME,
                    arrival.binding.bindingId(),
                    kind,
                    truncate(arrival.reference(), REFERENCE_LIMIT),
                    sha256(arrival.body),
                    // Payme's Basic credential is the analogue of Click's signature:
                    // it is the whole of the authentication, and a run of failures
                    // on one binding is a missed rotation or somebody probing.
                    arrival.authenticated,
                    null,
                    arrival.responseCode,
                    clock.instant(),
                    null,
                    null);
        } catch (RuntimeException failure) {
            log.warn(
                    "A Payme arrival on {} could not be recorded; the response is unaffected.",
                    arrival.binding,
                    failure);
        }
    }

    // -----------------------------------------------------------------------
    // Reading the request
    // -----------------------------------------------------------------------

    /**
     * The request id, echoed verbatim and never interpreted.
     *
     * <p>Documented as an Integer, so a number is the expected case; a string is
     * accepted and echoed as a string rather than refused, because refusing it would
     * cost a real payment over a field that only has to come back the way it went
     * out.
     */
    /**
     * At most {@link #MAX_REQUEST_BYTES}, refused rather than truncated.
     *
     * <p>Truncating would hand the parser a prefix, and a prefix of a well-formed
     * object is itself well formed often enough to be dangerous. A body over the
     * cap is refused outright and never parsed.
     */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        // Content-Length is a claim, not a fact, so it serves only as a cheap early
        // refusal. The real limit is enforced against what actually arrives.
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            throw PaymeErrors.parseError();
        }

        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            log.warn("A Payme request exceeded {} bytes and was refused unread.", MAX_REQUEST_BYTES);
            throw PaymeErrors.parseError();
        }
        return body;
    }

    private static Object requestIdOf(JsonNode root) {
        JsonNode id = root.path("id");
        if (id.isIntegralNumber()) {
            return id.longValue();
        }
        if (id.isString()) {
            return id.asString();
        }
        return null;
    }

    /**
     * What this arrival is about, for the ledger.
     *
     * <p>Payme's {@code params.id} when the call carries a transaction, and the
     * JSON-RPC request id otherwise — {@code CheckPerformTransaction} and
     * {@code GetStatement} have no transaction of their own.
     */
    private static String providerReferenceOf(JsonNode root, Object requestId) {
        JsonNode id = root.path("params").path("id");
        if (id.isString() && !id.asString().isBlank()) {
            return id.asString();
        }
        return requestId == null ? null : requestId.toString();
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * What is known about one arrival as it is being handled.
     *
     * <p>Mutable, and local to a single request. It exists so that the handler has
     * one exit point that both answers Payme and records what was answered: the
     * alternative is threading six values through every early return, which is how a
     * branch ends up answering correctly and recording nothing.
     */
    private static final class Arrival {

        private ProviderBinding binding;
        private byte[] body = new byte[0];
        private Object requestId;
        private String method;
        private String providerReference;
        private boolean authenticated;
        private String responseCode = OK;

        private String reference() {
            if (providerReference != null && !providerReference.isBlank()) {
                return providerReference;
            }
            return requestId == null ? UNIDENTIFIED : requestId.toString();
        }
    }
}
