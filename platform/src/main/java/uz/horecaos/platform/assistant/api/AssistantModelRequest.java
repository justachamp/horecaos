package uz.horecaos.platform.assistant.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One normalized request to the model provider (ADR 0069: "system posture,
 * retrieved facts, conversation turns").
 *
 * <p>The constructor is the PII egress guard's enforcement point. It refuses to
 * build a request whose turns carry text {@link PiiEgressGuard#redact} would
 * still change, whose pseudonym looks like anything but an opaque keyed hash, or
 * that carries no facts at all -- a request with nothing retrieved is a request
 * to answer from the model's memory, which is exactly what this assistant exists
 * not to do.
 *
 * @param posture  the standing instructions, supplied by the core and identical
 *                 for every turn of a locale; never tenant or customer text
 * @param locale   the language to reply in: {@code ru}, {@code uz} or {@code en}
 * @param facts    what was retrieved for this turn; never empty
 * @param turns    the conversation so far ending with the customer's question
 * @param customerPseudonym ADR 0029's per-tenant keyed hash of the customer
 *                 account when the chat is linked to one, null otherwise -- the
 *                 only identifier of the customer the provider is ever given
 * @param maxReplyCharacters how long a reply may be; a chat message, not an essay
 */
public record AssistantModelRequest(
        String posture,
        String locale,
        List<RetrievedFact> facts,
        List<ModelTurn> turns,
        @Nullable String customerPseudonym,
        int maxReplyCharacters) {

    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_\\-]{8,128}");

    public AssistantModelRequest {
        Objects.requireNonNull(posture, "A request needs its posture");
        Objects.requireNonNull(locale, "A request needs a reply language");
        facts = List.copyOf(Objects.requireNonNull(facts, "A request needs facts"));
        turns = List.copyOf(Objects.requireNonNull(turns, "A request needs the conversation"));
        if (facts.isEmpty()) {
            throw new IllegalArgumentException(
                    "A request with no retrieved facts would be answered from the model's memory");
        }
        if (turns.isEmpty() || turns.getLast().role() != ModelTurn.Role.CUSTOMER) {
            throw new IllegalArgumentException("A request must end with the customer's question");
        }
        for (ModelTurn turn : turns) {
            PiiEgressGuard.requireClean(turn.text(), "A conversation turn");
        }
        if (customerPseudonym != null && !OPAQUE.matcher(customerPseudonym).matches()) {
            throw new IllegalArgumentException("A customer may only be identified to a provider by an opaque hash");
        }
        if (maxReplyCharacters < 50) {
            throw new IllegalArgumentException("A reply limit under fifty characters cannot hold an answer");
        }
    }
}
