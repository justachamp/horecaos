package uz.horecaos.platform.integration.camel.notification.telegram;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for the Bot API HTTP boundary (ADR 0058), in the
 * {@code FakeSmsGateway}/{@code FakeClickHttpProvider} genre: a real socket,
 * reached through the real {@code TelegramBotApiClient}, so a test exercises the
 * whole path — the adapter, the per-chat lease, the message tracker, the
 * circuit breaker — rather than a mock of one interface.
 *
 * <p>Covers the taxonomy {@link TelegramBotApiClient} classifies: a plain
 * {@code sendMessage}/{@code editMessageText} success; {@code 403} (kicked or
 * blocked); a {@code migrate_to_chat_id} answer, armed to fire once and then
 * behave as the new chat id from then on; {@code getMe}/{@code getChatMember}
 * for the rights-verification handshake, with configurable rights so a test can
 * exercise the actionable-failure path too.
 *
 * <p>Scenario selection is by chat id and by explicit setters, and lives only in
 * test sources — no production adapter code has a scenario switch, per ADR 0007.
 */
public final class FakeTelegramBotApi implements AutoCloseable {

    public static final long BOT_USER_ID = 999_000_111L;

    private final HttpServer server;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicLong nextMessageId = new AtomicLong(1);
    private final Map<Long, List<String>> sentByChat = new ConcurrentHashMap<>();
    private final Map<Long, String> kicked = new ConcurrentHashMap<>();
    private final Map<Long, Long> pendingMigrations = new ConcurrentHashMap<>();
    private volatile String chatMemberStatus = "administrator";
    private final AtomicBoolean canManageTopics = new AtomicBoolean(true);

    private FakeTelegramBotApi(HttpServer server) {
        this.server = server;
    }

    public static FakeTelegramBotApi start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeTelegramBotApi fake = new FakeTelegramBotApi(server);
        server.createContext("/", fake::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return fake;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** The message bodies actually delivered to a chat, oldest first — the FIFO story. */
    public List<String> messagesSentTo(long chatId) {
        return List.copyOf(sentByChat.getOrDefault(chatId, List.of()));
    }

    /** 403 on every send to this chat from now on, with the reason Telegram's own description would carry. */
    public void kick(long chatId, boolean wasKicked) {
        kicked.put(chatId, wasKicked ? "kicked" : "blocked");
    }

    /** The next send to {@code oldChatId} answers {@code migrate_to_chat_id}; every send after that succeeds under {@code newChatId}. */
    public void migrateOnNextSend(long oldChatId, long newChatId) {
        pendingMigrations.put(oldChatId, newChatId);
    }

    public void setChatMemberStatus(String status) {
        this.chatMemberStatus = status;
    }

    public void setCanManageTopics(boolean value) {
        canManageTopics.set(value);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> body = readBody(exchange);

        switch (method) {
            case "sendMessage" -> handleSendOrEdit(exchange, body, false);
            case "editMessageText" -> handleSendOrEdit(exchange, body, true);
            case "getMe" ->
                respondOk(exchange, Map.of("id", BOT_USER_ID, "is_bot", true, "first_name", "HorecaOS Ops"));
            case "getChatMember" ->
                respondOk(exchange, Map.of("status", chatMemberStatus, "can_manage_topics", canManageTopics.get()));
            case "getUpdates" -> respondOk(exchange, List.of());
            default -> respond(exchange, 404, errorBody(404, "Not Found: unknown method"));
        }
    }

    private void handleSendOrEdit(HttpExchange exchange, Map<String, Object> body, boolean isEdit) throws IOException {
        long chatId = number(body.get("chat_id"));

        Long migrateTo = pendingMigrations.remove(chatId);
        if (migrateTo != null) {
            respond(
                    exchange,
                    400,
                    errorBodyWithParameters(
                            400,
                            "Bad Request: group chat was upgraded to a supergroup chat",
                            Map.of("migrate_to_chat_id", migrateTo)));
            return;
        }

        String kickReason = kicked.get(chatId);
        if (kickReason != null) {
            String description = "kicked".equals(kickReason)
                    ? "Forbidden: bot was kicked from the group chat"
                    : "Forbidden: bot was blocked by the user";
            respond(exchange, 403, errorBody(403, description));
            return;
        }

        String text = String.valueOf(body.get("text"));
        long messageId = isEdit ? number(body.get("message_id")) : nextMessageId.getAndIncrement();
        sentByChat.computeIfAbsent(chatId, key -> new CopyOnWriteArrayList<>()).add(text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", messageId);
        result.put("chat", Map.of("id", chatId));
        result.put("text", text);
        respondOk(exchange, result);
    }

    private static long number(@Nullable Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected a numeric Bot API field but got " + value);
        }
        return number.longValue();
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return Map.of();
        }
        return jsonMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    }

    private void respondOk(HttpExchange exchange, Object result) throws IOException {
        respond(exchange, 200, jsonMapper.writeValueAsString(Map.of("ok", true, "result", result)));
    }

    private String errorBody(int errorCode, String description) {
        return jsonMapper.writeValueAsString(Map.of("ok", false, "error_code", errorCode, "description", description));
    }

    private String errorBodyWithParameters(int errorCode, String description, Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error_code", errorCode);
        body.put("description", description);
        body.put("parameters", parameters);
        return jsonMapper.writeValueAsString(body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
