package uz.horecaos.platform.integration.provider.telegram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;

/**
 * The Bot API HTTP boundary (ADR 0058, ADR 0007's classification discipline
 * applied by hand rather than through {@code ProviderHttpClient}).
 *
 * <p>Why not the shared client every other adapter uses: its status-code
 * classifier treats 401/403 as {@code PROVIDER_AUTHENTICATION} and triggers a
 * credential refresh. For Telegram a 403 almost never means the bot token is
 * wrong — it means the bot was blocked or kicked from <em>this one chat</em>,
 * which is a per-binding fact, not a per-installation credential fact, and the
 * generic path has no hook to see the raw status before that classification
 * happens. This class owns that decision instead, and reuses
 * {@link uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier}
 * only for the thrown-exception path, where its connect-vs-read distinction is
 * exactly right and Telegram-specific.
 *
 * <p>The bot token is the URL path, not a header — Telegram's own contract, not
 * this platform's — so every log line here names the method only, never the URI.
 */
@Component
public class TelegramBotApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotApiClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient client;
    private final ObjectMapper objectMapper;

    public TelegramBotApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public TelegramCallResult sendMessage(ProviderCall call, long chatId, @Nullable Integer topicId, String text) {
        return sendMessage(call, chatId, topicId, text, null);
    }

    /**
     * @param keyboard the inline Approve/Reject-style keyboard to attach
     *                 (ADR 0060 §2), or null for a plain message
     */
    public TelegramCallResult sendMessage(
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            String text,
            @Nullable TelegramInlineKeyboard keyboard) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        if (topicId != null) {
            body.put("message_thread_id", topicId);
        }
        body.put("text", text);
        if (keyboard != null) {
            body.put("reply_markup", keyboard.toApiShape());
        }
        return call("sendMessage", call, body);
    }

    public TelegramCallResult editMessageText(ProviderCall call, long chatId, long messageId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        return call("editMessageText", call, body);
    }

    /**
     * Strips or replaces a message's inline keyboard without touching its text
     * (ADR 0060 §2/§4: "on the first successful decision the inline keyboard
     * is stripped").
     *
     * @param keyboard null strips the keyboard entirely — sent as an explicit
     *                 empty {@code inline_keyboard} rather than an omitted
     *                 field, because this call's only purpose is to change the
     *                 markup and an omitted field would leave Telegram's own
     *                 "nothing to update" behaviour to guess at
     */
    public TelegramCallResult editMessageReplyMarkup(
            ProviderCall call, long chatId, long messageId, @Nullable TelegramInlineKeyboard keyboard) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("reply_markup", keyboard == null ? Map.of("inline_keyboard", List.of()) : keyboard.toApiShape());
        return call("editMessageReplyMarkup", call, body);
    }

    /**
     * Acknowledges a callback query (ADR 0060 §2/§4): called immediately, on
     * receipt, before any authorization check or mutation — Telegram's own
     * deadline on this call is tight, and a caller that waited on the decide
     * call first would routinely miss it. The outcome of the tap is reported
     * separately, as a message edit or a follow-up send, never as a second
     * answer to the same callback query — the Bot API accepts only one.
     *
     * @param text a short toast Telegram shows the tapper, or null for a bare
     *             acknowledgement with no visible text
     */
    public TelegramCallResult answerCallbackQuery(ProviderCall call, String callbackQueryId, @Nullable String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null) {
            body.put("text", text);
        }
        return call("answerCallbackQuery", call, body);
    }

    /** The bot's own numeric user id, needed to ask {@link #getChatMember} about its own rights. */
    public TelegramCallResult getMe(ProviderCall call) {
        return call("getMe", call, Map.of());
    }

    public TelegramCallResult getChatMember(ProviderCall call, long chatId, long userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("user_id", userId);
        return call("getChatMember", call, body);
    }

    /**
     * {@code local}-profile long polling only (ADR 0058: "no public URL exists in
     * the dev loop"). Not routed through {@link #classify}, whose success path
     * assumes {@code result} is a JSON object — {@code getUpdates} answers a JSON
     * array, and this method exists to read exactly that, nothing more. A
     * developer's laptop is not where the platform-wide breaker or the retryable
     * taxonomy need to apply: a failed poll simply tries again next tick.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUpdates(ProviderCall call, long offset, int timeoutSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", timeoutSeconds);
        body.put("allowed_updates", List.of("message"));

        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            Duration deadline = Duration.ofSeconds(timeoutSeconds + 10L);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(call.baseUrl() + "/bot" + call.credential() + "/getUpdates"))
                    .timeout(deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();

            BoundedBody collected = new BoundedBody();
            HttpResponse<Void> response = send(request, collected, deadline);
            if (response.statusCode() < 200 || response.statusCode() >= 300 || collected.truncated()) {
                return List.of();
            }
            Map<String, Object> parsed =
                    collected.bytes().length == 0 ? Map.of() : objectMapper.readValue(collected.bytes(), MAP_TYPE);
            Object result = parsed.get("result");
            return result instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | RuntimeException failure) {
            log.debug(
                    "Telegram long-poll failed; will retry next tick: {}",
                    failure.getClass().getSimpleName());
            return List.of();
        }
    }

    private TelegramCallResult call(String method, ProviderCall call, Map<String, Object> body) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            Duration deadline = call.timeout() == null ? Duration.ofSeconds(15) : call.timeout();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(call.baseUrl() + "/bot" + call.credential() + "/" + method))
                    .timeout(deadline)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();

            BoundedBody collected = new BoundedBody();
            HttpResponse<Void> response = send(request, collected, deadline);
            return classify(method, response, collected);

        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return TelegramCallResult.uncertain("INTERRUPTED", "The call was interrupted");
        } catch (IOException failure) {
            boolean mayHaveReached = mayHaveReachedProvider(failure);
            log.warn(
                    "Telegram {} failed: {} (mayHaveReached={})",
                    method,
                    failure.getClass().getSimpleName(),
                    mayHaveReached);
            return mayHaveReached
                    ? TelegramCallResult.uncertain(
                            failure.getClass().getSimpleName(), "No response after the request was sent")
                    : TelegramCallResult.retryable("CONNECTION_FAILED", "Could not reach the Bot API", null);
        } catch (RuntimeException failure) {
            return TelegramCallResult.uncertain(
                    "RESPONSE_UNREADABLE", "The Bot API answered but the response could not be interpreted");
        }
    }

    private HttpResponse<Void> send(HttpRequest request, BoundedBody collected, Duration deadline)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<Void>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(collected));
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            pending.cancel(true);
            throw new HttpTimeoutException("No complete response within " + deadline);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            switch (cause) {
                case IOException io -> throw io;
                case RuntimeException runtime -> throw runtime;
                case null -> throw new IOException("The Bot API call failed without a cause");
                default -> throw new IOException(cause);
            }
        }
    }

    private static boolean mayHaveReachedProvider(Throwable failure) {
        return !(failure instanceof HttpConnectTimeoutException
                || failure instanceof ConnectException
                || failure instanceof UnknownHostException);
    }

    @SuppressWarnings("unchecked")
    private TelegramCallResult classify(String method, HttpResponse<Void> response, BoundedBody body) {
        if (body.truncated()) {
            return TelegramCallResult.uncertain("RESPONSE_TOO_LARGE", "The Bot API response exceeded the read bound");
        }

        Map<String, Object> parsed;
        try {
            parsed = body.bytes().length == 0 ? Map.of() : objectMapper.readValue(body.bytes(), MAP_TYPE);
        } catch (RuntimeException malformed) {
            return TelegramCallResult.uncertain("RESPONSE_UNREADABLE", "The Bot API response was not valid JSON");
        }

        int status = response.statusCode();
        boolean ok = Boolean.TRUE.equals(parsed.get("ok"));

        if (status >= 200 && status < 300 && ok) {
            Object result = parsed.get("result");
            return TelegramCallResult.success(result instanceof Map ? (Map<String, Object>) result : Map.of());
        }

        String description = String.valueOf(parsed.getOrDefault("description", ""));
        Map<String, Object> parameters =
                parsed.get("parameters") instanceof Map ? (Map<String, Object>) parsed.get("parameters") : Map.of();

        Object migrateTo = parameters.get("migrate_to_chat_id");
        if (migrateTo != null) {
            // Takes priority over every other classification: whatever the status
            // code, Telegram is telling us where this chat lives now.
            return TelegramCallResult.chatMigrated(((Number) migrateTo).longValue());
        }

        if (status == 403) {
            String lower = description.toLowerCase(Locale.ROOT);
            String reason = lower.contains("kick") ? "BOT_KICKED" : "BOT_BLOCKED";
            log.info("Telegram {} refused with 403 ({}): {}", method, reason, describeSafely(description));
            return TelegramCallResult.bindingRetirement(reason, description);
        }

        if (status == 400 || status == 404) {
            String lower = description.toLowerCase(Locale.ROOT);
            if (lower.contains("thread not found")
                    || lower.contains("topic_deleted")
                    || lower.contains("topic was deleted")) {
                return TelegramCallResult.bindingRetirement("THREAD_NOT_FOUND", description);
            }
            if (lower.contains("chat not found") || lower.contains("group chat was deactivated")) {
                return TelegramCallResult.bindingRetirement("TOPIC_DELETED", description);
            }
            return TelegramCallResult.businessRejected("TELEGRAM_REJECTED", description);
        }

        if (status == 429) {
            Object retryAfterBody = parameters.get("retry_after");
            Duration retryAfter = response.headers()
                    .firstValue("Retry-After")
                    .flatMap(TelegramBotApiClient::parseSeconds)
                    .or(() -> retryAfterBody instanceof Number number
                            ? Optional.of(Duration.ofSeconds(number.longValue()))
                            : Optional.empty())
                    .orElse(Duration.ofSeconds(5));
            return TelegramCallResult.retryable("RATE_LIMITED", description, retryAfter);
        }

        if (status >= 500) {
            return TelegramCallResult.retryable("TELEGRAM_UNAVAILABLE", description, Duration.ofSeconds(10));
        }

        return TelegramCallResult.uncertain("TELEGRAM_UNKNOWN_STATUS", "HTTP " + status);
    }

    private static Optional<Duration> parseSeconds(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /** No customer data ever reaches an operations-group failure description, but capped anyway. */
    private static String describeSafely(String description) {
        return description.length() > 200 ? description.substring(0, 200) + "..." : description;
    }

    private static final class BoundedBody implements java.util.function.Consumer<Optional<byte[]>> {
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
}
