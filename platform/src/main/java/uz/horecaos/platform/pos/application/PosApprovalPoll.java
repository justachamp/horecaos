package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.PosApprovalDecisionPort;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * Asks each till what its clerk decided, and relays the answer (ADR 0002,
 * ADR 0011 §6.4).
 *
 * <p>The last piece of the POS approval channel. {@code PosOrderExportTrigger}
 * flags an export the moment its order enters {@code AWAITING_APPROVAL} under a
 * {@code POS} or {@code EITHER} channel; {@code PosApprovalDecisionPort} records
 * a decision once one is known. Between them there was nothing, so a clerk could
 * accept an order on the till and the platform would wait for a decision that no
 * code path would ever deliver.
 *
 * <p><strong>A poll rather than a callback, and that is the vendor's choice not
 * ours.</strong> Clopos pushes nothing; ADR 0011 §6.4 records the capability
 * snapshot's {@code decisionLatency} as what tells a caller how stale an answer
 * may be. A vendor that did push would still be read this way on recovery.
 *
 * <h2>Why the decision id is derived and not fresh</h2>
 *
 * <p>Delivery here is at-least-once in the most ordinary way: this process can
 * relay a decision and die before recording that it did, and the next tick then
 * reads the same till and finds the same answer. A fresh {@code UUID} per tick
 * would make that second reading a second decision. So the id is derived from
 * the export and the outcome observed — the same observation yields the same id,
 * and {@code OrderStateService}'s compare-and-set settles the repeat without
 * deciding twice. This is the contract {@code DecisionCommand#decisionId} states
 * and the same shape {@code OrderDecisionPort}'s own callers already rely on.
 *
 * <p>The outcome is part of the id deliberately. A clerk who rejects an order the
 * till later reports as approved is two different observations, and collapsing
 * them under one id would silently drop the second.
 *
 * <h2>Ordering of the two writes</h2>
 *
 * <p>Decide first, then mark decided. The reverse loses a decision outright if
 * this process dies between them; this way the worst case is the replay above,
 * which is safe by construction. {@code markApprovalDecided} is itself
 * conditional, so two workers racing the same export settle to one.
 */
@Component
public class PosApprovalPoll {

    private static final Logger log = LoggerFactory.getLogger(PosApprovalPoll.class);

    private final JdbcPosExportStore exports;
    private final PosOrderExportService exportService;
    private final PosApprovalDecisionPort decisions;
    private final Clock clock;
    private final int batchSize;

    public PosApprovalPoll(
            JdbcPosExportStore exports,
            PosOrderExportService exportService,
            PosApprovalDecisionPort decisions,
            Clock clock,
            @Value("${horecaos.pos.approval.poll-batch:50}") int batchSize) {
        this.exports = exports;
        this.exportService = exportService;
        this.decisions = decisions;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass over the exports still waiting on a till.
     *
     * <p>Never in a transaction, and one export at a time: a till that has
     * stopped answering costs this tick its own thread and nothing else, which
     * is the same reasoning {@code PosOrderExportTrigger#dispatchPending} states
     * for its own loop. The batch bound keeps a backlog from turning one tick
     * into a long one; the remainder is still queued for the next.
     */
    @Scheduled(
            initialDelayString = "${horecaos.pos.approval.poll-initial-delay:PT10S}",
            fixedDelayString = "${horecaos.pos.approval.poll-interval:PT15S}")
    public void pollForDecisions() {
        List<JdbcPosExportStore.PendingApproval> awaiting = exports.findAwaitingPosApproval(batchSize);
        for (JdbcPosExportStore.PendingApproval export : awaiting) {
            try {
                poll(export);
            } catch (RuntimeException failure) {
                // One unreachable till must not stop the tick for every other
                // tenant's. The export stays flagged and the next tick asks again.
                log.warn("Could not read the approval status of export {}", export.exportId(), failure);
            }
        }
    }

    private void poll(JdbcPosExportStore.PendingApproval export) {
        Optional<PosOrderExportService.ApprovalObservation> observed = exportService.readApprovalStatus(
                export.tenantId(), export.bindingId(), export.externalOrderId(), correlationOf(export));
        if (observed.isEmpty()) {
            // Could not ask. Not the same as "the clerk said no", so nothing is
            // decided and the export stays in the poll.
            return;
        }

        PosAdapter.ApprovalRead.Decision decision = observed.get().read().decision();
        if (decision == null || decision == PosAdapter.ApprovalRead.Decision.PENDING) {
            // Includes an unrecognised answer, which the adapter reports as
            // PENDING on purpose: a status this build does not understand is a
            // reason to ask again, never a reason to reject somebody's dinner.
            return;
        }

        PosApprovalDecisionPort.Action action = decision == PosAdapter.ApprovalRead.Decision.APPROVED
                ? PosApprovalDecisionPort.Action.APPROVE
                : PosApprovalDecisionPort.Action.REJECT;

        var outcome = decisions.decide(
                export.tenantId(),
                export.orderId(),
                new PosApprovalDecisionPort.DecisionCommand(
                        decisionIdFor(export, decision),
                        action,
                        observed.get().providerType(),
                        clock.instant(),
                        correlationOf(export)));

        // Marked after the decision is recorded, never before: see the class doc.
        // False here means another worker recorded it first, which is not a
        // failure and needs no second attempt.
        boolean marked = exports.markApprovalDecided(export.tenantId(), export.exportId(), clock.instant());
        log.debug(
                "Export {} relayed a {} decision (applied={}, marked={})",
                export.exportId(),
                decision,
                outcome.applied(),
                marked);
    }

    /**
     * Stable across every observation of the same clerk action, and different
     * across a changed one. Derived rather than random for the reason the class
     * doc gives.
     */
    private static String decisionIdFor(
            JdbcPosExportStore.PendingApproval export, PosAdapter.ApprovalRead.Decision decision) {
        return "pos-approval:" + export.exportId() + ":" + decision;
    }

    private static String correlationOf(JdbcPosExportStore.PendingApproval export) {
        return "pos-approval-poll:" + export.exportId();
    }
}
