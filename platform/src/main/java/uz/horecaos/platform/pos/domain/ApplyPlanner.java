package uz.horecaos.platform.pos.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Decides what an approved run's differences turn into, and nothing else
 * (ADR 0012).
 *
 * <p>Pure, exactly like {@link DifferenceEngine}: no database, no HTTP, no
 * Spring, and {@code PosModuleBoundaryTests} enforces it here too. The
 * argument is the same one that ADR 0012 makes for the difference engine
 * itself — a database call inside this class would make every assertion about
 * it a question about what was in the database at the time, and "what does an
 * approved run apply" is exactly the kind of decision that must be
 * independently testable against a fixed input.
 *
 * <p><b>What this build can actually execute, and what it only plans.</b> Only
 * {@link Action#UPDATE_MAPPING} and {@link Action#RETIRE_MAPPING} touch a table
 * this platform already owns — {@code integration.provider_entity_mappings}
 * (ADR 0026). Every other action this planner can produce —
 * {@link Action#CREATE_DRAFT_PRODUCT}, {@link Action#CREATE_DRAFT_VARIANT}, and
 * {@link Action#UPDATE_OPERATIONAL_FIELD} — names a real ADR 0012 apply
 * outcome that this wave does not wire an executor for: creating a draft
 * product or variant needs a catalog authoring port this build does not have,
 * and "operational field" has no target column anywhere this platform owns.
 * {@code PosApplyService} plans every one of them (so an operator can see what
 * an approval would do) and executes only the two it safely can, failing the
 * rest with a distinguishing reason rather than silently dropping them or
 * guessing at a catalog write.
 */
public final class ApplyPlanner {

    private ApplyPlanner() {}

    /**
     * Every difference an approved run should turn into an apply item,
     * deterministically ordered.
     *
     * <p>Eligibility has exactly two sources, matching ADR 0012's apply policy:
     * a difference the policy already recommends {@code AUTO_APPLY} (today,
     * only a {@code MAPPING}- or {@code PROVIDER}-authority field change), or
     * one recommended {@code REVIEW} that an operator has since {@code
     * APPROVED}. Everything else — {@code IGNORE}d protected fields, {@code
     * STOP}ped conflicts, a difference still awaiting a decision, one {@code
     * REJECTED} or left {@code DEFERRED} — produces no apply item at all.
     */
    public static List<PlannedItem> plan(List<StagedDifference> differences) {
        List<PlannedItem> planned = new ArrayList<>();
        for (StagedDifference staged : differences) {
            if (!eligible(staged)) {
                continue;
            }
            planned.add(itemFor(staged));
        }
        planned.sort(Comparator.comparing(PlannedItem::idempotencyKey));
        return List.copyOf(planned);
    }

    private static boolean eligible(StagedDifference staged) {
        SyncDifference difference = staged.difference();
        if (difference.recommendedAction() == SyncDifference.RecommendedAction.AUTO_APPLY) {
            return true;
        }
        return difference.recommendedAction() == SyncDifference.RecommendedAction.REVIEW
                && staged.reviewOutcome() == ReviewOutcome.APPROVED;
    }

    private static PlannedItem itemFor(StagedDifference staged) {
        SyncDifference difference = staged.difference();
        String key = "difference:" + staged.id();

        SyncDifference.EntityType entityType = difference.entityType();

        if (difference.category() == SyncDifference.DifferenceCategory.REMOVAL_SIGNAL) {
            return new PlannedItem(
                    key,
                    staged.id(),
                    Action.RETIRE_MAPPING,
                    TargetType.MAPPING,
                    entityType,
                    difference.horecaosEntityId());
        }
        if (difference.category() == SyncDifference.DifferenceCategory.ADDITION) {
            return switch (entityType) {
                case PRODUCT ->
                    new PlannedItem(
                            key, staged.id(), Action.CREATE_DRAFT_PRODUCT, TargetType.PRODUCT, entityType, null);
                case VARIANT ->
                    new PlannedItem(
                            key, staged.id(), Action.CREATE_DRAFT_VARIANT, TargetType.VARIANT, entityType, null);
                // The apply_items action vocabulary has no CREATE_DRAFT_* for a
                // category, modifier group, modifier, or availability row -- an
                // addition there is planned as not-yet-implemented rather than
                // silently dropped or forced into a shape the schema does not
                // have a name for.
                default ->
                    new PlannedItem(
                            key, staged.id(), Action.NOT_IMPLEMENTED, targetTypeOf(difference), entityType, null);
            };
        }
        if (difference.authority() == SyncDifference.FieldAuthority.MAPPING) {
            return new PlannedItem(
                    key,
                    staged.id(),
                    Action.UPDATE_MAPPING,
                    TargetType.MAPPING,
                    entityType,
                    difference.horecaosEntityId());
        }
        if (difference.authority() == SyncDifference.FieldAuthority.PROVIDER) {
            return new PlannedItem(
                    key,
                    staged.id(),
                    Action.UPDATE_OPERATIONAL_FIELD,
                    targetTypeOf(difference),
                    entityType,
                    difference.horecaosEntityId());
        }
        // Reachable only if a future FieldAuthorityPolicy version recommends
        // AUTO_APPLY or admits an approval for an authority this planner does
        // not yet have a rule for. Planned rather than thrown, so a policy
        // change surfaces as a queue of NOT_IMPLEMENTED items an operator can
        // see, not a run that dies mid-apply.
        return new PlannedItem(key, staged.id(), Action.NOT_IMPLEMENTED, targetTypeOf(difference), entityType, null);
    }

    private static TargetType targetTypeOf(SyncDifference difference) {
        return switch (difference.entityType()) {
            case PRODUCT -> TargetType.PRODUCT;
            case VARIANT -> TargetType.VARIANT;
            case CATEGORY -> TargetType.CATEGORY;
            case MODIFIER_GROUP -> TargetType.MODIFIER_GROUP;
            case MODIFIER -> TargetType.MODIFIER;
            // AVAILABILITY has no matching apply target_type; OFFERING is the
            // closest the schema names and is itself unimplemented today.
            case AVAILABILITY -> TargetType.OFFERING;
        };
    }

    /** {@code integration.pos_sync_apply_items.action}'s values, plus this build's own escape hatch. */
    public enum Action {
        CREATE_DRAFT_PRODUCT,
        CREATE_DRAFT_VARIANT,
        UPDATE_MAPPING,
        RETIRE_MAPPING,
        UPDATE_OPERATIONAL_FIELD,
        SUSPEND_OFFERING,

        /**
         * Not a database value. {@code PosApplyService} stores this as {@code
         * UPDATE_OPERATIONAL_FIELD} (the closest real column value) and marks
         * the item {@code FAILED} with a reason naming the actual gap, so the
         * check constraint never sees a value it does not accept.
         */
        NOT_IMPLEMENTED
    }

    /** {@code integration.pos_sync_apply_items.target_type}'s values. */
    public enum TargetType {
        PRODUCT,
        VARIANT,
        CATEGORY,
        MODIFIER_GROUP,
        MODIFIER,
        MAPPING,
        OFFERING
    }

    /**
     * One apply item this run should have, before it is written or executed.
     *
     * @param mappedEntityType the difference's own {@link SyncDifference.EntityType},
     *                         carried alongside {@code targetType} because the two
     *                         diverge for a {@code MAPPING} target: executing
     *                         {@code UPDATE_MAPPING}/{@code RETIRE_MAPPING} means
     *                         looking up {@code provider_entity_mappings} by its
     *                         {@code entity_type} column, which is the original
     *                         entity's kind, not the literal string {@code "MAPPING"}
     * @param targetId         null for a not-yet-created entity ({@code
     *                         CREATE_DRAFT_PRODUCT}/{@code CREATE_DRAFT_VARIANT});
     *                         the provider-mapped HorecaOS entity id otherwise
     */
    public record PlannedItem(
            String idempotencyKey,
            UUID differenceId,
            Action action,
            TargetType targetType,
            SyncDifference.EntityType mappedEntityType,
            @Nullable UUID targetId) {}
}
