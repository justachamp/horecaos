package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;
import uz.horecaos.platform.integration.api.pos.PosSyncRequester;
import uz.horecaos.platform.pos.domain.ApplyPlanner;
import uz.horecaos.platform.pos.domain.ReviewOutcome;
import uz.horecaos.platform.pos.domain.StagedDifference;
import uz.horecaos.platform.pos.domain.SyncDifference;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore.ApplyItemRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore.MappingRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore.RunRow;

/**
 * Review decisions, applying an approved run, and resuming what either of
 * ADR 0012's two interruption points left behind.
 *
 * <p><b>Deliberately not one {@code @Transactional} method.</b> A run's apply
 * items can number in the thousands, and wrapping the whole apply in one
 * transaction would make "resume" meaningless: the one failure mode resume
 * exists for — this process dying partway through — rolls back an open
 * transaction in its entirety, so nothing would ever be left half-applied to
 * resume from. Each item's execution commits on its own instead, exactly the
 * granularity {@code DeliverySourcingScheduler} and {@code PosApprovalPoll}
 * already use for the same reason: one bad or interrupted item must cost
 * itself, not the batch.
 *
 * <p><b>Two different things are called "resume" here</b>, because ADR 0012
 * has two different interruption points and this build can only safely retry
 * one of them the same way it started. A run that failed before {@code
 * REVIEW_REQUIRED} cannot resume mid-fetch — the first provider offers no
 * incremental read to pick up from a checkpoint — so {@link #resume} for a
 * {@code FAILED} run asks for a fresh {@code PosSyncRequested(RESUMED)} run
 * instead of pretending to continue one. A run interrupted mid-{@code
 * APPLYING}, by contrast, has real durable progress: every apply item already
 * has a stable identity and an idempotency key, so resuming it means exactly
 * what the word says — execute whatever is still {@code PLANNED}.
 */
@Service
public class PosApplyService {

    private static final Logger log = LoggerFactory.getLogger(PosApplyService.class);

    private final JdbcPosApplyStore store;
    private final PosSyncRequester syncRequester;
    private final Clock clock;

    public PosApplyService(JdbcPosApplyStore store, PosSyncRequester syncRequester, Clock clock) {
        this.store = store;
        this.syncRequester = syncRequester;
        this.clock = clock;
    }

    /**
     * Records one operator decision on one difference.
     *
     * @return empty when the run or the difference does not exist for this
     *         tenant; {@link DecisionOutcome#refused} when the difference is
     *         not one a decision applies to
     */
    public Optional<DecisionOutcome> decide(
            UUID tenantId,
            UUID runId,
            UUID differenceId,
            ReviewOutcome outcome,
            String reviewedBy,
            @Nullable String note) {

        Optional<RunRow> run = store.findRun(tenantId, runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        if (!"REVIEW_REQUIRED".equals(run.get().status())) {
            return Optional.of(
                    DecisionOutcome.refused("Decisions are only recorded while the run is REVIEW_REQUIRED; it is "
                            + run.get().status()));
        }

        Optional<StagedDifference> staged = store.findDifference(tenantId, runId, differenceId);
        if (staged.isEmpty()) {
            return Optional.empty();
        }
        if (staged.get().difference().recommendedAction() != SyncDifference.RecommendedAction.REVIEW) {
            // Never AUTO_APPLY (nobody needs to decide it) and never IGNORE or
            // STOP (nobody is allowed to override a HorecaOS-authoritative field
            // or force a conflict through a decision surface built for a
            // difference of opinion about a value).
            return Optional.of(DecisionOutcome.refused("This difference is recommended "
                    + staged.get().difference().recommendedAction() + ", not REVIEW; there is nothing to decide"));
        }

        boolean recorded =
                store.recordReviewDecision(tenantId, runId, differenceId, outcome, reviewedBy, note, clock.instant());
        return Optional.of(recorded ? DecisionOutcome.recorded() : DecisionOutcome.refused("Difference not found"));
    }

    /**
     * Plans and executes every item an approved run's differences produce.
     *
     * @return empty when the run does not exist
     */
    public Optional<ApplyOutcome> apply(UUID tenantId, UUID runId) {
        Optional<RunRow> run = store.findRun(tenantId, runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        RunRow row = run.get();
        if (row.dryRun()) {
            return Optional.of(ApplyOutcome.refused("A dry run never applies; see ADR 0012's rollout"));
        }
        if (!"REVIEW_REQUIRED".equals(row.status())) {
            return Optional.of(ApplyOutcome.refused("Apply requires REVIEW_REQUIRED; this run is " + row.status()
                    + ". An APPLYING run uses resume instead"));
        }

        List<StagedDifference> differences = store.stagedDifferences(tenantId, runId);
        List<ApplyPlanner.PlannedItem> planned = ApplyPlanner.plan(differences);
        store.planApplyItems(tenantId, row.bindingId(), runId, planned);

        if (!store.markApplying(tenantId, runId)) {
            // Lost a race to another apply call for the same run -- not this
            // call's failure. The items it just planned are idempotent under
            // their own key, so nothing above was wasted; the caller of
            // whichever call actually flipped the status is the one driving
            // execution.
            return Optional.of(ApplyOutcome.refused("Another apply for this run is already in progress"));
        }

        return Optional.of(execute(tenantId, row.bindingId(), runId, differences));
    }

    /**
     * Continues whatever ADR 0012's own interruption points left behind.
     *
     * @return empty when the run does not exist
     */
    public Optional<ResumeOutcome> resume(UUID tenantId, UUID runId) {
        Optional<RunRow> run = store.findRun(tenantId, runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        RunRow row = run.get();

        if ("APPLYING".equals(row.status())) {
            List<StagedDifference> differences = store.stagedDifferences(tenantId, runId);
            ApplyOutcome outcome = execute(tenantId, row.bindingId(), runId, differences);
            return Optional.of(ResumeOutcome.appliedFurther(outcome));
        }

        if ("FAILED".equals(row.status())) {
            UUID requestId = Ids.newId();
            syncRequester.requestSync(
                    PosSyncRequestedPayload.resumed(requestId, tenantId, row.bindingId(), runId, clock.instant()),
                    "pos-sync-resume:" + runId);
            log.info("Requested a resumed POS sync run for {} (failed run {})", row.bindingId(), runId);
            return Optional.of(ResumeOutcome.requestedFreshRun(requestId));
        }

        return Optional.of(ResumeOutcome.refused(
                "Resume applies to a FAILED run (fetch/compare) or an APPLYING run (apply); this run is "
                        + row.status()));
    }

    private ApplyOutcome execute(UUID tenantId, UUID bindingId, UUID runId, List<StagedDifference> differences) {
        Map<UUID, StagedDifference> byId =
                differences.stream().collect(Collectors.toMap(StagedDifference::id, staged -> staged));

        for (ApplyItemRow item : store.plannedApplyItems(tenantId, runId)) {
            executeOne(tenantId, bindingId, item, byId);
        }

        List<ApplyItemRow> all = store.applyItems(tenantId, runId);
        boolean stillPlanned = all.stream().anyMatch(item -> "PLANNED".equals(item.status()));
        if (!stillPlanned) {
            store.completeApply(tenantId, runId, clock.instant());
        }
        return ApplyOutcome.of(all);
    }

    private void executeOne(UUID tenantId, UUID bindingId, ApplyItemRow item, Map<UUID, StagedDifference> byId) {
        try {
            StagedDifference staged = item.differenceId() == null ? null : byId.get(item.differenceId());
            UUID targetId = item.targetId();
            if (staged == null || targetId == null) {
                store.markApplyItemFailed(tenantId, item.id(), "the source difference or target id is missing");
                return;
            }
            switch (item.action()) {
                case "RETIRE_MAPPING" -> retireMapping(tenantId, bindingId, item, staged, targetId);
                case "UPDATE_MAPPING" -> updateMapping(tenantId, bindingId, item, staged, targetId);
                default ->
                    // Reachable only by a defect elsewhere: every action this
                    // build cannot execute is already FAILED at plan time and
                    // never appears PLANNED.
                    store.markApplyItemFailed(
                            tenantId, item.id(), "NOT_IMPLEMENTED: no executor wired for " + item.action());
            }
        } catch (RuntimeException failure) {
            log.error("POS apply item {} failed unexpectedly", item.id(), failure);
            store.markApplyItemFailed(
                    tenantId,
                    item.id(),
                    "unexpected failure: " + failure.getClass().getSimpleName());
        }
    }

    // targetId is threaded through explicitly, already null-checked by
    // executeOne, rather than re-read from item.targetId() here: NullAway's
    // dataflow analysis is per-method and cannot see that executeOne's own
    // null check still holds by the time this method runs.
    private void retireMapping(
            UUID tenantId, UUID bindingId, ApplyItemRow item, StagedDifference staged, UUID targetId) {
        Optional<MappingRow> mapping =
                store.findMapping(tenantId, bindingId, staged.difference().entityType(), targetId);
        if (mapping.isEmpty()) {
            store.markApplyItemSkipped(tenantId, item.id(), "no active mapping exists to retire");
            return;
        }

        int expected = item.expectedTargetVersion() != null
                ? item.expectedTargetVersion()
                : Math.toIntExact(mapping.get().version());
        if (store.retireMapping(tenantId, mapping.get().id(), expected, clock.instant())) {
            store.markApplyItemApplied(tenantId, item.id(), clock.instant());
            return;
        }

        // The update matched no row under expected. Re-read: if it is already
        // RETIRED, an earlier attempt at this same item did the work and this
        // is a resume finding its own prior progress -- not a race with
        // somebody else, and not a reason to bounce a settled outcome back to
        // review.
        Optional<MappingRow> after =
                store.findMapping(tenantId, bindingId, staged.difference().entityType(), targetId);
        if (after.isPresent() && "RETIRED".equals(after.get().status())) {
            store.markApplyItemApplied(tenantId, item.id(), clock.instant());
            return;
        }
        store.markApplyItemReturnedToReview(tenantId, item.id(), "the mapping changed after this item was planned");
    }

    private void updateMapping(
            UUID tenantId, UUID bindingId, ApplyItemRow item, StagedDifference staged, UUID targetId) {
        String newExternalId = staged.difference().importedValue();
        if (newExternalId == null || newExternalId.isBlank()) {
            store.markApplyItemFailed(tenantId, item.id(), "the provider sent no value to map to");
            return;
        }

        Optional<MappingRow> mapping =
                store.findMapping(tenantId, bindingId, staged.difference().entityType(), targetId);
        if (mapping.isEmpty()) {
            store.markApplyItemFailed(tenantId, item.id(), "no existing mapping found to update");
            return;
        }

        int expected = item.expectedTargetVersion() != null
                ? item.expectedTargetVersion()
                : Math.toIntExact(mapping.get().version());
        try {
            if (store.updateMappingExternalId(tenantId, mapping.get().id(), newExternalId, expected, clock.instant())) {
                store.markApplyItemApplied(tenantId, item.id(), clock.instant());
                return;
            }
        } catch (DataIntegrityViolationException conflict) {
            store.markApplyItemFailed(tenantId, item.id(), "another mapping already claims this external id");
            return;
        }

        Optional<MappingRow> after =
                store.findMapping(tenantId, bindingId, staged.difference().entityType(), targetId);
        if (after.isPresent() && newExternalId.equals(after.get().externalEntityId())) {
            // Same reasoning as retireMapping: a prior attempt already got
            // this item to its target state.
            store.markApplyItemApplied(tenantId, item.id(), clock.instant());
            return;
        }
        store.markApplyItemReturnedToReview(tenantId, item.id(), "the mapping changed after this item was planned");
    }

    /** @param reason non-null only when {@code accepted} is false */
    public record DecisionOutcome(
            boolean accepted, @Nullable String reason) {
        static DecisionOutcome recorded() {
            return new DecisionOutcome(true, null);
        }

        static DecisionOutcome refused(String reason) {
            return new DecisionOutcome(false, reason);
        }
    }

    /**
     * @param items  every apply item this run now has, whatever state it is in
     * @param reason non-null only when the whole apply was refused before any
     *               item was touched
     */
    public record ApplyOutcome(
            boolean started,
            List<ApplyItemRow> items,
            @Nullable String reason) {
        static ApplyOutcome of(List<ApplyItemRow> items) {
            return new ApplyOutcome(true, items, null);
        }

        static ApplyOutcome refused(String reason) {
            return new ApplyOutcome(false, List.of(), reason);
        }
    }

    /**
     * @param requestedRunId set only when this call asked for a fresh run
     *                       rather than continuing this one
     */
    public record ResumeOutcome(
            boolean accepted,
            @Nullable ApplyOutcome applyProgress,
            @Nullable UUID requestedRunId,
            @Nullable String reason) {

        static ResumeOutcome appliedFurther(ApplyOutcome outcome) {
            return new ResumeOutcome(true, outcome, null, null);
        }

        static ResumeOutcome requestedFreshRun(UUID requestId) {
            return new ResumeOutcome(true, null, requestId, null);
        }

        static ResumeOutcome refused(String reason) {
            return new ResumeOutcome(false, null, null, reason);
        }
    }
}
