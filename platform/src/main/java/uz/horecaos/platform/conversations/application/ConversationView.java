package uz.horecaos.platform.conversations.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A conversation as the inbox detail screen renders its header (ADR 0059 stage 2). */
public record ConversationView(
        UUID id,
        UUID brandId,
        String channel,
        @Nullable UUID customerAccountId,
        String state,
        @Nullable String assignedTo,
        Instant updatedAt,
        long version) {

    static ConversationView of(ConversationRepository.Row row) {
        return new ConversationView(
                row.id(),
                row.brandId(),
                row.channel().name(),
                row.customerAccountId(),
                row.state().name(),
                row.assignedTo(),
                row.updatedAt(),
                row.version());
    }
}
