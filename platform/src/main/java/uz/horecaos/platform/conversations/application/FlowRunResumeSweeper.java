package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.domain.DelayBlock;
import uz.horecaos.platform.conversations.domain.FlowDocument;
import uz.horecaos.platform.conversations.domain.FlowState;

/**
 * The single sweeper a delay block genuinely needs (ADR 0059), in the {@code
 * ApprovalDeadlineWarningSweeper} genre: claims runs whose {@code
 * resume_due_at} has passed and resumes each through {@link
 * ConversationEngine#resumeDelayed}, which re-runs the same CAS discipline
 * every other transition does — a run this sweeper picks up twice (two ticks
 * racing, a redeploy mid-sweep) loses the second attempt harmlessly.
 */
@Component
class FlowRunResumeSweeper {

    private static final Logger log = LoggerFactory.getLogger(FlowRunResumeSweeper.class);

    private final FlowRunRepository runs;
    private final ConversationRepository conversations;
    private final FlowDocumentService flowDocuments;
    private final ConversationEngine engine;
    private final Clock clock;
    private final int batchSize;

    FlowRunResumeSweeper(
            FlowRunRepository runs,
            ConversationRepository conversations,
            FlowDocumentService flowDocuments,
            ConversationEngine engine,
            Clock clock,
            @Value("${horecaos.conversations.resume-sweeper.batch-size:100}") int batchSize) {
        this.runs = runs;
        this.conversations = conversations;
        this.flowDocuments = flowDocuments;
        this.engine = engine;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.conversations.resume-sweeper.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.conversations.resume-sweeper.interval:PT15S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The conversation flow resume sweep could not run", failure);
        }
    }

    /** @return how many due runs were resumed this pass, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<FlowRunRepository.Row> due = runs.dueForResume(now, batchSize);
        for (FlowRunRepository.Row run : due) {
            try {
                resumeOne(run);
            } catch (RuntimeException failure) {
                // One run's failure must not stop the sweep from resuming
                // every other due run in this batch.
                log.error("Could not resume flow run {}", run.id(), failure);
            }
        }
        return due.size();
    }

    private void resumeOne(FlowRunRepository.Row run) {
        var conversation =
                conversations.findById(run.tenantId(), run.conversationId()).orElse(null);
        if (conversation == null) {
            log.error("Flow run {} names conversation {} which no longer exists", run.id(), run.conversationId());
            return;
        }
        FlowDocument document =
                flowDocuments.parsedById(run.tenantId(), run.flowDocumentId()).orElse(null);
        if (document == null) {
            log.error("Flow run {} names flow document {} which no longer exists", run.id(), run.flowDocumentId());
            return;
        }
        FlowState state = document.state(run.currentStateId()).orElse(null);
        if (state == null || !(state.block() instanceof DelayBlock delay)) {
            log.error(
                    "Flow run {} is due for resume but its current state \"{}\" is not a delay block",
                    run.id(),
                    run.currentStateId());
            return;
        }

        ConversationChannelRef channel = new ConversationChannelRef(
                conversation.tenantId(),
                conversation.brandId(),
                conversation.installationId(),
                conversation.channel(),
                conversation.channelChatId(),
                conversation.customerAccountId());
        Map<String, String> captured = runs.capturedFields(run.tenantId(), run);
        engine.resumeDelayed(channel, run, document, delay, captured);
    }
}
