package uz.horecaos.platform.payments.infrastructure.click.fake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for Click's MERCHANT API HTTP boundary (ADR 0007, ADR 0013).
 *
 * <p><strong>Fidelity choice, and why.</strong> {@code PaymentCheckoutSurfaceTests}'
 * {@code RecordingTransport} fakes the port {@code MerchantApiTransport} declares —
 * it proves {@code ClickPaymentAdapter}/{@code ClickMerchantApi} build the right
 * request, but nothing from {@code CamelMerchantApiTransport} outward ever runs:
 * not the {@code payment.merchant-api} Camel route, not {@code PaymentGateway}'s
 * installation lookup and ADR 0028 secret resolution, not {@code ProviderHttpClient}'s
 * timeout and classification behaviour. ADR 0007's own {@code ControlledFakeProvider}
 * is closer in spirit — a local HTTP double reached through the real transport — but
 * it is wired to a separate, generic {@code testprovider} Camel route built to prove
 * the inbox/outbox foundation, not to the payment route real checkouts use, and it
 * knows nothing of Click's wire format. This class takes ADR 0007's HTTP-boundary
 * idea and applies it at the boundary payments actually calls: it answers on a real
 * socket, at the URL an {@code integration.provider_environments.base_url} row
 * already points {@link uz.horecaos.platform.integration.camel.common.ProviderHttpClient}
 * at, so a checkout run under the {@code local} profile exercises
 * {@code ClickPaymentAdapter}, {@code ClickFiscalAdapter}, {@code ClickMerchantApi},
 * the Camel route, {@code PaymentGateway}, and ADR 0028 secret resolution exactly as
 * they run in production — only the socket on the other end is this class instead of
 * {@code api.click.uz}. What it does not exercise is Click itself: no error-code
 * enumeration, no undocumented behaviour, nothing this class's author does not
 * already know from reading {@code ClickMerchantApi} and the fiscalization notes.
 * That is the trade the whole exercise makes, not a gap specific to this class.
 *
 * <p><strong>Deliberately framework-free.</strong> No Spring annotation appears on
 * this class so that a test can {@code new} one directly, exactly the way
 * {@code PaymentCheckoutSurfaceTests} constructs {@code ClickMerchantApi} and
 * {@code ClickPaymentAdapter} by hand. {@link FakeClickProviderConfiguration} is the
 * separate, {@code local}-profile-only glue that starts one as part of {@code make
 * run}.
 *
 * <p><strong>Scenario selection carries no switch in production code</strong>, per
 * ADR 0007's rule for {@code ControlledFakeProvider}. A scenario is selected by the
 * Click {@code service_id} a request names — {@code merchant_account_reference} on
 * whichever {@code payments.merchant_bindings} row a test points at this server —
 * and {@code service_id} is ordinary fixture data a test already controls. No
 * production adapter, route, or gateway class has to know a scenario concept exists.
 *
 * <p>State is in memory and per-instance; nothing here is tenant-scoped because
 * nothing about a fake Click sandbox needs to be — nobody's tenant data reaches it.
 */
public final class FakeClickHttpProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeClickHttpProvider.class);

    /** Every scenario service_id starts with this, so a fixture reads as one at a glance. */
    public static final String SCENARIO_PREFIX = "fake-scenario-";

    /** Never responds within any caller's deadline; the connection is eventually dropped unanswered. */
    public static final String SCENARIO_TIMEOUT = SCENARIO_PREFIX + "timeout";

    /** Answers HTTP 500 with a small JSON body, on every call. */
    public static final String SCENARIO_HTTP_500 = SCENARIO_PREFIX + "http-500";

    /**
     * Performs the mutating side effect — an invoice is created, items are
     * submitted — and then drops the connection before any response is written.
     * The caller sees exactly what a lost reply looks like: the provider acted and
     * the answer never arrived, which {@link
     * uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier}
     * reads as {@code UNCERTAIN} on a mutating call and never as a licence to retry.
     */
    public static final String SCENARIO_ACCEPTED_THEN_LOST = SCENARIO_PREFIX + "accepted-then-lost";

    private static final DateTimeFormatter OFD_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Uzbekistan has no daylight-saving offset change; fixed, like {@code ClickReceiptUrl}'s own. */
    private static final ZoneOffset TASHKENT = ZoneOffset.ofHours(5);

    /**
     * How long the {@link #SCENARIO_TIMEOUT} handler blocks before giving up and
     * dropping the connection unanswered.
     *
     * <p>Comfortably longer than every deadline this platform imposes on a Click
     * call today — {@code ClickMerchantApi}'s own 30 seconds, {@code
     * PaymentGateway}'s 20-second default — so a caller with any of those deadlines
     * always experiences its own timeout first and this bound is only a safety
     * valve against a handler thread parked forever.
     */
    private static final Duration TIMEOUT_SCENARIO_DELAY = Duration.ofSeconds(45);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;
    private final @Nullable String expectedSecret;

    private final Map<String, PaymentRecord> paymentsById = new ConcurrentHashMap<>();
    private final Map<String, String> paymentIdByMerchantTransId = new ConcurrentHashMap<>();
    private final Map<String, String> ofdUrlByPaymentId = new ConcurrentHashMap<>();
    private final Map<String, Long> invoiceIdByMerchantTransId = new ConcurrentHashMap<>();

    private final AtomicLong paymentIdSequence = new AtomicLong(700_000_001);
    private final AtomicLong invoiceIdSequence = new AtomicLong(400_000_001);
    private final AtomicLong receiptNumberSequence = new AtomicLong(1);

    private @Nullable HttpServer server;

    public FakeClickHttpProvider() {
        this(Clock.systemUTC(), null);
    }

    /**
     * @param expectedSecret the secret every {@code Auth} header is checked against.
     *                       {@code null} skips verification, for a caller that only
     *                       needs the wire shape and not the credential path.
     */
    public FakeClickHttpProvider(Clock clock, @Nullable String expectedSecret) {
        this.clock = clock;
        this.expectedSecret = expectedSecret;
    }

    /** Starts on an ephemeral port and returns it. */
    public synchronized int start() {
        return start(0);
    }

    public synchronized int start(int port) {
        if (server != null) {
            throw new IllegalStateException(
                    "Already started on port " + server.getAddress().getPort());
        }
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not bind the fake Click provider", failure);
        }
        // Daemon threads: a scenario that never answers must never be the reason a
        // test JVM, or `make run`, fails to exit.
        server.setExecutor(Executors.newCachedThreadPool(daemonThreadFactory()));
        server.createContext("/", this::dispatch);
        server.start();
        log.info(
                "Fake Click provider listening on http://localhost:{}",
                server.getAddress().getPort());
        return server.getAddress().getPort();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("Not started");
        }
        return server.getAddress().getPort();
    }

    /**
     * Tells the fake that a payment for {@code merchantTransId} has been captured,
     * outside any call this class's own HTTP surface received.
     *
     * <p>The seam a fake customer-pay tool uses. Click's real inbound SHOP API
     * callback — {@code prepare}/{@code complete} — is a completely different
     * surface from the outbound MERCHANT API this class answers, and HorecaOS's own
     * webhook controllers never learn a {@code payment_id} from it (see {@code
     * ClickCallbackProcessor}). A real Click customer payment is what mints one; a
     * fake customer payment must mint one the same way, or {@code
     * status_by_mti} — and everything fiscalization resolves through it — has
     * nothing to find.
     *
     * @return the synthetic {@code payment_id}, so the caller can hand it back to
     *         whoever is watching the dev loop
     */
    public String registerCapturedPayment(String serviceId, String merchantTransId, long amountSom) {
        String paymentId = Long.toString(paymentIdSequence.getAndIncrement());
        paymentsById.put(paymentId, new PaymentRecord(paymentId, merchantTransId, serviceId, amountSom, false));
        paymentIdByMerchantTransId.put(merchantTransId, paymentId);
        return paymentId;
    }

    /** Test-only window onto what {@code submit_items}/{@code submit_qrcode} recorded. */
    @Nullable String readOfdUrl(String paymentId) {
        return ofdUrlByPaymentId.get(paymentId);
    }

    // ------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException unexpected) {
            log.warn(
                    "Fake Click provider failed to answer {} {}",
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    unexpected);
            respondJson(exchange, 500, Map.of("error_code", -1, "error_note", "fake provider fault"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        List<String> segments = segments(exchange.getRequestURI().getPath());

        if (!authorized(exchange)) {
            respondJson(exchange, 401, Map.of("error_note", "invalid Auth header"));
            return;
        }

        if ("POST".equals(method) && segments.equals(List.of("invoice", "create"))) {
            invoiceCreate(exchange);
            return;
        }
        if ("GET".equals(method)
                && segments.size() == 5
                && segments.subList(0, 2).equals(List.of("payment", "status_by_mti"))) {
            statusByMerchantTransId(exchange, segments.get(2), segments.get(3));
            return;
        }
        if ("GET".equals(method)
                && segments.size() == 4
                && segments.subList(0, 2).equals(List.of("payment", "status"))) {
            paymentStatus(exchange, segments.get(2), segments.get(3));
            return;
        }
        if ("DELETE".equals(method)
                && segments.size() == 4
                && segments.subList(0, 2).equals(List.of("payment", "reversal"))) {
            reversal(exchange, segments.get(2), segments.get(3));
            return;
        }
        if ("POST".equals(method) && segments.equals(List.of("payment", "ofd_data", "submit_items"))) {
            submitItems(exchange);
            return;
        }
        if ("POST".equals(method) && segments.equals(List.of("payment", "ofd_data", "submit_qrcode"))) {
            submitQrCode(exchange);
            return;
        }
        if ("GET".equals(method)
                && segments.size() == 4
                && segments.subList(0, 2).equals(List.of("payment", "ofd_data"))) {
            ofdData(exchange, segments.get(2), segments.get(3));
            return;
        }
        respondJson(
                exchange, 404, Map.of("error_code", -8, "error_note", "no such endpoint on the fake Click provider"));
    }

    // ------------------------------------------------------------- endpoints

    private void invoiceCreate(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String serviceId = String.valueOf(body.get("service_id"));
        String merchantTransId = String.valueOf(body.get("merchant_trans_id"));
        Scenario scenario = scenarioFor(serviceId);

        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }

        long invoiceId = invoiceIdSequence.getAndIncrement();
        invoiceIdByMerchantTransId.put(merchantTransId, invoiceId);

        if (dropIfNeeded(exchange, scenario)) {
            return;
        }
        respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success", "invoice_id", invoiceId));
    }

    private void statusByMerchantTransId(HttpExchange exchange, String serviceId, String merchantTransId)
            throws IOException {
        merchantTransId = decode(merchantTransId);
        Scenario scenario = scenarioFor(serviceId);
        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }
        if (dropIfNeeded(exchange, scenario)) {
            return;
        }

        PaymentRecord record = forMerchantTransId(serviceId, merchantTransId);
        if (record == null) {
            // Click's own worked example for "no payment yet" carries no
            // payment_id and no non-zero error_code either; absence is the answer.
            respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success"));
            return;
        }
        respondJson(
                exchange,
                200,
                orderedMap("error_code", 0, "error_note", "Success", "payment_id", Long.parseLong(record.paymentId)));
    }

    private void paymentStatus(HttpExchange exchange, String serviceId, String paymentId) throws IOException {
        paymentId = decode(paymentId);
        Scenario scenario = scenarioFor(serviceId);
        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }
        if (dropIfNeeded(exchange, scenario)) {
            return;
        }

        PaymentRecord record = paymentsById.get(paymentId);
        if (record == null || !record.serviceId.equals(serviceId)) {
            respondJson(exchange, 200, orderedMap("error_code", -5, "error_note", "USER_DOES_NOT_EXIST"));
            return;
        }
        // 2 = successfully paid, unless this same fake reversed it.
        int paymentStatus = record.reversed ? -1 : 2;
        respondJson(
                exchange,
                200,
                orderedMap(
                        "error_code",
                        0,
                        "error_note",
                        "Success",
                        "payment_id",
                        Long.parseLong(record.paymentId),
                        "payment_status",
                        paymentStatus,
                        "payment_status_date",
                        OFD_TIME.format(clock.instant().atZone(TASHKENT))));
    }

    private void reversal(HttpExchange exchange, String serviceId, String paymentId) throws IOException {
        String finalPaymentId = decode(paymentId);
        Scenario scenario = scenarioFor(serviceId);
        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }

        PaymentRecord record = paymentsById.get(finalPaymentId);
        if (record != null) {
            paymentsById.put(finalPaymentId, record.reversedCopy());
        }

        if (dropIfNeeded(exchange, scenario)) {
            return;
        }
        if (record == null || !record.serviceId.equals(serviceId)) {
            respondJson(exchange, 200, orderedMap("error_code", -5, "error_note", "USER_DOES_NOT_EXIST"));
            return;
        }
        respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success"));
    }

    private void submitItems(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String serviceId = String.valueOf(body.get("service_id"));
        String paymentId = String.valueOf(body.get("payment_id"));
        Scenario scenario = scenarioFor(serviceId);

        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }

        PaymentRecord record = paymentsById.get(paymentId);
        boolean known = record != null && record.serviceId.equals(serviceId);
        if (known) {
            registerOfdReceipt(serviceId, paymentId);
        }

        if (dropIfNeeded(exchange, scenario)) {
            return;
        }
        if (!known) {
            respondJson(exchange, 200, orderedMap("error_code", -5, "error_note", "USER_DOES_NOT_EXIST"));
            return;
        }
        respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success"));
    }

    private void submitQrCode(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String serviceId = String.valueOf(body.get("service_id"));
        String paymentId = String.valueOf(body.get("payment_id"));
        String qrcode = String.valueOf(body.get("qrcode"));
        Scenario scenario = scenarioFor(serviceId);

        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }

        ofdUrlByPaymentId.put(paymentId, qrcode);

        if (dropIfNeeded(exchange, scenario)) {
            return;
        }
        respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success"));
    }

    private void ofdData(HttpExchange exchange, String serviceId, String paymentId) throws IOException {
        String finalPaymentId = decode(paymentId);
        Scenario scenario = scenarioFor(serviceId);
        if (scenario == Scenario.HTTP_500) {
            respondHttp500(exchange);
            return;
        }
        if (dropIfNeeded(exchange, scenario)) {
            return;
        }

        String qrCodeUrl = ofdUrlByPaymentId.get(finalPaymentId);
        if (qrCodeUrl == null) {
            // Eventually consistent on the real provider; here it is simply "not
            // submitted yet". No error_code at all, matching the documented shape
            // ClickReceiptUrl/ClickErrorCodes read as success-with-nothing-yet.
            respondJson(exchange, 200, Map.of());
            return;
        }
        respondJson(exchange, 200, orderedMap("paymentId", Long.parseLong(finalPaymentId), "qrCodeURL", qrCodeUrl));
    }

    private void registerOfdReceipt(String serviceId, String paymentId) {
        String terminal = "FAKE-TERMINAL-" + serviceId;
        long receiptNumber = receiptNumberSequence.getAndIncrement();
        String sign = "FAKESIGN" + HexFormat.of().toHexDigits(paymentId.hashCode());
        String timestamp = OFD_TIME.format(clock.instant().atZone(TASHKENT));
        String url = "https://ofd.soliq.uz/epi?t=%s&r=%d&c=%s&s=%s".formatted(terminal, receiptNumber, timestamp, sign);
        ofdUrlByPaymentId.put(paymentId, url);
    }

    private @Nullable PaymentRecord forMerchantTransId(String serviceId, String merchantTransId) {
        String paymentId = paymentIdByMerchantTransId.get(merchantTransId);
        if (paymentId == null) {
            return null;
        }
        PaymentRecord record = paymentsById.get(paymentId);
        return record != null && record.serviceId.equals(serviceId) ? record : null;
    }

    // ------------------------------------------------------------- scenarios

    private enum Scenario {
        NONE,
        HTTP_500,
        ACCEPTED_THEN_LOST,
        TIMEOUT
    }

    /**
     * {@code service_id}'s scenario, or {@link Scenario#NONE}.
     *
     * <p>Read once per call, before the state mutation each handler performs — the
     * mutation always happens except on {@link Scenario#HTTP_500}, so an
     * accepted-then-lost or timed-out caller finds real evidence that the fake
     * acted, which is the entire point of both scenarios.
     */
    private static Scenario scenarioFor(String serviceId) {
        if (serviceId == null || !serviceId.startsWith(SCENARIO_PREFIX)) {
            return Scenario.NONE;
        }
        return switch (serviceId) {
            case SCENARIO_HTTP_500 -> Scenario.HTTP_500;
            case SCENARIO_ACCEPTED_THEN_LOST -> Scenario.ACCEPTED_THEN_LOST;
            case SCENARIO_TIMEOUT -> Scenario.TIMEOUT;
            default -> Scenario.NONE;
        };
    }

    private void respondHttp500(HttpExchange exchange) throws IOException {
        respondJson(exchange, 500, Map.of("error_note", "fake provider: simulated 5xx"));
    }

    /**
     * Drops the connection for {@link Scenario#TIMEOUT}/{@link
     * Scenario#ACCEPTED_THEN_LOST}, after whatever state mutation the caller
     * already made.
     *
     * @return whether the exchange was answered (by not answering it)
     */
    private boolean dropIfNeeded(HttpExchange exchange, Scenario scenario) throws IOException {
        if (scenario == Scenario.TIMEOUT) {
            sleepPastEveryDeadline();
            dropConnection(exchange);
            return true;
        }
        if (scenario == Scenario.ACCEPTED_THEN_LOST) {
            dropConnection(exchange);
            return true;
        }
        return false;
    }

    private void sleepPastEveryDeadline() {
        try {
            Thread.sleep(TIMEOUT_SCENARIO_DELAY);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** No response is written; the client experiences exactly what a lost reply looks like. */
    private void dropConnection(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.close();
    }

    // ------------------------------------------------------------- wire helpers

    private boolean authorized(HttpExchange exchange) {
        if (expectedSecret == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Auth");
        if (header == null) {
            return false;
        }
        String[] parts = header.split(":", -1);
        if (parts.length != 3) {
            return false;
        }
        String timestamp = parts[2];
        String expected = sha1(timestamp + expectedSecret);
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                parts[1].toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha1(String input) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return Map.of();
        }
        return json.readValue(body, MAP_TYPE);
    }

    private void respondJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static List<String> segments(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return List.of(trimmed.split("/"));
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "fake-click-provider");
            thread.setDaemon(true);
            return thread;
        };
    }

    private record PaymentRecord(
            String paymentId, String merchantTransId, String serviceId, long amountSom, boolean reversed) {
        PaymentRecord reversedCopy() {
            return new PaymentRecord(paymentId, merchantTransId, serviceId, amountSom, true);
        }
    }
}
