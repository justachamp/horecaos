package uz.horecaos.platform.assistant.api;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What the model said, normalized (ADR 0069: "reply, grounding citations,
 * refusal signal, token usage").
 *
 * <p>Nothing here is believed on its face. {@code citations} are checked against
 * the facts the request carried, and {@code reply} is checked for any figure
 * those facts do not contain, by the core and never by the adapter.
 *
 * @param reply    what to tell the customer; empty when {@code refusal}
 * @param citations the ids of the facts the reply was composed from
 * @param refusal  the model declined: the facts did not answer the question, or
 *                 it was asked for something it must not give. A first-class
 *                 outcome, never a failure
 */
public record AssistantModelResponse(
        String reply,
        List<String> citations,
        boolean refusal,
        @Nullable String refusalCode,
        TokenUsage usage) {

    public AssistantModelResponse {
        Objects.requireNonNull(reply, "A response needs a reply, empty when it refuses");
        citations = List.copyOf(citations);
        Objects.requireNonNull(usage, "A response needs its usage");
    }

    public static AssistantModelResponse refused(@Nullable String code, TokenUsage usage) {
        return new AssistantModelResponse("", List.of(), true, code, usage);
    }
}
