package uz.horecaos.platform.pos.application;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.ExternalWorkInboxHandler;
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;

/**
 * Turns a durably-decided command into a running catalog sync (ADR 0012).
 *
 * <p>Everything about this handler's safety beyond the provider call comes from
 * the inbox it runs inside, not from anything written here. The scheduler
 * already guarantees at most one {@code PosSyncRequested} is enqueued per due
 * schedule occurrence (claiming the row under {@code FOR UPDATE SKIP LOCKED} and
 * advancing {@code next_run_at} in the same transaction as the outbox append);
 * this handler's own job is only to be safe under the redelivery Kafka can still
 * cause on top of that. The ADR 0005 inbox's {@code (consumer_name, event_id)}
 * key is what makes that true: a duplicate delivery of the same command is
 * recognised and acknowledged before {@link #perform} runs a second time, so
 * {@link PosCatalogSyncService#run} executes exactly once per command, however
 * many times Kafka redelivers it.
 *
 * <p><b>{@link ExternalWorkInboxHandler}, not the plain {@code InboxHandler}, and
 * that is load-bearing.</b> {@link PosCatalogSyncService#run} reads the provider
 * — Clopos, over HTTP, across as many pages as the catalog needs — and {@code
 * InboxHandler}'s own contract says plainly that a handler must not do that: a
 * plain handler runs inside the transaction that also marks the inbox row
 * {@code PROCESSED}, and a provider call in there would hold one of ten pooled
 * connections open for the whole of the fetch. {@link #perform} is where the
 * call happens, with nothing open; {@link #record} runs inside the commit and
 * does no work of its own, because {@link PosCatalogSyncService#run} already
 * wrote everything it produced durably on the way — each of its steps commits
 * on its own, which is the same incremental-commit design ADR 0012 uses so a
 * failed run can resume from a checkpoint instead of losing partial progress.
 * There is nothing left for {@code record} to write; it exists to log what
 * {@link #perform} learned.
 */
@Component
public class PosSyncRequestedHandler
        implements ExternalWorkInboxHandler<PosSyncRequestedPayload, PosCatalogSyncService.RunResult> {

    /** ADR 0012 catalogue key, matching {@code PosSyncOutbox.COMMAND_EVENT_TYPE}. */
    public static final String CONSUMER_NAME = "pos-sync-scheduler";

    private static final Logger log = LoggerFactory.getLogger(PosSyncRequestedHandler.class);

    private final PosCatalogSyncService sync;

    public PosSyncRequestedHandler(PosCatalogSyncService sync) {
        this.sync = sync;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return "PosSyncRequested";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Class<PosSyncRequestedPayload> payloadType() {
        return PosSyncRequestedPayload.class;
    }

    /**
     * Reads the provider and stages/compares a run. No transaction is open —
     * see the class doc for why that matters here specifically.
     *
     * <p>The envelope tenant is verified context; the payload's own {@code
     * tenantId} is producer-controlled and used only by {@link #record}'s log
     * line, never as the tenant this call runs under — see {@link
     * ExternalEventEnvelope}'s own doc on exactly this point.
     */
    @Override
    public PosCatalogSyncService.RunResult perform(
            ExternalEventEnvelope<PosSyncRequestedPayload> event, Attempt attempt) {
        PosSyncRequestedPayload payload = event.payload();
        return sync.run(event.tenantId(), payload.bindingId(), payload.triggerType(), false);
    }

    /** Nothing to write; {@link #perform} already committed every effect it produced. */
    @Override
    public void record(
            ExternalEventEnvelope<PosSyncRequestedPayload> event, PosCatalogSyncService.@Nullable RunResult work) {
        PosSyncRequestedPayload payload = event.payload();
        log.info(
                "POS sync command {} ({}) for binding {} produced run {} in status {}",
                payload.requestId(),
                payload.triggerType(),
                payload.bindingId(),
                work == null ? null : work.runId(),
                work == null ? "UNKNOWN" : work.status());
    }
}
