package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.reviews.api.CustomerReviewPort;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The customer half of the callback boundary (ADR 0075).
 *
 * <p>The sibling of {@link BotCallbackAuthorizer}, and deliberately a separate
 * class rather than a branch inside it, because the two differ in the one thing
 * that matters at this boundary: <strong>a customer tap carries no delegated
 * authority and this class calls {@code AuthorizationService.require} nowhere.
 * </strong> That is not an omission. Capabilities are ADR 0025 staff authority —
 * a manager may approve because a grant says so — and a customer buying lunch
 * exercises no authority over anything; there is no grant row per customer and
 * there is not meant to be one. {@code StorefrontOrderingController} declares
 * no capability for exactly this reason, and requiring one here would refuse
 * every caller this surface exists for.
 *
 * <p>What stands in its place is narrower and stronger: the action can only run
 * against the customer account that <em>this private chat is bound to</em>. The
 * token names a tenant, the ADR 0026 binding resolves the chat to an account
 * inside that tenant, and every port call takes that account explicitly. There
 * is no parameter anywhere below by which a tap could name a different account,
 * a different chat, or a different tenant.
 *
 * <p>Deliberately does not touch the Bot API. {@link TelegramUpdateHandler} owns
 * the immediate {@code answerCallbackQuery} and turns the {@link Outcome} into
 * what the customer sees.
 */
@Service
public class CustomerBotActionAuthorizer {

    /** ADR 0033 policy: a button can be tapped as fast as a finger moves. */
    private static final RateLimiter.Policy PER_CHAT = RateLimiter.Policy.strictPerMinute(20);

    private static final String OPERATION = "integration.telegram.customer.action";

    private final BotActionTokenStore tokens;
    private final TelegramBindingStore bindings;
    private final EntitlementService entitlements;
    private final CustomerBotOrderingPort ordering;
    private final CustomerReviewPort reviews;
    private final RateLimiter rateLimiter;
    private final AuditRecorder audit;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CustomerBotActionAuthorizer(
            BotActionTokenStore tokens,
            TelegramBindingStore bindings,
            EntitlementService entitlements,
            CustomerBotOrderingPort ordering,
            CustomerReviewPort reviews,
            RateLimiter rateLimiter,
            AuditRecorder audit,
            Clock clock) {
        this.tokens = tokens;
        this.bindings = bindings;
        this.entitlements = entitlements;
        this.ordering = ordering;
        this.reviews = reviews;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Resolves and, if the chat is entitled to it, performs one customer tap.
     *
     * <p>Empty means "not a customer action for this tapper", and the caller
     * tries the next resolver in its chain — the same shape {@code
     * resolveTenantSelect} already has. Expired, forged, and rendered-for-
     * somebody-else all collapse into it deliberately: the account predicate
     * lives inside the query, so a forwarded button tapped by a stranger cannot
     * learn that the token was ever real.
     *
     * @param privateChat whether the tap happened in a 1:1 chat. Checked after
     *     the token resolves rather than before, so a staff button tapped in a
     *     group still reaches its own authorizer
     * @param telegramUserId {@code callback_query.from.id} — the real tapper,
     *     which Telegram carries even where {@code message.from} would not
     */
    public Optional<Outcome> perform(String token, long telegramUserId, long chatId, boolean privateChat) {
        Optional<BotActionTokenStore.CustomerActionToken> resolved =
                tokens.resolveCustomerAction(token, telegramUserId);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        BotActionTokenStore.CustomerActionToken action = resolved.get();

        if (!privateChat) {
            // A customer action in a group would act on somebody's account, and
            // show their order, in front of strangers.
            return Optional.of(Outcome.of(Result.NOT_PRIVATE));
        }

        if (!entitlements.featureEnabled(action.tenantId(), EntitlementKeys.TELEGRAM_CUSTOMER_INLINE_ACTIONS_ENABLED)) {
            return Optional.of(Outcome.of(Result.NOT_ENTITLED));
        }

        Optional<UUID> account = bindings.customerAccountFor(action.tenantId(), chatId);
        if (account.isEmpty()) {
            // The chat holds no CUSTOMER binding for this token's tenant. A token
            // minted for one tenant therefore cannot be redeemed against another's
            // cart even where the same person is linked to both.
            return Optional.of(Outcome.of(Result.NOT_LINKED));
        }
        UUID customerAccountId = account.get();

        RateLimiter.Decision allowed = rateLimiter.check(
                new RateLimiter.Key(OPERATION, action.tenantId().toString(), String.valueOf(chatId)), PER_CHAT);
        if (!allowed.allowed()) {
            return Optional.of(Outcome.of(Result.RATE_LIMITED));
        }

        return Optional.of(
                switch (action.action()) {
                    case "STATUS" -> status(action, customerAccountId);
                    case "REPEAT" -> repeat(action, customerAccountId, token);
                    case "CART" -> cart(action, customerAccountId);
                    case "CHECKOUT" -> checkout(action, customerAccountId, token);
                    case "RATE" -> rate(action, customerAccountId);
                    // V0172 constrains the column to exactly the five above, so this is
                    // unreachable rather than defensive — and answering "expired" beats
                    // throwing on a webhook thread if a future migration widens the set
                    // before this switch learns about it.
                    default -> Outcome.of(Result.TOKEN_EXPIRED);
                });
    }

    private Outcome status(BotActionTokenStore.CustomerActionToken action, UUID customerAccountId) {
        return ordering.latestOrder(action.tenantId(), action.brandId(), customerAccountId)
                .map(order -> new Outcome(
                        Result.DONE,
                        action.tenantId(),
                        action.brandId(),
                        order,
                        null,
                        null,
                        null,
                        null,
                        order.completed() && !reviews.hasReview(action.tenantId(), order.orderId())))
                .orElseGet(() -> Outcome.of(Result.NOTHING_TO_SHOW));
    }

    private Outcome repeat(BotActionTokenStore.CustomerActionToken action, UUID customerAccountId, String token) {
        UUID orderId = requireOrder(action);
        CustomerBotOrderingPort.Repeat repeat =
                ordering.repeat(action.tenantId(), action.brandId(), customerAccountId, orderId);
        if (repeat.result() == CustomerBotOrderingPort.Repeat.Result.BUILT) {
            record(action, customerAccountId, "integration.telegram_customer_repeat", token, orderId);
        }
        return new Outcome(Result.DONE, action.tenantId(), action.brandId(), null, repeat, null, null, null, false);
    }

    private Outcome cart(BotActionTokenStore.CustomerActionToken action, UUID customerAccountId) {
        return ordering.currentCart(action.tenantId(), action.brandId(), customerAccountId)
                .map(card -> new Outcome(
                        Result.DONE, action.tenantId(), action.brandId(), null, null, card, null, null, false))
                .orElseGet(() -> Outcome.of(Result.NOTHING_TO_SHOW));
    }

    private Outcome checkout(BotActionTokenStore.CustomerActionToken action, UUID customerAccountId, String token) {
        Optional<CustomerBotOrderingPort.CartCard> card =
                ordering.currentCart(action.tenantId(), action.brandId(), customerAccountId);
        if (card.isEmpty()) {
            return Outcome.of(Result.NOTHING_TO_SHOW);
        }
        CustomerBotOrderingPort.Checkout done = ordering.checkoutForCash(
                action.tenantId(),
                action.brandId(),
                customerAccountId,
                card.get().cartId(),
                // The tapped button's own token. The same physical button tapped
                // twice is therefore one checkout and never two orders — the same
                // property ADR 0060 gets by making a decision token the decisionId.
                token);
        if (done.result() == CustomerBotOrderingPort.Checkout.Result.PLACED) {
            record(action, customerAccountId, "integration.telegram_customer_checkout", token, null);
        }
        return new Outcome(Result.DONE, action.tenantId(), action.brandId(), null, null, null, done, null, false);
    }

    private Outcome rate(BotActionTokenStore.CustomerActionToken action, UUID customerAccountId) {
        UUID orderId = requireOrder(action);
        int rating;
        try {
            rating = Integer.parseInt(String.valueOf(action.argument()));
        } catch (NumberFormatException notANumber) {
            return Outcome.of(Result.TOKEN_EXPIRED);
        }
        CustomerReviewPort.Outcome outcome =
                reviews.rate(action.tenantId(), action.brandId(), orderId, customerAccountId, rating);
        return new Outcome(Result.DONE, action.tenantId(), action.brandId(), null, null, null, null, outcome, false);
    }

    /** V0172's own CHECK guarantees this for STATUS, REPEAT and RATE. */
    private static UUID requireOrder(BotActionTokenStore.CustomerActionToken action) {
        UUID orderId = action.orderId();
        if (orderId == null) {
            throw new IllegalStateException("V0172 requires an order id on a " + action.action() + " token");
        }
        return orderId;
    }

    /**
     * ADR 0027, with the customer as the actor.
     *
     * <p>Recorded on the two actions that change something and on neither read:
     * a status card and a cart view are the customer looking at their own data
     * in their own chat, and an audit row per glance is noise that buries the
     * two rows anybody would ever look for.
     */
    private void record(
            BotActionTokenStore.CustomerActionToken action,
            UUID customerAccountId,
            String factType,
            String token,
            @Nullable UUID orderId) {
        audit.record(AuditFact.of(factType, AuditClass.BUSINESS)
                .by(ActorRef.user(customerAccountId.toString(), null))
                .at(ResourceScope.tenant(action.tenantId()))
                .target("Order", orderId == null ? customerAccountId : orderId)
                .because("Customer tapped an inline action in their own Telegram chat")
                .correlatedBy(token)
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * What happened, for {@link TelegramUpdateHandler} to render.
     *
     * <p>Nullable payloads rather than a sealed hierarchy, matching {@link
     * BotCallbackAuthorizer.Outcome}'s own shape: at most one is set, chosen by
     * the action the token named.
     *
     * <p>Carries the tenant and brand so the renderer can mint the follow-up
     * buttons this reply offers without resolving them a second time — and so it
     * mints them for the tenant the token named rather than for the installation
     * the webhook arrived on, which are the same thing today and would be a
     * silent cross-tenant bug the day they are not.
     *
     * @param rateable set on a STATUS outcome: completed, and reviews holds none
     *     yet. Computed here because ordering may not read the reviews module
     */
    public record Outcome(
            Result result,
            @Nullable UUID tenantId,
            @Nullable UUID brandId,
            CustomerBotOrderingPort.@Nullable OrderCard order,
            CustomerBotOrderingPort.@Nullable Repeat repeat,
            CustomerBotOrderingPort.@Nullable CartCard cart,
            CustomerBotOrderingPort.@Nullable Checkout checkout,
            CustomerReviewPort.@Nullable Outcome rating,
            boolean rateable) {

        static Outcome of(Result result) {
            return new Outcome(result, null, null, null, null, null, null, null, false);
        }
    }

    public enum Result {
        /** The action ran; exactly one payload on the outcome says what happened. */
        DONE,
        /** The token does not resolve for this account — expired, forged, or never existed. */
        TOKEN_EXPIRED,
        /** This tenant does not currently entitle customer inline actions. */
        NOT_ENTITLED,
        /** This chat holds no customer binding for the token's tenant. */
        NOT_LINKED,
        /** A customer action was tapped somewhere other than a 1:1 chat. */
        NOT_PRIVATE,
        /** Too many taps from this chat too quickly (ADR 0033). */
        RATE_LIMITED,
        /** Nothing to act on — no order yet, or no open cart. */
        NOTHING_TO_SHOW
    }
}
