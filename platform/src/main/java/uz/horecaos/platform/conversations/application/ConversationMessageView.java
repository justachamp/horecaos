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
 *                  engine), or {@code OPERATOR} (a staff reply)
 * @param actorPrincipalId the replying operator's subject, set only when
 *                         {@code direction} is {@code OPERATOR}
 */
public record ConversationMessageView(
        UUID id,
        String direction,
        @Nullable String blockId,
        @Nullable String actorPrincipalId,
        String body,
        Instant occurredAt) {

    static ConversationMessageView of(ConversationMessageStore.Row row) {
        return new ConversationMessageView(
                row.id(), row.direction().name(), row.blockId(), row.actorPrincipalId(), row.body(), row.occurredAt());
    }
}
