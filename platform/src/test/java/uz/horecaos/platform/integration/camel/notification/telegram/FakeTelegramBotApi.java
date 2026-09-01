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
    private final Map<Long, Long> lastMessageIdByChat = new ConcurrentHashMap<>();
    private final Map<Long, Object> replyMarkupByMessageId = new ConcurrentHashMap<>();
    private final List<String> answeredCallbackQueryIds = new CopyOnWriteArrayList<>();
    private final Map<String, String> answeredCallbackQueryText = new ConcurrentHashMap<>();
    private final Map<Long, String> kicked = new ConcurrentHashMap<>();
    private final Map<Long, Long> pendingMigrations = new ConcurrentHashMap<>();
    private volatile String chatMemberStatus = "administrator";
    private final AtomicBoolean canManageTopics = new AtomicBoolean(true);
    // Absent by default: the group-link and staff-link handshakes' getMe
    // calls never read a username, only the numeric id. ADR 0058 stage 2's
    // customer deep link is the first caller that needs one, hence the
    // opt-in setter rather than a fixed default.
    private volatile @Nullable String botUsername;
    private final AtomicLong getMeCalls = new AtomicLong();
    private volatile boolean tokenRevoked;

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

    /** {@code getMe}'s {@code username} field from now on — absent (as real Telegram never is) until set. */
    public void setBotUsername(String username) {
        this.botUsername = username;
    }

    /** How many times {@code getMe} has actually been called — what a caching resolver's own test asserts against. */
    public long getMeCallCount() {
        return getMeCalls.get();
    }

    /**
     * Every Bot API call answers real Telegram's own {@code 401 Unauthorized}
     * from now on, whatever token the URL path carries — this fake never reads
     * the token out of the path (it has one socket, one scenario), so
     * revocation is global rather than per-token. What a secret-rotation
     * verification test needs: a token the manager resolves to but Telegram
     * itself refuses.
     */
    public void revokeToken() {
        tokenRevoked = true;
    }

    public void setChatMemberStatus(String status) {
        this.chatMemberStatus = status;
    }

    public void setCanManageTopics(boolean value) {
        canManageTopics.set(value);
    }

    /** The most recent message id sent (or edited) in this chat, for a test that then targets it directly. */
    public @Nullable Long lastMessageIdSentTo(long chatId) {
        return lastMessageIdByChat.get(chatId);
    }

    /**
     * Whether this message currently carries an inline keyboard — true after
     * a {@code sendMessage}/{@code editMessageReplyMarkup} that set one,
     * false once {@code editMessageReplyMarkup} strips it (ADR 0060 §2/§4:
     * "on the first successful decision the inline keyboard is stripped").
     */
    public boolean hasKeyboard(long messageId) {
        return replyMarkupByMessageId.containsKey(messageId);
    }

    /**
     * The opaque {@code callback_data} tokens on a message's current
     * keyboard, in on-screen order — what a test reads to build a realistic
     * {@code callback_query} update pointing at a real, server-minted token
     * rather than a guessed string.
     */
    @SuppressWarnings("unchecked")
    public List<String> callbackDataOn(long messageId) {
        Object markup = replyMarkupByMessageId.get(messageId);
        if (!(markup instanceof Map<?, ?> map) || !(map.get("inline_keyboard") instanceof List<?> rows)) {
            return List.of();
        }
        List<String> tokens = new java.util.ArrayList<>();
        for (Object rawRow : rows) {
            for (Object rawButton : (List<Object>) rawRow) {
                Map<String, Object> button = (Map<String, Object>) rawButton;
                tokens.add(String.valueOf(button.get("callback_data")));
            }
        }
        return tokens;
    }

    /** Every {@code callback_query_id} this fake has acknowledged, in arrival order. */
    public List<String> answeredCallbackQueryIds() {
        return List.copyOf(answeredCallbackQueryIds);
    }

    /** The toast text (if any) an {@code answerCallbackQuery} call carried for this query id. */
    public @Nullable String answeredCallbackQueryText(String callbackQueryId) {
        return answeredCallbackQueryText.get(callbackQueryId);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> body = readBody(exchange);

        if (tokenRevoked) {
            respond(exchange, 401, errorBody(401, "Unauthorized"));
            return;
        }

        switch (method) {
            case "sendMessage" -> handleSendOrEdit(exchange, body, false);
            case "editMessageText" -> handleSendOrEdit(exchange, body, true);
            case "editMessageReplyMarkup" -> handleReplyMarkupEdit(exchange, body);
            case "answerCallbackQuery" -> handleAnswerCallbackQuery(exchange, body);
            case "getMe" -> {
                getMeCalls.incrementAndGet();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", BOT_USER_ID);
                result.put("is_bot", true);
                result.put("first_name", "HorecaOS Ops");
                String username = botUsername;
                if (username != null) {
                    result.put("username", username);
                }
                respondOk(exchange, result);
            }
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
        lastMessageIdByChat.put(chatId, messageId);

        // Real Telegram semantics: sendMessage sets whatever keyboard is
        // given (including none); editMessageText only touches the keyboard
        // when reply_markup is explicitly present in the same call — omitted
        // means "leave it as is", which is exactly how TelegramChannelAdapter
        // relies on an edit preserving the Approve/Reject buttons it never
        // resends.
        if (!isEdit || body.containsKey("reply_markup")) {
            Object replyMarkup = body.get("reply_markup");
            if (replyMarkup == null || isEmptyKeyboard(replyMarkup)) {
                replyMarkupByMessageId.remove(messageId);
            } else {
                replyMarkupByMessageId.put(messageId, replyMarkup);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", messageId);
        result.put("chat", Map.of("id", chatId));
        result.put("text", text);
        respondOk(exchange, result);
    }

    private void handleReplyMarkupEdit(HttpExchange exchange, Map<String, Object> body) throws IOException {
        long chatId = number(body.get("chat_id"));
        long messageId = number(body.get("message_id"));

        String kickReason = kicked.get(chatId);
        if (kickReason != null) {
            respond(exchange, 403, errorBody(403, "Forbidden: bot was blocked by the user"));
            return;
        }

        Object replyMarkup = body.get("reply_markup");
        if (replyMarkup == null || isEmptyKeyboard(replyMarkup)) {
            replyMarkupByMessageId.remove(messageId);
        } else {
            replyMarkupByMessageId.put(messageId, replyMarkup);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", messageId);
        result.put("chat", Map.of("id", chatId));
        respondOk(exchange, result);
    }

    private void handleAnswerCallbackQuery(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String callbackQueryId = String.valueOf(body.get("callback_query_id"));
        answeredCallbackQueryIds.add(callbackQueryId);
        Object text = body.get("text");
        if (text != null) {
            answeredCallbackQueryText.put(callbackQueryId, String.valueOf(text));
        }
        respondOk(exchange, Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    private static boolean isEmptyKeyboard(Object replyMarkup) {
        return replyMarkup instanceof Map<?, ?> map
                && map.get("inline_keyboard") instanceof List<?> rows
                && rows.isEmpty();
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
