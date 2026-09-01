package uz.horecaos.platform.conversations.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces {@code conversations.conversations.retention_months} (ADR 0059,
 * the named ADR 0029 gap V0108 recorded: "enforcement... is a named ADR 0029
 * gap, not built by this stage"). {@link ConversationRetentionSweeper} is the
 * scheduled caller; this class owns the two transactional writes it needs.
 *
 * <p>A separate {@code @Service} rather than methods on the sweeper itself,
 * so each one's {@code @Transactional} boundary is a real external call
 * through the Spring proxy — a self-invocation from within the sweeper would
 * silently skip the annotation, the same pitfall {@code CampaignExpansionScheduler}
 * avoids by calling {@code CampaignSendService#expandNextBatch} rather than
 * transacting on itself.
 */
@Service
class ConversationRetentionService {

    private final ConversationMessageStore messages;
    private final ConversationRepository conversations;
    private final FlowRunRepository runs;

    ConversationRetentionService(
            ConversationMessageStore messages, ConversationRepository conversations, FlowRunRepository runs) {
        this.messages = messages;
        this.conversations = conversations;
        this.runs = runs;
    }

    /**
     * Hard-deletes messages older than their own conversation's retention
     * window. State-independent: an open conversation's expired messages are
     * deleted exactly as a closed one's are — only the conversation row
     * itself waits on {@code CLOSED}.
     */
    @Transactional
    int deleteExpiredMessages(Instant now, int batchSize) {
        return messages.deleteExpired(now, batchSize);
    }

    /**
     * Removes every {@code CLOSED} conversation whose own retention window
     * has passed and that no longer carries a single message — {@code
     * flow_runs} first, since {@code fk_flow_run_conversation} carries no
     * cascade, then the conversation itself. One transaction, so the {@code
     * FOR UPDATE SKIP LOCKED} claim below still holds a row against a
     * concurrent reopen (a new inbound message on a {@code CLOSED}
     * conversation — see {@code ConversationEngine}) for as long as this
     * method needs it.
     */
    @Transactional
    int deleteFullyExpiredClosedConversations(Instant now, int batchSize) {
        List<ConversationRepository.Ref> claimed = conversations.claimClosedAndExpired(now, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }
        List<UUID> ids =
                claimed.stream().map(ConversationRepository.Ref::conversationId).toList();
        runs.deleteForConversations(ids);
        return conversations.deleteByIds(ids);
    }
}
