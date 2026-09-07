package uz.horecaos.platform.ordering.application;

import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.PosApprovalDecisionPort;

/**
 * The {@code ordering.api} face of {@link OrderStateService#decide} for a
 * POS-sourced decision (ADR 0002, ADR 0011 §6.4).
 *
 * <p>A translation layer only, the same shape {@code OrderDecisionPortAdapter}
 * is for the Telegram bot — see that class's own doc for why a second,
 * near-identical adapter exists rather than one adapter both callers share.
 * Every field this fills in that the port does not carry is fixed policy the
 * adapter applies, never something a caller configures:
 *
 * <ul>
 *   <li>{@code actorType = "SERVICE"} — {@link OrderStateService}'s own
 *       {@code recordAudit} maps that to {@code ActorRef.Type.SERVICE}, the
 *       same population support tooling and other machine callers occupy and
 *       never the one staff decisions are recorded under. A POS-sourced
 *       decision is a machine relaying a restaurant's choice, not a person,
 *       and recording it as {@code USER} would put it in the same audit
 *       population as an operator's own click.</li>
 *   <li>{@code actorId = "pos:" + providerType} — honest about which
 *       integration relayed the decision, e.g. {@code "pos:clopos"}.</li>
 *   <li>{@code decisionChannel = "POS_" + PROVIDERTYPE} — the POS counterpart
 *       of the web board's hardcoded {@code "HORECAOS_OPERATIONS"} and the
 *       bot's {@code "HORECAOS_TELEGRAM_BOT"}.</li>
 *   <li>the reason code — a clerk's accept or decline at the till carries no
 *       free text of any kind (docs/providers/clopos-api.md §6.2), so the
 *       honest audit reason is the fixed, stable string this adapter names
 *       rather than a curated {@code RejectReasonDirectory} code nothing
 *       chose. A POS reject therefore always reaches {@link
 *       OrderStateService#decide} directly, the same path an unreasoned
 *       Telegram reject takes, and never {@code OrderOutcomeService#reject}'s
 *       registry-validated one — there is no reason to validate.</li>
 * </ul>
 */
@Component
public class PosApprovalDecisionPortAdapter implements PosApprovalDecisionPort {

    static final String APPROVE_REASON_CODE = "POS_CLERK_ACCEPTED";
    static final String REJECT_REASON_CODE = "POS_CLERK_DECLINED";
    static final String DECISION_CHANNEL_PREFIX = "POS_";
    static final String ACTOR_ID_PREFIX = "pos:";

    private final OrderStateService orderState;

    public PosApprovalDecisionPortAdapter(OrderStateService orderState) {
        this.orderState = orderState;
    }

    @Override
    public Decision decide(UUID tenantId, UUID orderId, DecisionCommand command) {
        String actorId = ACTOR_ID_PREFIX + command.providerType();
        String decisionChannel = DECISION_CHANNEL_PREFIX + command.providerType().toUpperCase(Locale.ROOT);

        OrderStateService.DecisionResult result = orderState.decide(
                tenantId,
                orderId,
                new OrderStateService.DecisionCommand(
                        command.decisionId(),
                        command.action() == Action.APPROVE
                                ? OrderStateService.DecisionAction.APPROVE
                                : OrderStateService.DecisionAction.REJECT,
                        decisionChannel,
                        "SERVICE",
                        actorId,
                        command.action() == Action.APPROVE ? APPROVE_REASON_CODE : REJECT_REASON_CODE,
                        command.issuedAt(),
                        command.correlationId(),
                        null));

        return new Decision(result.applied(), result.status().name(), result.orderVersion(), settledBy(result));
    }

    private static @Nullable SettledBy settledBy(OrderStateService.DecisionResult result) {
        var effective = result.effectiveDecision();
        if (effective == null) {
            return null;
        }
        return new SettledBy(effective.decisionId(), effective.action(), effective.actorId());
    }
}
