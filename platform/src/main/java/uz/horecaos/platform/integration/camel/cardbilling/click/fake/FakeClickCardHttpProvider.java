package uz.horecaos.platform.integration.camel.cardbilling.click.fake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for Click's card-token calls at the HTTP boundary (ADR 0007, ADR 0095), reached
 * through the real card-billing route at the URL an approved environment names, so the adapter, the Camel
 * route, the egress check and the secret resolution all run as they will in production. Only the socket on
 * the other end is this class instead of {@code api.click.uz}.
 *
 * <p>It answers the shapes {@code docs/providers/click-card-token.md} records from Click's published
 * documentation, and <strong>only those</strong>. Where the documentation is silent it says so rather than
 * invent a Click: every {@code error_code} other than {@code 0} below is a placeholder labelled as one in
 * its {@code error_note}, because the real table is not published.
 *
 * <p><strong>It does not deduplicate.</strong> Click documents no idempotency key on {@code
 * card_token/payment}, so a second call with the same {@code transaction_parameter} here is a second
 * payment, and {@link #paidPayments()} counts it. That is the point: the double charge the port's contract
 * forbids is something this fake can <em>show</em>, so a test can prove the adapter prevents it rather than
 * relying on the fake to.
 *
 * <p>The card number arrives here, from the test acting as the cardholder's browser, exactly as it does at
 * Click; the card number never goes to HorecaOS and the fake keeps only its last four digits.
 *
 * <p><strong>Scenario selection carries no switch in production code</strong> (ADR 0007). A scenario is
 * selected by the {@code service_id} a request names, or by the card number last four a test types into the
 * tokenise call -- ordinary fixture data a test already controls.
 *
 * <p>Deliberately framework-free so a test can {@code new} one, and
 * {@link FakeClickCardProviderConfiguration} is the {@code local}-profile glue for {@code make run}.
 */
public final class FakeClickCardHttpProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeClickCardHttpProvider.class);

    /** The service id of the ordinary scenario. */
    public static final String SERVICE_ID = "100100";

    /** Every call hangs past every deadline, then the connection is dropped unanswered. */
    public static final String SERVICE_ID_TIMEOUT = "100901";

    /** Every call answers HTTP 500. */
    public static final String SERVICE_ID_HTTP_500 = "100902";

    /** Every call answers 200 with a body that is not JSON. */
    public static final String SERVICE_ID_MALFORMED = "100903";

    /** The merchant user id and secret the {@code Auth} header is checked against. */
    public static final String MERCHANT_USER_ID = "fake-merchant-user";

    public static final String SECRET_KEY = "fake-click-card-secret-key-not-a-real-credential";

    /** The code the cardholder's bank "texts". */
    public static final String SMS_CODE = "246810";

    /** Test card numbers; the last four select the behaviour. */
    public static final String APPROVING_CARD = "8600123412344242";

    public static final String DECLINING_CARD = "8600123412340002";

    /** The first charge is taken and the connection is dropped before the answer. */
    public static final String LOSING_CARD = "8600123412340003";

    private static final Duration TIMEOUT_DELAY = Duration.ofSeconds(45);
    private static final ZoneOffset TASHKENT = ZoneOffset.ofHours(5);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** How a payment that is not held is reported, since the documentation does not say. */
    public enum NotHeldAnswer {
        /** 200 with {@code error_code} 0 and no payment, the shape the payment adapter already reads. */
        EMPTY_SUCCESS,
        /** HTTP 404. */
        HTTP_404,
        /** 200 with the placeholder {@code error_code} {@value #NOT_FOUND_CODE}. */
        ERROR_CODE;

        public static final long NOT_FOUND_CODE = -5001;
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;

    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, Payment> paymentsById = new ConcurrentHashMap<>();
    private final List<Payment> payments = new ArrayList<>();
    private final Map<String, Boolean> verified = new ConcurrentHashMap<>();
    private final AtomicLong paymentIds = new AtomicLong(700_000_001);
    private final AtomicLong chargeRequests = new AtomicLong();

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private volatile NotHeldAnswer notHeldAnswer = NotHeldAnswer.EMPTY_SUCCESS;
    private volatile boolean paymentStatusReportsProcessing;
    private volatile int chargeAnswersStatus = 2;
    private @Nullable HttpServer server;

    public FakeClickCardHttpProvider() {
        this(Clock.systemUTC());
    }

    public FakeClickCardHttpProvider(Clock clock) {
        this.clock = clock;
    }

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
            throw new IllegalStateException("Could not bind the fake Click card provider", failure);
        }
        // Daemon threads: a scenario that never answers must never be why a test JVM fails to exit.
        server.setExecutor(Executors.newCachedThreadPool(daemonThreadFactory()));
        server.createContext("/", this::dispatch);
        server.start();
        log.info(
                "Fake Click card provider listening on http://localhost:{}",
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

    /** The base URL an approved environment would name for this fake, with Click's own path root. */
    public String baseUrl() {
        return "http://localhost:" + port() + "/v2/merchant";
    }

    // ---------------------------------------------------------------- test hooks

    /** How an unknown {@code merchant_trans_id} is answered. */
    public void answerNotHeldWith(NotHeldAnswer answer) {
        this.notHeldAnswer = answer;
    }

    /** Makes {@code payment/status} answer 1 (in processing) for every payment, as Click's own examples show. */
    public void reportPaymentsAsProcessing(boolean processing) {
        this.paymentStatusReportsProcessing = processing;
    }

    /**
     * Makes {@code card_token/payment} answer with this {@code payment_status}, as Click's own examples show a
     * synchronous charge answering {@code 1}. The payment is recorded as taken either way.
     */
    public void answerChargesWithStatus(int status) {
        this.chargeAnswersStatus = status;
    }

    /** Every request that arrived, in order, with the body exactly as received. */
    public List<Received> requests() {
        return List.copyOf(received);
    }

    /** Every payment that was taken, in order. A double charge shows up here and nowhere else. */
    public synchronized List<Payment> paidPayments() {
        return List.copyOf(payments);
    }

    /** How many {@code card_token/payment} requests arrived, whatever came of them. */
    public long chargeRequests() {
        return chargeRequests.get();
    }

    /** How many card tokens are held. */
    public int tokensHeld() {
        return cards.size();
    }

    public synchronized void reset() {
        cards.clear();
        paymentsById.clear();
        payments.clear();
        verified.clear();
        chargeRequests.set(0);
        received.clear();
        notHeldAnswer = NotHeldAnswer.EMPTY_SUCCESS;
        paymentStatusReportsProcessing = false;
        chargeAnswersStatus = 2;
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            exchange.setStreams(new ByteArrayInputStream(raw), null);
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(raw, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Auth")));
            route(exchange);
        } catch (RuntimeException unexpected) {
            log.warn(
                    "Fake Click card provider failed to answer {} {}",
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    unexpected);
            respondJson(exchange, 500, Map.of("error_code", -1, "error_note", "fake provider fault"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        cors(exchange);
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        List<String> segments = segments(exchange.getRequestURI().getPath());
        // /v2/merchant/<resource>/...
        if (segments.size() < 3
                || !segments.get(0).equals("v2")
                || !segments.get(1).equals("merchant")) {
            respondJson(exchange, 404, Map.of("error_code", -8, "error_note", "no such endpoint (fake)"));
            return;
        }
        List<String> path = segments.subList(2, segments.size());

        if ("POST".equals(method) && path.equals(List.of("card_token", "request"))) {
            tokenRequest(exchange);
            return;
        }
        // Every other call carries Auth; card_token/request is the one Click documents without it.
        if (!authorized(exchange)) {
            respondJson(exchange, 401, Map.of("error_note", "invalid Auth header"));
            return;
        }
        if ("POST".equals(method) && path.equals(List.of("card_token", "verify"))) {
            tokenVerify(exchange);
        } else if ("POST".equals(method) && path.equals(List.of("card_token", "payment"))) {
            tokenPayment(exchange);
        } else if ("DELETE".equals(method) && path.size() == 3 && path.get(0).equals("card_token")) {
            tokenDelete(exchange, path.get(1), decode(path.get(2)));
        } else if ("GET".equals(method)
                && path.size() == 5
                && path.get(0).equals("payment")
                && path.get(1).equals("status_by_mti")) {
            statusByMerchantTransId(exchange, path.get(2), decode(path.get(3)), path.get(4));
        } else if ("GET".equals(method)
                && path.size() == 4
                && path.get(0).equals("payment")
                && path.get(1).equals("status")) {
            paymentStatus(exchange, path.get(2), decode(path.get(3)));
        } else {
            respondJson(exchange, 404, Map.of("error_code", -8, "error_note", "no such endpoint (fake)"));
        }
    }

    // ----------------------------------------------------------------- endpoints

    /** The cardholder's browser. The number arrives here and nowhere else. */
    private void tokenRequest(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String serviceId = String.valueOf(body.get("service_id"));
        if (scenarioOutcome(exchange, serviceId)) {
            return;
        }
        String number = String.valueOf(body.get("card_number"));
        String expiry = String.valueOf(body.get("expire_date"));
        if (!number.matches("\\d{16}") || !expiry.matches("(0[1-9]|1[0-2])\\d{2}")) {
            respondJson(exchange, 200, orderedMap("error_code", -100, "error_note", "placeholder: invalid card data"));
            return;
        }
        String token = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        cards.put(token, new Card(serviceId, number.substring(12), behaviourOf(number)));
        respondJson(
                exchange,
                200,
                orderedMap(
                        "error_code",
                        0,
                        "error_note",
                        "",
                        "card_token",
                        token,
                        "phone_number",
                        "+998*******97",
                        "temporary",
                        "1".equals(String.valueOf(body.get("temporary")))
                                || "true".equals(String.valueOf(body.get("temporary")))));
    }

    private void tokenVerify(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String serviceId = String.valueOf(body.get("service_id"));
        if (scenarioOutcome(exchange, serviceId)) {
            return;
        }
        Card card = cards.get(String.valueOf(body.get("card_token")));
        if (card == null || !card.serviceId().equals(serviceId)) {
            respondJson(exchange, 200, orderedMap("error_code", -101, "error_note", "placeholder: unknown card token"));
            return;
        }
        if (!SMS_CODE.equals(String.valueOf(body.get("sms_code")))) {
            respondJson(exchange, 200, orderedMap("error_code", -102, "error_note", "placeholder: wrong sms code"));
            return;
        }
        verified.put(String.valueOf(body.get("card_token")), true);
        respondJson(
                exchange,
                200,
                orderedMap("error_code", 0, "error_note", "Success", "card_number", "8600 12** **** " + card.last4()));
    }

    private void tokenPayment(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String serviceId = String.valueOf(body.get("service_id"));
        chargeRequests.incrementAndGet();
        if (scenarioOutcome(exchange, serviceId)) {
            return;
        }
        String token = String.valueOf(body.get("card_token"));
        Card card = cards.get(token);
        if (card == null || !card.serviceId().equals(serviceId) || !verified.containsKey(token)) {
            respondJson(
                    exchange, 200, orderedMap("error_code", -103, "error_note", "placeholder: card token not usable"));
            return;
        }
        Object rawAmount = body.get("amount");
        long amount = rawAmount instanceof Number number ? number.longValue() : 0L;
        String parameter = String.valueOf(body.get("transaction_parameter"));
        if (card.behaviour() == Behaviour.DECLINE) {
            respondJson(exchange, 200, orderedMap("error_code", -104, "error_note", "placeholder: insufficient funds"));
            return;
        }
        Payment payment;
        synchronized (this) {
            payment = new Payment(
                    Long.toString(paymentIds.getAndIncrement()),
                    serviceId,
                    parameter,
                    amount,
                    LocalDate.ofInstant(clock.instant(), TASHKENT));
            paymentsById.put(payment.paymentId(), payment);
            payments.add(payment);
        }
        if (card.behaviour() == Behaviour.LOSE_THE_ANSWER && card.consumeLostAnswer()) {
            // The money moved and the answer never arrives: no response is written.
            exchange.getRequestBody().readAllBytes();
            exchange.close();
            return;
        }
        respondJson(
                exchange,
                200,
                orderedMap(
                        "error_code",
                        0,
                        "error_note",
                        "Success",
                        "payment_id",
                        Long.parseLong(payment.paymentId()),
                        "payment_status",
                        chargeAnswersStatus));
    }

    private void tokenDelete(HttpExchange exchange, String serviceId, String token) throws IOException {
        if (scenarioOutcome(exchange, serviceId)) {
            return;
        }
        Card card = cards.get(token);
        if (card == null || !card.serviceId().equals(serviceId)) {
            respondJson(exchange, 200, orderedMap("error_code", -101, "error_note", "placeholder: unknown card token"));
            return;
        }
        cards.remove(token);
        verified.remove(token);
        respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success"));
    }

    /**
     * Resolves {@code merchant_trans_id}. The date segment is modelled as the payment's date in Tashkent time,
     * which is one of the readings the documentation allows; the adapter must not depend on that one.
     */
    private void statusByMerchantTransId(HttpExchange exchange, String serviceId, String parameter, String date)
            throws IOException {
        if (scenarioOutcome(exchange, serviceId)) {
            return;
        }
        Payment found;
        synchronized (this) {
            found = payments.stream()
                    .filter(payment -> payment.serviceId().equals(serviceId)
                            && payment.merchantTransId().equals(parameter)
                            && payment.date().toString().equals(date))
                    .findFirst()
                    .orElse(null);
        }
        if (found == null) {
            switch (notHeldAnswer) {
                case EMPTY_SUCCESS -> respondJson(exchange, 200, orderedMap("error_code", 0, "error_note", "Success"));
                case HTTP_404 ->
                    respondJson(exchange, 404, orderedMap("error_code", -105, "error_note", "placeholder: not found"));
                case ERROR_CODE ->
                    respondJson(
                            exchange,
                            200,
                            orderedMap(
                                    "error_code",
                                    NotHeldAnswer.NOT_FOUND_CODE,
                                    "error_note",
                                    "placeholder: payment not found"));
            }
            return;
        }
        respondJson(
                exchange,
                200,
                orderedMap(
                        "error_code",
                        0,
                        "error_note",
                        "Success",
                        "payment_id",
                        Long.parseLong(found.paymentId()),
                        "merchant_trans_id",
                        found.merchantTransId()));
    }

    private void paymentStatus(HttpExchange exchange, String serviceId, String paymentId) throws IOException {
        if (scenarioOutcome(exchange, serviceId)) {
            return;
        }
        Payment payment = paymentsById.get(paymentId);
        if (payment == null || !payment.serviceId().equals(serviceId)) {
            respondJson(exchange, 200, orderedMap("error_code", -106, "error_note", "placeholder: payment not found"));
            return;
        }
        respondJson(
                exchange,
                200,
                orderedMap(
                        "error_code",
                        0,
                        "error_note",
                        "Success",
                        "payment_id",
                        Long.parseLong(payment.paymentId()),
                        "payment_status",
                        paymentStatusReportsProcessing ? 1 : 2));
    }

    // ----------------------------------------------------------------- scenarios

    /** Answers the failure scenarios; true when the exchange has been dealt with. */
    private boolean scenarioOutcome(HttpExchange exchange, String serviceId) throws IOException {
        switch (serviceId) {
            case SERVICE_ID_HTTP_500 -> {
                respondJson(exchange, 500, Map.of("error_note", "fake provider: simulated 5xx"));
                return true;
            }
            case SERVICE_ID_MALFORMED -> {
                byte[] bytes = "<html>not json</html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                return true;
            }
            case SERVICE_ID_TIMEOUT -> {
                try {
                    Thread.sleep(TIMEOUT_DELAY);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.getRequestBody().readAllBytes();
                exchange.close();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static Behaviour behaviourOf(String number) {
        return switch (number.substring(12)) {
            case "0002" -> Behaviour.DECLINE;
            case "0003" -> Behaviour.LOSE_THE_ANSWER;
            default -> Behaviour.APPROVE;
        };
    }

    // -------------------------------------------------------------- wire helpers

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Auth");
        if (header == null) {
            return false;
        }
        String[] parts = header.split(":", -1);
        if (parts.length != 3 || !MERCHANT_USER_ID.equals(parts[0])) {
            return false;
        }
        String expected = sha1(parts[2] + SECRET_KEY);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
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

    private static void cors(HttpExchange exchange) {
        // A real browser tokenises against this when a developer walks the console locally.
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
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
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("/"));
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
            Thread thread = new Thread(runnable, "fake-click-card-provider");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** One request as it arrived. {@code auth} is the {@code Auth} header, or null when there was none. */
    public record Received(
            String method,
            String path,
            String body,
            @Nullable String auth) {}

    /** One payment the fake took. */
    public record Payment(String paymentId, String serviceId, String merchantTransId, long amountSom, LocalDate date) {}

    private enum Behaviour {
        APPROVE,
        DECLINE,
        LOSE_THE_ANSWER
    }

    private static final class Card {
        private final String serviceId;
        private final String last4;
        private final Behaviour behaviour;
        private boolean answerStillToLose = true;

        Card(String serviceId, String last4, Behaviour behaviour) {
            this.serviceId = serviceId;
            this.last4 = last4;
            this.behaviour = behaviour;
        }

        String serviceId() {
            return serviceId;
        }

        String last4() {
            return last4;
        }

        Behaviour behaviour() {
            return behaviour;
        }

        synchronized boolean consumeLostAnswer() {
            boolean lose = answerStillToLose;
            answerStillToLose = false;
            return lose;
        }
    }
}
