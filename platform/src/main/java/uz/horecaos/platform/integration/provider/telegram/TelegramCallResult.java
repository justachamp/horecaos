package uz.horecaos.platform.integration.provider.telegram;

import java.time.Duration;
import java.util.Map;

/**
 * One Bot API call's outcome, in ADR 0058's taxonomy — a superset of ADR 0007's
 * four states because two Telegram-specific answers ({@code migrate_to_chat_id}
 * and a retirement-worthy 403/400) each demand a side effect on the binding that
 * a bare {@code ProviderOutcome} has no field for.
 *
 * <p>{@link TelegramChannelAdapter} is the only place that turns one of these into
 * a {@code ProviderOutcome} and, where applicable, a
 * {@link TelegramBindingStore#retire} or {@link TelegramBindingStore#rewriteChatId}
 * call. This type stays a pure description of what Telegram said.
 */
public sealed interface TelegramCallResult {

    record Success(Map<String, Object> result) implements TelegramCallResult {}

    /** Transport or provider fault, safe to retry. */
    record Retryable(String errorCode, String detail, Duration retryAfter) implements TelegramCallResult {}

    /** Telegram may or may not have acted; there is no query to ask it, so this can only be waited out. */
    record Uncertain(String errorCode, String detail) implements TelegramCallResult {}

    /** A refusal that does not implicate the binding — malformed request, not a chat problem. */
    record BusinessRejected(String errorCode, String detail) implements TelegramCallResult {}

    /** 403 (blocked/kicked) or a topic/chat-gone 400: the binding must retire. */
    record BindingRetirement(String reason, String detail) implements TelegramCallResult {}

    /** {@code migrate_to_chat_id}: the binding's chat id must be rewritten and the send replayed once. */
    record ChatMigrated(long newChatId) implements TelegramCallResult {}

    static TelegramCallResult success(Map<String, Object> result) {
        return new Success(result);
    }

    static TelegramCallResult retryable(String errorCode, String detail, Duration retryAfter) {
        return new Retryable(errorCode, detail, retryAfter);
    }

    static TelegramCallResult uncertain(String errorCode, String detail) {
        return new Uncertain(errorCode, detail);
    }

    static TelegramCallResult businessRejected(String errorCode, String detail) {
        return new BusinessRejected(errorCode, detail);
    }

    static TelegramCallResult bindingRetirement(String reason, String detail) {
        return new BindingRetirement(reason, detail);
    }

    static TelegramCallResult chatMigrated(long newChatId) {
        return new ChatMigrated(newChatId);
    }
}
