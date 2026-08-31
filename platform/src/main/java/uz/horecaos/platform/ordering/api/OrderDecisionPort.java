package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one write another module is allowed to drive: an approve/reject
 * decision on an order awaiting one (ADR 0002, ADR 0019, ADR 0060 §4).
 *
 * <p>Exists for exactly one consumer today — {@code BotCallbackAuthorizer} in
 * {@code integration}, resolving a Telegram Approve/Reject tap to the same
 * decision the web operations board makes — and is deliberately narrow: it
 * is not a general order-mutation port. {@link OrderStateService#decide} is
 * the implementation this delegates to (via {@code OrderDecisionPortAdapter}
 * in {@code ordering.application}), so a bot decision and a board decision
 * are, byte for byte, the same call: same compare-and-set, same
 * first-decision-wins settlement, same ADR 0027 audit fact — the port only
 * changes how the call is reached across a module boundary that Spring
 * Modulith enforces, never what the call does.
 *
 * <p>{@code status} and the effective decision's fields are plain strings
 * rather than {@code OrderStatus}/{@code DecisionAction}, matching
 * {@link OrderDirectory}'s own rule: no internal ordering type crosses this
 * boundary.
 */
public interface OrderDecisionPort {

    Decision decide(UUID tenantId, UUID orderId, DecisionCommand command);

    enum Action {
        APPROVE,
        REJECT
    }

    /**
     * @param decisionId stable across retries of one tap — the same button
     *                    tapped twice (a network retry, a double-tap) must be
     *                    one decision, not two; a different button (a
     *                    different chat's render of the same order) carries
     *                    its own decisionId and is a genuinely separate
     *                    decision the compare-and-set settles
     * @param actorId     the linked principal's Keycloak subject, resolved by
     *                    {@code BotCallbackAuthorizer} before this is ever
     *                    called — never a Telegram user id
     *
     *                    <p>Carries no channel or reason code: there is
     *                    exactly one caller of this port, so both are fixed
     *                    policy the adapter applies rather than something a
     *                    caller configures.
     */
    record DecisionCommand(
            String decisionId,
            Action action,
            String actorId,
            Instant issuedAt,
            @Nullable String correlationId) {}

    /**
     * @param applied whether this call's command is the one that moved the
     *                order
     * @param status  the order's status now, whoever moved it
     * @param settledBy the decision that actually settled the order, which may
     *                   be this call's own or somebody else's; null only when
     *                   the order carries no approval decision at all
     */
    record Decision(
            boolean applied,
            String status,
            int orderVersion,
            @Nullable SettledBy settledBy) {}

    /** Who and what actually settled the order — what a late tapper is shown. */
    record SettledBy(String decisionId, String action, String actorId) {}
}
