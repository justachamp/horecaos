package uz.horecaos.platform.conversations.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Someone other than the flow engine and a staff member who may take a turn in
 * a conversation (ADR 0069: "the assistant is a participant in ADR 0059's
 * conversations, not a new channel and not a second bot").
 *
 * <p>This module defines the seam and never learns who stands behind it. The
 * assistant implements it from the other side of the arrow, which is what keeps
 * {@code conversations} a leaf: it imports nothing about retrieval, models or
 * spend, and a build with no participant behaves exactly as it did before this
 * interface existed.
 *
 * <p><strong>A deterministic flow wins.</strong> A participant is only offered
 * text no flow run consumed (ADR 0069: "the assistant handles the long tail the
 * flows do not model"), and never in a conversation a person already holds.
 * Handing off is a change of author: the participant's last words are recorded
 * as its own, the conversation becomes {@code HANDED_TO_OPERATOR} with its
 * history intact, and from then on only staff reply.
 */
public interface ConversationParticipant {

    /**
     * Whether this participant would take a turn in this brand's conversations
     * at all right now: entitled, switched on, and able to answer. A cheap read
     * with no side effect, so the channel adapter can ask it before deciding
     * whether a private message is worth routing here.
     */
    boolean willingToParticipate(UUID tenantId, UUID brandId);

    /**
     * Offers one inbound customer message no flow run consumed.
     *
     * <p>Never throws for anything the participant can recover from; the caller
     * treats a thrown exception as {@link NotParticipating} and logs the type,
     * never the message.
     */
    Outcome offer(Turn turn);

    /**
     * @param channel the channel identity, with the customer account the adapter
     *                has proved for this chat (or null) -- the only identity the
     *                participant may answer order questions for
     * @param customerText what the customer just wrote, as received
     * @param history the conversation so far, oldest first, excluding {@code
     *                customerText}; bounded by the caller
     * @param sharedLocation set when the customer shared a map position
     *                       instead of typing, null otherwise
     */
    record Turn(
            ConversationChannelRef channel,
            UUID conversationId,
            String customerText,
            List<HistoryEntry> history,
            @Nullable SharedLocation sharedLocation) {

        public Turn {
            history = List.copyOf(history);
        }
    }

    /** One earlier message, with who said it. */
    record HistoryEntry(Author author, String text, Instant at) {}

    /** Who a message in the history came from. */
    enum Author {
        CUSTOMER,
        FLOW,
        ASSISTANT,
        OPERATOR
    }

    /** A position the customer shared, in WGS 84 degrees. */
    record SharedLocation(double latitude, double longitude) {}

    /** What the participant did with a turn. */
    sealed interface Outcome permits NotParticipating, Replied, HandedOff {}

    /** Nothing sent and nothing changed; the conversation proceeds as if nobody had been asked. */
    record NotParticipating() implements Outcome {}

    /**
     * An answer for the customer.
     *
     * @param turnId the participant's own record of why it said this, stored
     *               beside the message so "why did it say that" has an answer
     */
    record Replied(String text, UUID turnId) implements Outcome {}

    /**
     * The participant's last words before a person takes over. The conversation
     * becomes {@code HANDED_TO_OPERATOR} and any active flow run ends.
     */
    record HandedOff(String text, UUID turnId) implements Outcome {}
}
