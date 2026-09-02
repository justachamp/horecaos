package uz.horecaos.platform.ordering.application;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderDecisionPort;
import uz.horecaos.platform.ordering.domain.OrderStatus;

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
 * web board's hardcoded {@code "HORECAOS_OPERATIONS"}. An approve's reason is
 * always the fixed {@link #REASON_CODE} (wave 24 changes nothing there); a
 * reject that names a chosen reason routes through {@link
 * OrderOutcomeService#reject} instead — the same registry-validated,
 * note-encrypting path {@code OperationsOrderController} calls for a web
 * rejection, so a bot decision and a board decision stay byte-for-byte the
 * same call there too. A reject with no reason still reaches {@link #decide},
 * carrying {@link #REASON_CODE} like an approve does — see that method's own
 * comment for why {@code BotCallbackAuthorizer} only ever sends one of those
 * for a tap that is guaranteed to lose.
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
    private final OrderOutcomeService outcomes;

    public OrderDecisionPortAdapter(OrderStateService orderState, OrderOutcomeService outcomes) {
        this.orderState = orderState;
        this.outcomes = outcomes;
    }

    @Override
    public Decision decide(java.util.UUID tenantId, java.util.UUID orderId, DecisionCommand command) {
        // wave 24: only a REJECT that names a chosen reason routes through the
        // registry-validated path. Two other cases reach this method with no
        // rejectReasonCode: an APPROVE, always, and a REJECT that
        // BotCallbackAuthorizer forwarded purely to record a late/losing tap
        // on an already-settled order (settledDecisionIfAny found it settled
        // before this was ever called) — that call is guaranteed to land on
        // OrderStateService.decide's own "already settled" branch and never
        // actually reject anything, so the fixed REASON_CODE is exactly as
        // honest an audit reason for it as it always was for an APPROVE.
        OrderStateService.DecisionResult result =
                command.action() == Action.REJECT && command.rejectReasonCode() != null
                        ? outcomes.reject(
                                tenantId,
                                orderId,
                                new OrderOutcomeService.RejectCommand(
                                        command.decisionId(),
                                        command.rejectReasonCode(),
                                        null,
                                        DECISION_CHANNEL,
                                        "USER",
                                        command.actorId(),
                                        command.issuedAt(),
                                        command.correlationId()))
                        : orderState.decide(
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
                                        command.correlationId(),
                                        null));

        return new Decision(result.applied(), result.status().name(), result.orderVersion(), settledBy(result));
    }

    @Override
    public Optional<Decision> settledDecisionIfAny(java.util.UUID tenantId, java.util.UUID orderId) {
        return orderState
                .currentState(tenantId, orderId)
                .filter(state -> state.status() != OrderStatus.AWAITING_APPROVAL)
                .map(state -> new Decision(false, state.status().name(), state.orderVersion(), settledByState(state)));
    }

    private static @Nullable SettledBy settledByState(OrderStateService.CurrentState state) {
        var effective = state.effectiveDecision();
        if (effective == null) {
            return null;
        }
        return new SettledBy(effective.decisionId(), effective.action(), effective.actorId());
    }

    private static @Nullable SettledBy settledBy(OrderStateService.DecisionResult result) {
        var effective = result.effectiveDecision();
        if (effective == null) {
            return null;
        }
        return new SettledBy(effective.decisionId(), effective.action(), effective.actorId());
    }
}
