package uz.horecaos.platform.conversations.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One decrypted message, as the inbox detail screen renders history (ADR
 * 0059 stage 2). {@code body} is always plaintext here — {@link
 * ConversationInboxService#history} is the one call in this module that ever
 * produces one of these, and it is the audited read.
 *
 * @param direction {@code INBOUND} (the customer), {@code OUTBOUND} (the flow
 *                  engine), {@code OPERATOR} (a staff reply), or {@code
 *                  ASSISTANT} (the grounded assistant, ADR 0069)
 * @param actorPrincipalId the replying operator's subject, set only when
 *                         {@code direction} is {@code OPERATOR}
 * @param assistantTurnId the assistant turn that produced this message, set
 *                        only when {@code direction} is {@code ASSISTANT}
 */
public record ConversationMessageView(
        UUID id,
        String direction,
        @Nullable String blockId,
        @Nullable String actorPrincipalId,
        @Nullable UUID assistantTurnId,
        String body,
        Instant occurredAt) {

    static ConversationMessageView of(ConversationMessageStore.Row row) {
        return new ConversationMessageView(
                row.id(),
                row.direction().name(),
                row.blockId(),
                row.actorPrincipalId(),
                row.assistantTurnId(),
                row.body(),
                row.occurredAt());
    }
}
