package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The write a POS integration is allowed to drive: an approve/reject decision
 * on an order awaiting one, discovered by polling the till rather than typed
 * by a person (ADR 0002, ADR 0011 §6.4).
 *
 * <p>{@link OrderDecisionPort} already crosses this same module boundary for
 * one external decision source — the Telegram bot — and its own doc is
 * explicit that it is "not a general order-mutation port": fixed actor type,
 * fixed channel, fixed reason, because it exists for exactly one caller.
 * ADR 0002 names a second, genuinely different source ("accept decisions from
 * both Qoida Operations and POS; the first valid atomic decision wins"), and
 * a POS-sourced decision needs its own honest identity — a machine relaying a
 * restaurant's choice, never a person, never the bot — so this is a sibling
 * port rather than a reused one.
 *
 * <p>Implemented the same way {@code OrderDecisionPort} is: a translation
 * layer over {@link uz.horecaos.platform.ordering.application.OrderStateService#decide}
 * (via {@code PosApprovalDecisionPortAdapter} in {@code ordering.application}),
 * so a POS decision and a board decision are, byte for byte, the same call —
 * same compare-and-set, same first-decision-wins settlement, same ADR 0027
 * audit fact. The port only changes how the call is reached across a module
 * boundary Spring Modulith enforces, never what the call does.
 */
public interface PosApprovalDecisionPort {

    Decision decide(UUID tenantId, UUID orderId, DecisionCommand command);

    enum Action {
        APPROVE,
        REJECT
    }

    /**
     * @param decisionId   stable across a repeated observation of the same
     *                     clerk action. Delivery from a poll is at-least-once
     *                     — a process restart between deciding and recording
     *                     that it decided means the next tick reads the till
     *                     again and finds the same status — so the caller
     *                     derives this from something that does not change
     *                     between two such observations (the export and the
     *                     discovered outcome, not a fresh random value) and
     *                     the same compare-and-set {@code OrderDecisionPort}
     *                     relies on for its own caller settles a repeat
     *                     without deciding twice
     * @param providerType the POS vendor relaying this, e.g. {@code "clopos"}
     *                     — carried into the actor id ({@code "pos:" +
     *                     providerType}) so an audit trail never has to guess
     *                     which integration produced a given decision. Not
     *                     carried into the decision channel: {@code
     *                     ordering.approval_decisions.decision_channel}
     *                     (V0022) is constrained to a fixed, small set of
     *                     literal values, and a POS decision always records
     *                     the shared {@code "POS"} channel regardless of vendor
     */
    record DecisionCommand(
            String decisionId,
            Action action,
            String providerType,
            Instant issuedAt,
            @Nullable String correlationId) {}

    /**
     * @param applied whether this call's command is the one that moved the
     *                order
     * @param status  the order's status now, whoever moved it
     * @param settledBy the decision that actually settled the order, which may
     *                  be this call's own or somebody else's; null only when
     *                  the order carries no approval decision at all
     */
    record Decision(
            boolean applied,
            String status,
            int orderVersion,
            @Nullable SettledBy settledBy) {}

    /** Who and what actually settled the order — the audit trail's own answer. */
    record SettledBy(String decisionId, String action, String actorId) {}
}
