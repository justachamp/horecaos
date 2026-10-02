package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Grouping;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Skip;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;

/**
 * What the dispatch rules decided for one plan, stored on the plan and applied by every
 * later sourcing tick (ADR 0142 Decision 3).
 *
 * <p>Evaluated once, when the plan is created. Editing a rule therefore never changes what
 * happens to an order already in flight, and "why did this order go to Noor" is answered by
 * reading this and the pinned document version beside it. The live facts a tick reads -- who
 * is on shift, which partner is healthy, what a quote says -- are what they always were.
 *
 * <p>Installation ids and rule ids only. No coordinates, no names, no money (ADR 0029).
 *
 * @param ruleId the rule that matched, or {@value #DEFAULT_RULE} when the document's default
 *               (or the built-in one) did
 * @param skips  installations the rule named that could not be honoured at the branch, so a
 *               skip is visible rather than silent
 */
public record DispatchDecision(
        String ruleId,
        SourcingMode mode,
        PartnerSet partners,
        Start dispatchAt,
        @Nullable Grouping grouping,
        List<Skip> skips) {

    /** Recorded as the rule id when no rule matched; the plan column is null for it. */
    public static final String DEFAULT_RULE = "DEFAULT";

    public DispatchDecision {
        Objects.requireNonNull(ruleId, "A decision names its rule");
        Objects.requireNonNull(mode, "A decision names its mode");
        partners = partners == null ? PartnerSet.bindingOrder() : partners;
        dispatchAt = dispatchAt == null ? Start.lead() : dispatchAt;
        skips = skips == null ? List.of() : List.copyOf(skips);
    }

    /** Today's behaviour: what every plan is sourced with when nothing is published. */
    public static DispatchDecision builtInDefault() {
        return of(DEFAULT_RULE, Action.builtInDefault(), List.of());
    }

    public static DispatchDecision of(String ruleId, Action action, List<Skip> skips) {
        return new DispatchDecision(
                ruleId, action.mode(), action.partners(), action.dispatchAt(), action.grouping(), skips);
    }

    /** Whether a rule (rather than the default) produced this. */
    public boolean matchedARule() {
        return !DEFAULT_RULE.equals(ruleId);
    }
}
