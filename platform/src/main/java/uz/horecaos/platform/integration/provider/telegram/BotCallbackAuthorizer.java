package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderDecisionPort;

/**
 * The named mechanism ADR 0060 §4 requires at the callback boundary.
 *
 * <p>Capability enforcement today lives exclusively in the web layer's
 * {@code @RequiresCapability} interceptor — {@link OrderDecisionPort}'s own
 * implementation enforces nothing itself, exactly like every other
 * application service. Calling it directly from a Telegram callback would
 * therefore silently be a bypass. This class is what stops that: it resolves
 * the opaque token to a server-side action record, resolves the tapping
 * Telegram account to a linked principal <em>for that record's own tenant</em>,
 * calls the same {@link AuthorizationService#require} the interceptor calls,
 * and only then invokes {@link OrderDecisionPort#decide} — the identical call
 * {@code OperationsOrderController} makes. The bot holds no authority of its
 * own; every tap re-earns it, live, against the current grant (v1
 * "check-at-tap" revocation).
 *
 * <p>Deliberately does not touch the Bot API. {@link TelegramUpdateHandler}
 * owns the immediate {@code answerCallbackQuery} (which must not wait on this
 * class's work) and turns the {@link Outcome} this returns into the message
 * edit or follow-up the tapper sees.
 */
@Service
public class BotCallbackAuthorizer {

    private final BotActionTokenStore tokens;
    private final TelegramStaffLinkService staffLinks;
    private final AuthorizationService authorization;
    private final EntitlementService entitlements;
    private final OrderDecisionPort orderDecisions;
    private final Clock clock;

    public BotCallbackAuthorizer(
            BotActionTokenStore tokens,
            TelegramStaffLinkService staffLinks,
            AuthorizationService authorization,
            EntitlementService entitlements,
            OrderDecisionPort orderDecisions,
            Clock clock) {
        this.tokens = tokens;
        this.staffLinks = staffLinks;
        this.authorization = authorization;
        this.entitlements = entitlements;
        this.orderDecisions = orderDecisions;
        this.clock = clock;
    }

    /**
     * Resolves and, if authorized, applies one Approve/Reject tap.
     *
     * @param telegramUserId the real tapper — {@code callback_query.from.id},
     *                       which Telegram always carries even when the tap
     *                       happens in a group with anonymous admins on
     */
    public Outcome decide(String token, long telegramUserId) {
        Optional<BotActionTokenStore.OrderDecisionToken> resolved = tokens.resolveOrderDecision(token);
        if (resolved.isEmpty()) {
            return new Outcome(Result.TOKEN_EXPIRED, null, null, null);
        }
        BotActionTokenStore.OrderDecisionToken action = resolved.get();

        if (!entitlements.featureEnabled(action.tenantId(), EntitlementKeys.TELEGRAM_BOT_INTERACTIVE_ENABLED)) {
            return new Outcome(Result.NOT_ENTITLED, null, null, null);
        }

        Optional<String> principal = staffLinks.principalFor(action.tenantId(), telegramUserId);
        if (principal.isEmpty()) {
            return new Outcome(Result.NOT_LINKED, null, null, null);
        }
        String subject = principal.get();

        ResourceScope scope = ResourceScope.location(action.tenantId(), action.brandId(), action.locationId());
        try {
            // ORDER_APPROVE covers both directions of the decision, exactly as
            // OperationsOrderController.decide is gated: approving and
            // rejecting are the same authority, a decision either way.
            authorization.require(subject, Capability.ORDER_APPROVE, scope);
        } catch (AuthorizationService.AccessDeniedException denied) {
            // v1 check-at-tap revocation (ADR 0060 §4): a revoked grant answers
            // exactly like this every time, whether the link row itself was
            // ever touched or not.
            return new Outcome(Result.UNAUTHORIZED, subject, null, null);
        }

        // wave 24: the bare Reject button — authorized, but with no reason
        // chosen yet. Before presenting the follow-up keyboard, check whether
        // there is still a decision to make at all: a late tap on a stale
        // Reject button, after the order settled some other way, must still
        // be recorded — "who tried to reject this order, and when" is asked
        // after every dispute — and answered exactly as decide() always has
        // ("already approved"), rather than offering a reason picker for a
        // decision nobody can make any more. Only when the order still
        // genuinely awaits one does this return without calling decide() at
        // all: nothing was attempted, so there is nothing yet to audit — the
        // reason picker's own button, tapped next, is the first real attempt.
        if (action.action() == OrderDecisionPort.Action.REJECT
                && action.rejectReasonCode() == null
                && orderDecisions
                        .settledDecisionIfAny(action.tenantId(), action.orderId())
                        .isEmpty()) {
            return new Outcome(Result.NEEDS_REASON, subject, null, action);
        }

        OrderDecisionPort.Decision decision = orderDecisions.decide(
                action.tenantId(),
                action.orderId(),
                new OrderDecisionPort.DecisionCommand(
                        token, action.action(), subject, clock.instant(), token, action.rejectReasonCode()));

        Result result = decision.applied() ? Result.APPLIED : Result.ALREADY_SETTLED;
        return new Outcome(result, subject, decision, null);
    }

    /**
     * What happened, for {@link TelegramUpdateHandler} to render.
     *
     * @param pendingReject set only on {@link Result#NEEDS_REASON} — the
     *                       resolved order context the follow-up keyboard is
     *                       built against
     */
    public record Outcome(
            Result result,
            @Nullable String actorSubject,
            OrderDecisionPort.@Nullable Decision decision,
            BotActionTokenStore.@Nullable OrderDecisionToken pendingReject) {

        public boolean firstSuccessfulDecision() {
            return result == Result.APPLIED;
        }
    }

    public enum Result {
        /** This tap is the one that moved the order; strip the keyboard. */
        APPLIED,
        /** Somebody else's decision (possibly this same principal's earlier tap) already settled it. */
        ALREADY_SETTLED,
        /** The token does not resolve to a live action record — expired, or never existed. */
        TOKEN_EXPIRED,
        /** This tenant does not currently entitle interactive bot use. */
        NOT_ENTITLED,
        /** This Telegram account holds no staff link for the order's tenant. */
        NOT_LINKED,
        /** Linked, but the live grant does not cover this decision at this scope — refused politely. */
        UNAUTHORIZED,
        /** wave 24: the bare Reject button was tapped; present the reason picker instead of deciding. */
        NEEDS_REASON
    }
}
