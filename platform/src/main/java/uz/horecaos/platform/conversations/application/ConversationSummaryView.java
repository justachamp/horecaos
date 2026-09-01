package uz.horecaos.platform.conversations.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the inbox's conversation list (ADR 0059 stage 2), as {@link
 * ConversationInboxService#list} exposes it to the operations web layer.
 * Deliberately thin: no message body ever reaches this type — "NO message
 * bodies in the list payload" is the ADR's own PII posture for this screen.
 *
 * @param customerAccountId a linked-customer indicator only — the id itself,
 *                           never a name, phone, or any other fact about the
 *                           customer
 * @param needsReply {@code HANDED_TO_OPERATOR}, or {@code FLOW_ACTIVE} with
 *                    an unanswered inbound message — see {@link
 *                    ConversationRepository.ListRow#needsReply()}
 */
public record ConversationSummaryView(
        UUID id,
        String channel,
        @Nullable UUID customerAccountId,
        String state,
        boolean needsReply,
        Instant lastActivityAt) {

    static ConversationSummaryView of(ConversationRepository.ListRow row) {
        return new ConversationSummaryView(
                row.id(),
                row.channel().name(),
                row.customerAccountId(),
                row.state().name(),
                row.needsReply(),
                row.lastActivityAt());
    }
}
