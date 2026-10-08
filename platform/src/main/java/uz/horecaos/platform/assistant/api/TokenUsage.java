package uz.horecaos.platform.assistant.api;

/**
 * What a model call consumed, as the provider reported it. Zero for a turn served
 * from cache or refused before any call.
 */
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("A token count is never negative");
        }
    }
}
