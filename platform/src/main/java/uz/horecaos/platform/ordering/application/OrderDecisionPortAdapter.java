package uz.horecaos.platform.ordering.application;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderDecisionPort;

/**
 * The {@code ordering.api} face of {@link OrderStateService#decide}
 * (ADR 0060 §4).
 *
 * <p>A translation layer only. {@link OrderDecisionPort} exists so
 * {@code BotCallbackAuthorizer} in the {@code integration} module can call
 * exactly the decision path {@code OperationsOrderController} calls, without
 * {@code integration} importing an internal {@code ordering.application} type
 * — Spring Modulith's {@code verify()} only allows a cross-module reference
 * into {@code ordering.api}. Every field this adapter fills in that the port
 * does not carry (actor type, decision channel) is fixed, not guessed: a bot
 * decision is always {@code actorType = "USER"} (a resolved staff principal,
 * never the bot itself — see {@code BotCallbackAuthorizer}) on the
 * {@code HORECAOS_TELEGRAM_BOT} channel, the Telegram-bot counterpart of the
 * web board's hardcoded {@code "HORECAOS_OPERATIONS"}.
 */
@Component
public class OrderDecisionPortAdapter implements OrderDecisionPort {

    /**
     * {@code OrderStateService.decide} calls {@code AuditFact}'s {@code
     * .because(reasonCode)}, which refuses a blank reason for a USER actor.
     * The web board lets an operator type their own; a button tap has no free
     * text to carry, so this stable code is the honest answer to "why" — "an
     * authorized staff member tapped the button" — same discipline
     * {@code TelegramLinkCodeController} follows for its own system-generated
     * audit reasons.
     */
    static final String REASON_CODE = "TELEGRAM_BOT_TAP";

    static final String DECISION_CHANNEL = "HORECAOS_TELEGRAM_BOT";

    private final OrderStateService orderState;

    public OrderDecisionPortAdapter(OrderStateService orderState) {
        this.orderState = orderState;
    }

    @Override
    public Decision decide(java.util.UUID tenantId, java.util.UUID orderId, DecisionCommand command) {
        OrderStateService.DecisionResult result = orderState.decide(
                tenantId,
                orderId,
                new OrderStateService.DecisionCommand(
                        command.decisionId(),
                        command.action() == Action.APPROVE
                                ? OrderStateService.DecisionAction.APPROVE
                                : OrderStateService.DecisionAction.REJECT,
                        DECISION_CHANNEL,
                        "USER",
                        command.actorId(),
                        REASON_CODE,
                        command.issuedAt(),
                        command.correlationId()));

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
