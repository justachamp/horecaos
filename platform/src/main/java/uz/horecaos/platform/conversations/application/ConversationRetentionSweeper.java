package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The conversation retention sweep (ADR 0059, ADR 0029), in the {@code
 * ApprovalDeadlineWarningSweeper} genre: a small, read-then-act job rather
 * than a state machine transition, safe to re-run on every tick.
 *
 * <p>Two passes, oldest history first. Messages past their own conversation's
 * {@code retention_months} are deleted regardless of that conversation's
 * state — an open conversation's old messages age out exactly as a closed
 * one's do. Only the conversation row itself waits on {@code CLOSED}: a
 * conversation with a live message, or one that is not {@code CLOSED} at all,
 * survives every pass, however old it is. Deletion is the point — this is
 * customer PII whose retention has expired — so both counts are logged and
 * neither pass logs a shred of content.
 */
@Component
class ConversationRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweeper.class);

    private final ConversationRetentionService retention;
    private final Clock clock;
    private final int batchSize;

    ConversationRetentionSweeper(
            ConversationRetentionService retention,
            Clock clock,
            @Value("${horecaos.conversations.retention-sweeper.batch-size:500}") int batchSize) {
        this.retention = retention;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.conversations.retention-sweeper.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.conversations.retention-sweeper.interval:PT1H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The conversation retention sweep could not run", failure);
        }
    }

    /** @return how many messages and how many conversations were deleted this pass, for a deterministic test */
    public Result runOnce() {
        Instant now = clock.instant();
        int deletedMessages = retention.deleteExpiredMessages(now, batchSize);
        int deletedConversations = retention.deleteFullyExpiredClosedConversations(now, batchSize);
        if (deletedMessages > 0 || deletedConversations > 0) {
            log.info(
                    "Conversation retention sweep: {} messages and {} closed conversations past retention",
                    deletedMessages,
                    deletedConversations);
        }
        return new Result(deletedMessages, deletedConversations);
    }

    /** What one pass did — counts only, never which conversation or what they said. */
    public record Result(int deletedMessages, int deletedConversations) {}
}
