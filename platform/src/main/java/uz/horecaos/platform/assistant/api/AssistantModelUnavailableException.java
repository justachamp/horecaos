package uz.horecaos.platform.assistant.api;

/**
 * The model provider could not answer this turn (ADR 0069: "a provider outage
 * degrades a customer-facing surface" -- and degrades it into an honest handoff,
 * not into silence).
 *
 * <p>Carries a stable {@link #code()} and a retryability flag and <em>nothing
 * else</em>. The message is the code: a provider's own error text can quote the
 * request, and the request is a customer's words.
 */
public final class AssistantModelUnavailableException extends Exception {

    private final String code;
    private final boolean retryable;

    public AssistantModelUnavailableException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    /** A stable, provider-neutral code such as {@code PROVIDER_TIMEOUT} or {@code PROVIDER_AUTHENTICATION}. */
    public String code() {
        return code;
    }

    /** Whether trying the same turn again could succeed. The assistant does not retry within a turn. */
    public boolean retryable() {
        return retryable;
    }
}
