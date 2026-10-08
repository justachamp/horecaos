package uz.horecaos.platform.conversations.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A conversation as the inbox detail screen renders its header (ADR 0059 stage 2).
 *
 * @param assistantActive the assistant (ADR 0069) has answered here and no person holds the
 *                        conversation, so it is the one answering the customer's next message
 * @param assistantInvolved the assistant has taken at least one turn here, whoever holds it now
 */
public record ConversationView(
        UUID id,
        UUID brandId,
        String channel,
        @Nullable UUID customerAccountId,
        String state,
        @Nullable String assignedTo,
        boolean assistantActive,
        boolean assistantInvolved,
        Instant updatedAt,
        long version) {

    static ConversationView of(ConversationRepository.Row row, boolean assistantTookPart) {
        return new ConversationView(
                row.id(),
                row.brandId(),
                row.channel().name(),
                row.customerAccountId(),
                row.state().name(),
                row.assignedTo(),
                assistantTookPart && row.state().machineAnswers(),
                assistantTookPart,
                row.updatedAt(),
                row.version());
    }
}
