package uz.horecaos.platform.assistant.api;

/**
 * The language-model provider, as the assistant's core sees it (ADR 0069).
 *
 * <p>A port like every other provider on this platform (ADR 0007, ADR 0026): the
 * core hands over a normalized {@link AssistantModelRequest} and receives a
 * normalized {@link AssistantModelResponse}; no provider DTO, no model id and no
 * {@code if (provider == X)} crosses it in either direction. Which provider and
 * which model are configuration the adapter reads, and the core learns them only
 * through {@link #descriptor()} so it can record them beside what they cost.
 *
 * <p><strong>The model composes; it never recalls.</strong> The request carries
 * the facts retrieved for this turn, structured, and the response must cite the
 * ones it used. A reply that cites nothing, or cites a fact that was not given,
 * is not an answer -- the core discards it and refuses -- so an adapter that
 * returned a fluent guess would be caught by the caller, not trusted by it.
 *
 * <p>An adapter never sees a customer's name, phone number or address: the
 * request type carries none and refuses to be built with one in the text it does
 * carry ({@link AssistantModelRequest}). It must not log the request or the
 * response text, nor put either in an exception message.
 */
public interface AssistantModelPort {

    /** What this adapter is, for the ledger and the audit trail. Never a credential. */
    ProviderDescriptor descriptor();

    /**
     * Whether this adapter has everything it needs to be called: a credential
     * reference that names something, an approved endpoint. A build with no
     * provider configured answers false and the assistant does not take turns --
     * it does not hand every question to a person to say "I cannot help".
     */
    boolean configured();

    /**
     * One turn.
     *
     * @throws AssistantModelUnavailableException the provider could not be reached
     *         or refused, for a reason the caller records by code and never by text
     */
    AssistantModelResponse answer(AssistantModelRequest request) throws AssistantModelUnavailableException;

    /**
     * @param providerType a stable code such as {@code ANTHROPIC}; ledger data, never branched on
     * @param modelId      the exact model, which is configuration and is recorded per turn
     * @param adapterVersion the adapter's own declared version (ADR 0026)
     */
    record ProviderDescriptor(String providerType, String modelId, String adapterVersion) {}
}
