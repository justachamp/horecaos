package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.catalog.api.StopListPort;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.conversations.api.ChannelKind;
import uz.horecaos.platform.conversations.api.ConversationCallbackToken;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationInboundPort;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService.PendingCustomerLink;
import uz.horecaos.platform.integration.provider.telegram.TelegramLinkService.PendingLink;
import uz.horecaos.platform.integration.provider.telegram.TelegramStaffLinkService.PendingStaffLink;
import uz.horecaos.platform.integration.provider.telegram.TelegramStaffLinkService.TenantLink;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;

/**
 * Everything an authenticated Telegram update may cause.
 *
 * <p>ADR 0058 stage 1 shipped exactly one command, group {@code /link
 * <code>}, and ignored every other update shape. ADR 0060 grows hands: a
 * staff {@code /link <code>} in a 1:1 chat (a different handshake — see
 * {@link TelegramStaffLinkService}), a callback query from an inline
 * Approve/Reject button (resolved through {@link BotCallbackAuthorizer}, the
 * one place capability enforcement actually happens on this boundary), and
 * two typed commands, {@code /86} and {@code /stats}, usable in a 1:1 chat or
 * a bound group. ADR 0058 stage 2 adds a third private-chat handshake, {@code
 * /start <code>} — the Bot API's own deep-link command, arriving unprompted
 * the moment a customer opens {@code https://t.me/<bot>?start=<code>} — which
 * links the chat to a customer account rather than a staff principal (see
 * {@link TelegramCustomerLinkService}). A customer may also link without ever
 * sending a Telegram message at all, through a verified Mini App {@code
 * initData} payload presented directly to a storefront endpoint; that path
 * calls {@link TelegramCustomerLinkService#link} the same way this class's
 * {@code /start} handler does, and never reaches this class.
 */
@Service
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    private static final String LINK_COMMAND = "link";

    /** ADR 0058 stage 2: the customer 1:1 deep-link handshake, private chat only. */
    private static final String START_COMMAND = "start";

    private static final String STOP_LIST_COMMAND = "86";
    private static final String STATS_COMMAND = "stats";
    private static final Set<String> TYPED_COMMANDS = Set.of(STOP_LIST_COMMAND, STATS_COMMAND);

    /** Every event class an operations chat is subscribed to by default (ADR 0058/0060). */
    private static final Set<String> DEFAULT_SUBSCRIPTIONS =
            Set.of("ORDER_CONFIRMED", "ORDER_REJECTED", "ORDER_APPROVAL_DEADLINE_WARNING", "ORDER_AWAITING_APPROVAL");

    private static final Duration TENANT_SELECT_TTL = Duration.ofMinutes(15);

    /** {@code InventoryService#setAvailabilityAudited} requires a reason; a typed command carries no free text. */
    private static final String STOP_LIST_TOGGLE_REASON = "TELEGRAM_BOT_86";

    private final TelegramLinkService links;
    private final TelegramStaffLinkService staffLinks;
    private final TelegramCustomerLinkService customerLinks;
    private final TelegramRightsVerifier rights;
    private final TelegramBindingStore bindings;
    private final BotActionTokenStore actionTokens;
    private final BotCallbackAuthorizer callbackAuthorizer;
    private final AuthorizationService authorization;
    private final EntitlementService entitlements;
    private final OrderDirectory orderDirectory;
    private final RecipientContactDirectory contacts;
    private final StopListPort stopList;
    private final StockAvailabilityPort stockAvailability;
    private final TelegramBotApiClient bots;
    private final SecretResolver secrets;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String defaultLocale;
    private final ConversationInboundPort conversations;
    private final TelegramInstallationBrandLookup installationBrands;
    private final TelegramUpdateDedupStore dedup;

    public TelegramUpdateHandler(
            TelegramLinkService links,
            TelegramStaffLinkService staffLinks,
            TelegramCustomerLinkService customerLinks,
            TelegramRightsVerifier rights,
            TelegramBindingStore bindings,
            BotActionTokenStore actionTokens,
            BotCallbackAuthorizer callbackAuthorizer,
            AuthorizationService authorization,
            EntitlementService entitlements,
            OrderDirectory orderDirectory,
            RecipientContactDirectory contacts,
            StopListPort stopList,
            StockAvailabilityPort stockAvailability,
            TelegramBotApiClient bots,
            SecretResolver secrets,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.notifications.telegram.group-locale:ru}") String defaultLocale,
            ConversationInboundPort conversations,
            TelegramInstallationBrandLookup installationBrands,
            TelegramUpdateDedupStore dedup) {
        this.links = links;
        this.staffLinks = staffLinks;
        this.customerLinks = customerLinks;
        this.rights = rights;
        this.bindings = bindings;
        this.actionTokens = actionTokens;
        this.callbackAuthorizer = callbackAuthorizer;
        this.authorization = authorization;
        this.entitlements = entitlements;
        this.orderDirectory = orderDirectory;
        this.contacts = contacts;
        this.stopList = stopList;
        this.stockAvailability = stockAvailability;
        this.bots = bots;
        this.secrets = secrets;
        this.audit = audit;
        this.clock = clock;
        this.defaultLocale = defaultLocale;
        this.conversations = conversations;
        this.installationBrands = installationBrands;
        this.dedup = dedup;
    }

    /**
     * @return true the first time this update's {@code update_id} is seen for
     *         this installation; false on a redelivery, which the caller must
     *         not act on again (ADR 0032)
     */
    private boolean isNewUpdate(WebhookInstallation installation, Map<String, Object> update) {
        if (!(update.get("update_id") instanceof Number updateIdNumber)) {
            // Every real Bot API update carries one; its absence only happens
            // in a hand-built fixture that does not exercise dedup, and
            // proceeding rather than dropping keeps that fixture working.
            return true;
        }
        return dedup.recordIfNew(installation.tenantId(), installation.installationId(), updateIdNumber.longValue());
    }

    /**
     * @param installation already authenticated by the controller's secret-token
     *                     check; nothing here re-verifies it
     * @param update the parsed Bot API {@code Update} object
     */
    public void handle(WebhookInstallation installation, Map<String, Object> update) {
        if (!isNewUpdate(installation, update)) {
            // ADR 0032: a redelivered update_id — a webhook retry, or the
            // local long-polling consumer racing a slow ack — must not run
            // any of this again. Checked before anything else, including the
            // secret-token-authenticated installation's own tenant, so a
            // duplicate never reaches a handshake, a typed command, or the
            // conversations engine a second time.
            return;
        }

        if (update.get("callback_query") instanceof Map<?, ?> rawCallback) {
            handleCallbackQuery(installation, asMap(rawCallback));
            return;
        }

        Object messageObject = update.get("message");
        if (!(messageObject instanceof Map<?, ?> rawMessage)) {
            return;
        }
        Map<String, Object> message = asMap(rawMessage);

        Object textObject = message.get("text");
        if (!(textObject instanceof String text)) {
            return;
        }

        Object chatObject = message.get("chat");
        if (!(chatObject instanceof Map<?, ?> chat) || !(chat.get("id") instanceof Number chatIdNumber)) {
            return;
        }
        long chatId = chatIdNumber.longValue();
        String chatType = String.valueOf(chat.get("type"));
        Integer topicId = message.get("message_thread_id") instanceof Number threadId ? threadId.intValue() : null;
        long fromUserId = fromUserId(message);
        boolean anonymousGroupAdmin = message.get("sender_chat") != null;

        ProviderCall call = resolveCall(installation);

        ParsedCommand parsed = parseCommand(text);
        if (parsed == null) {
            // Not a command at all — ordinary free text. ADR 0059 precedence
            // tier 4: only a private chat, only when entitled and an active
            // flow run is actually waiting on text, does anything happen;
            // every group chat and every other case is exactly today's
            // silent no-op, unchanged.
            if ("private".equals(chatType) && fromUserId != 0L) {
                handlePrivateFreeText(installation, chatId, text);
            }
            return;
        }

        if ("private".equals(chatType)) {
            handlePrivateMessage(installation, call, chatId, topicId, fromUserId, parsed);
            return;
        }

        if (!("group".equals(chatType) || "supergroup".equals(chatType))) {
            // A channel post or another shape this bot has no command for.
            return;
        }

        if (LINK_COMMAND.equals(parsed.command())) {
            handleGroupLink(installation, call, chatId, topicId, fromUserId, parsed.argument());
            return;
        }

        if (!TYPED_COMMANDS.contains(parsed.command())) {
            return;
        }

        if (anonymousGroupAdmin) {
            // ADR 0060 §2: "a typed command from an anonymous group admin
            // cannot resolve to a principal and is politely refused... button
            // taps still resolve the real tapper and are unaffected" — this
            // branch is exactly the one case that sentence describes.
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.anonymousAdminRefused(defaultLocale));
            return;
        }

        handleGroupTypedCommand(installation, call, chatId, topicId, fromUserId, parsed);
    }

    // ------------------------------------------------------------------ callbacks

    private void handleCallbackQuery(WebhookInstallation installation, Map<String, Object> callback) {
        Object idObject = callback.get("id");
        if (!(idObject instanceof String callbackQueryId)) {
            return;
        }
        ProviderCall call = resolveCall(installation);

        // ADR 0060 §2/§4: acknowledged immediately, before token resolution,
        // authorization, or any mutation — this call has a tight Telegram
        // deadline and the outcome is reported separately, as a message edit
        // or follow-up, never as a second answer to the same query.
        bots.answerCallbackQuery(call, callbackQueryId, null);

        Object dataObject = callback.get("data");
        if (!(dataObject instanceof String token) || token.isBlank()) {
            return;
        }
        Object messageObject = callback.get("message");
        if (!(messageObject instanceof Map<?, ?> rawMessage)) {
            return;
        }
        Map<String, Object> message = asMap(rawMessage);
        Object chatObject = message.get("chat");
        if (!(chatObject instanceof Map<?, ?> chat) || !(chat.get("id") instanceof Number chatIdNumber)) {
            return;
        }
        long chatId = chatIdNumber.longValue();
        Integer topicId = message.get("message_thread_id") instanceof Number threadId ? threadId.intValue() : null;
        Long messageId =
                message.get("message_id") instanceof Number messageIdNumber ? messageIdNumber.longValue() : null;

        // The real tapper — callback_query.from.id is populated by Telegram
        // even when the tap happens in a group with anonymous admins on,
        // unlike message.from for a typed command (ADR 0060 §2's own point).
        long fromUserId = callback.get("from") instanceof Map<?, ?> from && from.get("id") instanceof Number id
                ? id.longValue()
                : 0L;
        if (fromUserId == 0L) {
            return;
        }

        // ADR 0059 precedence: a flow button's callback_data lives in its own
        // cvb: namespace, provably disjoint from every BotActionTokenStore-
        // minted token (see ConversationCallbackToken's own doc) — checked
        // first so a flow tap is never misresolved as an unrecognised order-
        // decision/tenant-select token further down.
        Optional<String> conversationButtonKey = ConversationCallbackToken.unwrap(token);
        if (conversationButtonKey.isPresent()) {
            handleConversationButtonTap(installation, chatId, conversationButtonKey.get());
            return;
        }

        Optional<BotActionTokenStore.TenantSelectToken> picked = actionTokens.resolveTenantSelect(token, fromUserId);
        if (picked.isPresent()) {
            handleTenantSelected(call, chatId, topicId, fromUserId, picked.get());
            return;
        }

        if (messageId == null) {
            return;
        }
        handleOrderDecisionCallback(call, chatId, topicId, messageId, fromUserId, token);
    }

    private void handleOrderDecisionCallback(
            ProviderCall call, long chatId, @Nullable Integer topicId, long messageId, long fromUserId, String token) {
        BotCallbackAuthorizer.Outcome outcome = callbackAuthorizer.decide(token, fromUserId);

        switch (outcome.result()) {
            case APPLIED -> {
                // ADR 0060 §2/§4: "on the first successful decision the
                // inline keyboard is stripped."
                bots.editMessageReplyMarkup(call, chatId, messageId, null);
                var settled =
                        outcome.decision() == null ? null : outcome.decision().settledBy();
                boolean approved = settled != null && "APPROVE".equals(settled.action());
                String actorLabel = settled != null ? settled.actorId() : String.valueOf(outcome.actorSubject());
                bots.sendMessage(
                        call,
                        chatId,
                        topicId,
                        TelegramBotMessages.decisionApplied(defaultLocale, approved, actorLabel));
            }
            case ALREADY_SETTLED -> {
                var settled =
                        outcome.decision() == null ? null : outcome.decision().settledBy();
                String text = settled == null
                        ? TelegramBotMessages.decisionSettledElsewhere(defaultLocale)
                        : TelegramBotMessages.decisionAlreadySettled(
                                defaultLocale, settled.action(), settled.actorId());
                bots.sendMessage(call, chatId, topicId, text);
            }
            case NOT_LINKED ->
                bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionNotLinked(defaultLocale));
            case UNAUTHORIZED ->
                bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionUnauthorized(defaultLocale));
            case TOKEN_EXPIRED ->
                bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionTokenExpired(defaultLocale));
            case NOT_ENTITLED ->
                bots.sendMessage(call, chatId, topicId, TelegramBotMessages.botNotEnabledForTenant(defaultLocale));
        }
    }

    private void handleTenantSelected(
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            long fromUserId,
            BotActionTokenStore.TenantSelectToken picked) {
        Optional<String> principal = staffLinks.principalFor(picked.tenantId(), fromUserId);
        if (principal.isEmpty()) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionNotLinked(defaultLocale));
            return;
        }
        String argument = picked.pendingArgument() == null ? "" : picked.pendingArgument();
        ParsedCommand resumed = new ParsedCommand(picked.pendingCommand(), argument);

        Optional<LocationScope> scope =
                resolveLocationForCommand(picked.tenantId(), principal.get(), resumed.command());
        if (scope.isEmpty()) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.ambiguousLocation(defaultLocale));
            return;
        }
        executeTypedCommand(
                call,
                chatId,
                topicId,
                picked.tenantId(),
                scope.get().brandId(),
                scope.get().locationId(),
                principal.get(),
                resumed);
    }

    // ---------------------------------------------------------- typed commands

    private void handlePrivateMessage(
            WebhookInstallation installation,
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            long fromUserId,
            ParsedCommand parsed) {
        if (LINK_COMMAND.equals(parsed.command())) {
            handleStaffLink(installation, call, chatId, fromUserId, parsed.argument());
            return;
        }
        if (START_COMMAND.equals(parsed.command())) {
            handleCustomerLink(installation, call, chatId, fromUserId, parsed.argument());
            return;
        }
        if (!TYPED_COMMANDS.contains(parsed.command())) {
            return;
        }

        List<TenantLink> tenantLinks = staffLinks.tenantsFor(fromUserId);
        if (tenantLinks.isEmpty()) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.noTenantLinked(defaultLocale));
            return;
        }

        record Candidate(UUID tenantId, String principalSubject, LocationScope scope) {}
        List<Candidate> eligible = tenantLinks.stream()
                .flatMap(link -> resolveLocationForCommand(link.tenantId(), link.principalSubject(), parsed.command())
                        .map(scope -> new Candidate(link.tenantId(), link.principalSubject(), scope))
                        .stream())
                .toList();

        if (eligible.isEmpty()) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.noGrantInAnyLinkedTenant(defaultLocale));
            return;
        }
        if (eligible.size() == 1) {
            Candidate only = eligible.get(0);
            executeTypedCommand(
                    call,
                    chatId,
                    topicId,
                    only.tenantId(),
                    only.scope().brandId(),
                    only.scope().locationId(),
                    only.principalSubject(),
                    parsed);
            return;
        }

        // ADR 0060 §3: "an ambiguous DM command... is answered with a tenant
        // picker unless the principal holds exactly one active grant."
        List<TelegramInlineKeyboard.Button> buttons = eligible.stream()
                .map(candidate -> {
                    String pickToken = actionTokens.mintTenantSelectToken(
                            candidate.tenantId(),
                            fromUserId,
                            parsed.command(),
                            parsed.argument(),
                            clock.instant().plus(TENANT_SELECT_TTL));
                    return new TelegramInlineKeyboard.Button(
                            candidate.tenantId().toString().substring(0, 8), pickToken);
                })
                .toList();
        bots.sendMessage(
                call,
                chatId,
                topicId,
                TelegramBotMessages.tenantPickerPrompt(defaultLocale),
                new TelegramInlineKeyboard(List.of(buttons)));
    }

    private void handleGroupTypedCommand(
            WebhookInstallation installation,
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            long fromUserId,
            ParsedCommand parsed) {
        Optional<TelegramBindingStore.BindingScope> scope =
                bindings.scopeForChat(installation.tenantId(), chatId, topicId);
        if (scope.isEmpty() || scope.get().locationId() == null) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.ambiguousLocation(defaultLocale));
            return;
        }
        Optional<String> principal = staffLinks.principalFor(installation.tenantId(), fromUserId);
        if (principal.isEmpty()) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionNotLinked(defaultLocale));
            return;
        }
        executeTypedCommand(
                call,
                chatId,
                topicId,
                installation.tenantId(),
                scope.get().brandId(),
                scope.get().locationId(),
                principal.get(),
                parsed);
    }

    private void executeTypedCommand(
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String subject,
            ParsedCommand parsed) {
        if (!entitlements.featureEnabled(tenantId, EntitlementKeys.TELEGRAM_BOT_INTERACTIVE_ENABLED)) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.botNotEnabledForTenant(defaultLocale));
            return;
        }
        switch (parsed.command()) {
            case STOP_LIST_COMMAND ->
                executeStopListCommand(
                        call, chatId, topicId, tenantId, brandId, locationId, subject, parsed.argument());
            case STATS_COMMAND -> executeStatsCommand(call, chatId, topicId, tenantId, brandId, locationId, subject);
            default -> {
                /* unreachable: filtered by TYPED_COMMANDS upstream */
            }
        }
    }

    private void executeStopListCommand(
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String subject,
            String argument) {
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        try {
            authorization.require(subject, Capability.INVENTORY_ADJUST, scope);
        } catch (AuthorizationService.AccessDeniedException denied) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionUnauthorized(defaultLocale));
            return;
        }

        if (argument.isBlank()) {
            List<StopListPort.Item> items = stopList.listAtLocation(tenantId, brandId, locationId);
            if (items.isEmpty()) {
                bots.sendMessage(call, chatId, topicId, TelegramBotMessages.stopListEmpty(defaultLocale));
                return;
            }
            StringBuilder text = new StringBuilder();
            for (StopListPort.Item item : items) {
                text.append(TelegramBotMessages.stopListRow(
                                item.available(), shortReference(item.variantId()), item.productName()))
                        .append('\n');
            }
            text.append('\n').append(TelegramBotMessages.stopListUsage(defaultLocale));
            bots.sendMessage(call, chatId, topicId, text.toString().strip());
            return;
        }

        String reference = argument.split("\\s+", 2)[0];
        StopListPort.Item item = resolveItemByReference(tenantId, brandId, locationId, reference);
        if (item == null) {
            bots.sendMessage(
                    call, chatId, topicId, TelegramBotMessages.stopListUnknownReference(defaultLocale, reference));
            return;
        }

        boolean nowAvailable = !item.available();
        try {
            stockAvailability.toggle(
                    tenantId, locationId, item.variantId(), nowAvailable, STOP_LIST_TOGGLE_REASON, subject);
        } catch (IllegalArgumentException | IllegalStateException notToggleable) {
            // Not stocked at all, or QUANTITY-tracked (86 only ever applies to
            // a BINARY item) — the same refusal a bad reference gets, since
            // from the operator's chair both mean "there is nothing here to
            // 86 by that reference."
            bots.sendMessage(
                    call, chatId, topicId, TelegramBotMessages.stopListUnknownReference(defaultLocale, reference));
            return;
        }

        bots.sendMessage(
                call,
                chatId,
                topicId,
                TelegramBotMessages.stopListToggled(defaultLocale, item.productName(), nowAvailable));
    }

    private void executeStatsCommand(
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String subject) {
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        try {
            authorization.require(subject, Capability.ORDER_READ, scope);
        } catch (AuthorizationService.AccessDeniedException denied) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.decisionUnauthorized(defaultLocale));
            return;
        }

        OrderDirectory.Counts counts = orderDirectory.counts(tenantId, brandId, locationId);
        String text = TelegramBotMessages.statsHeader(defaultLocale)
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "new", counts.newOrders())
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "awaiting", counts.awaitingApproval())
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "kitchen", counts.inKitchen())
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "ready", counts.ready())
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "fulfilling", counts.fulfilling())
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "completed", counts.completed())
                + "\n"
                + TelegramBotMessages.statsRow(defaultLocale, "cancelled", counts.cancelled());
        bots.sendMessage(call, chatId, topicId, text);
    }

    /**
     * The one location a principal's grant resolves to for one command's
     * capability, in one tenant — or empty when it is zero or more than one.
     *
     * <p>ADR 0060 §3 names only the tenant picker explicitly; a principal
     * whose grant spans several locations (a brand- or tenant-scoped grant,
     * or more than one explicit location grant) is refused with
     * {@link TelegramBotMessages#ambiguousLocation} rather than guessed at —
     * deliberately narrower than the ADR strictly requires, and named as a
     * scope decision rather than an oversight: the no-POS tenant ADR 0060 §6
     * calls the design center is, in practice, a single location.
     */
    private Optional<LocationScope> resolveLocationForCommand(UUID tenantId, String subject, String command) {
        Capability capability = STOP_LIST_COMMAND.equals(command) ? Capability.INVENTORY_ADJUST : Capability.ORDER_READ;
        CapabilityView view = authorization.viewFor(subject, tenantId);
        List<ResourceScope> locationScopes = view.scopes().stream()
                .filter(grant -> grant.capabilities().contains(capability))
                .map(CapabilityView.ScopeGrant::scope)
                .filter(candidate -> candidate.type() == ResourceScope.ScopeType.LOCATION)
                .distinct()
                .toList();
        if (locationScopes.size() != 1) {
            return Optional.empty();
        }
        ResourceScope only = locationScopes.get(0);
        // ResourceScope's own compact constructor guarantees brandId and
        // locationId are non-null for ScopeType.LOCATION, just filtered for
        // above; NullAway cannot see that guarantee across the stream.
        return Optional.of(new LocationScope(
                java.util.Objects.requireNonNull(only.brandId()), java.util.Objects.requireNonNull(only.locationId())));
    }

    private StopListPort.@Nullable Item resolveItemByReference(
            UUID tenantId, UUID brandId, UUID locationId, String reference) {
        String needle = reference.toLowerCase(Locale.ROOT);
        return stopList.listAtLocation(tenantId, brandId, locationId).stream()
                .filter(item -> shortReference(item.variantId()).equals(needle))
                .findFirst()
                .orElse(null);
    }

    private static String shortReference(UUID id) {
        return id.toString().replace("-", "").substring(0, 8);
    }

    // ------------------------------------------------------------ staff linking

    private void handleStaffLink(
            WebhookInstallation installation, ProviderCall call, long chatId, long fromUserId, String code) {
        if (code.isEmpty()) {
            return;
        }
        Optional<PendingStaffLink> pending = staffLinks.resolve(code);
        if (pending.isEmpty()) {
            bots.sendMessage(call, chatId, null, TelegramBotMessages.staffLinkInvalidOrExpiredCode());
            return;
        }
        PendingStaffLink link = pending.get();
        if (!link.tenantId().equals(installation.tenantId())) {
            // Same refusal ADR 0058's group handshake gives for a cross-
            // tenant code: this webhook's tenant, established by the secret-
            // token check, is the only one it may ever write for.
            log.warn(
                    "Refusing a staff /link code issued for tenant {} against installation {} (tenant {})",
                    link.tenantId(),
                    installation.installationId(),
                    installation.tenantId());
            bots.sendMessage(call, chatId, null, TelegramBotMessages.staffLinkInvalidOrExpiredCode());
            return;
        }

        staffLinks.link(link.tenantId(), link.id(), link.principalSubject(), fromUserId);

        audit.record(AuditFact.of("integration.telegram_staff_link_created", AuditClass.SECURITY)
                .by(ActorRef.user(link.principalSubject(), null))
                .at(ResourceScope.tenant(link.tenantId()))
                .because("Staff Telegram identity-link handshake completed")
                .correlatedBy(link.id().toString())
                .occurredAt(clock.instant())
                .build());

        bots.sendMessage(call, chatId, null, TelegramBotMessages.staffLinked(defaultLocale));
        log.info("Linked Telegram account {} to principal in tenant {} via staff /link", fromUserId, link.tenantId());
    }

    // ------------------------------------------------------ conversations engine

    /**
     * ADR 0059 precedence tier 4: a bare {@code /start} — private chat, no
     * payload — is offered to the flow engine. No-ops (nothing sent, no
     * conversation row created) unless the tenant is entitled and an active
     * welcome flow exists for the resolved brand; either gate failing leaves
     * this exactly the silent no-op {@code handleCustomerLink} always gave a
     * bare {@code /start}.
     */
    private void handleBareStart(WebhookInstallation installation, long chatId) {
        if (!entitlements.featureEnabled(installation.tenantId(), EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED)) {
            return;
        }
        Optional<UUID> brandId = resolveBrandForChat(installation, chatId);
        if (brandId.isEmpty()) {
            return;
        }
        conversations.handleStart(channelRef(installation, brandId.get(), chatId));
    }

    /**
     * ADR 0059 precedence tier 4: ordinary free text in a private chat.
     * No-ops unless entitled, a brand resolves, and that brand has an active
     * flow — {@link ConversationInboundPort#handleText} itself further no-ops
     * unless this exact channel identity has a run actually waiting on text,
     * which is the common case for most free text and is where today's
     * unchanged silence ultimately comes from.
     */
    private void handlePrivateFreeText(WebhookInstallation installation, long chatId, String text) {
        if (!entitlements.featureEnabled(installation.tenantId(), EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED)) {
            return;
        }
        Optional<UUID> brandId = resolveBrandForChat(installation, chatId);
        if (brandId.isEmpty() || !conversations.hasActiveFlow(installation.tenantId(), brandId.get())) {
            return;
        }
        conversations.handleText(channelRef(installation, brandId.get(), chatId), text);
    }

    /**
     * A tap on a flow-rendered button, already unwrapped of {@link
     * ConversationCallbackToken}'s namespace by the caller. Same gates as
     * {@link #handlePrivateFreeText}; a stale tap on a superseded run is a
     * no-op inside the engine itself.
     */
    private void handleConversationButtonTap(WebhookInstallation installation, long chatId, String buttonKey) {
        if (!entitlements.featureEnabled(installation.tenantId(), EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED)) {
            return;
        }
        Optional<UUID> brandId = resolveBrandForChat(installation, chatId);
        if (brandId.isEmpty()) {
            return;
        }
        conversations.handleButtonTap(channelRef(installation, brandId.get(), chatId), buttonKey);
    }

    /**
     * Which brand's flow answers this chat (ADR 0059, V0108): an already-
     * bound chat (a group link, a prior customer {@code /start <code>})
     * resolves from that binding first; a chat that has never bound anything
     * falls back to the installation's own configured brand — ADR 0058's
     * bot-per-brand topology, decided but still not a schema fact end to end.
     */
    private Optional<UUID> resolveBrandForChat(WebhookInstallation installation, long chatId) {
        return bindings.scopeForChat(installation.tenantId(), chatId, null)
                .map(TelegramBindingStore.BindingScope::brandId)
                .or(() -> installationBrands.brandFor(installation.installationId()));
    }

    private ConversationChannelRef channelRef(WebhookInstallation installation, UUID brandId, long chatId) {
        UUID customerAccountId =
                bindings.customerAccountFor(installation.tenantId(), chatId).orElse(null);
        return new ConversationChannelRef(
                installation.tenantId(),
                brandId,
                installation.installationId(),
                ChannelKind.TELEGRAM,
                chatId,
                customerAccountId);
    }

    // ----------------------------------------------------------- customer link

    /**
     * ADR 0058 stage 2: {@code /start <code>} in a private chat, the deep link
     * behind {@code https://t.me/<bot>?start=<code>}. Telegram sends this
     * automatically the moment a customer opens the link, so this is the
     * whole handshake on the bot side — no second message from the customer,
     * unlike staff's typed {@code /link <code>}.
     */
    private void handleCustomerLink(
            WebhookInstallation installation, ProviderCall call, long chatId, long fromUserId, String code) {
        if (code.isEmpty()) {
            // A bare /start, with no payload — somebody opened the bot
            // directly rather than through a storefront deep link. ADR 0059
            // precedence tier 4: this is exactly the case the conversations
            // engine answers, when the tenant is entitled and an active flow
            // exists for the brand; otherwise handleStart no-ops and this
            // stays the silent no-op it always was.
            handleBareStart(installation, chatId);
            return;
        }
        Optional<PendingCustomerLink> pending = customerLinks.resolve(code);
        if (pending.isEmpty()) {
            bots.sendMessage(call, chatId, null, TelegramBotMessages.customerLinkInvalidOrExpiredCode());
            return;
        }
        PendingCustomerLink link = pending.get();
        if (!link.tenantId().equals(installation.tenantId())) {
            // Same refusal every /link-family handshake gives a cross-tenant
            // code: this webhook's tenant, established by the secret-token
            // check, is the only one it may ever write for.
            log.warn(
                    "Refusing a customer /start code issued for tenant {} against installation {} (tenant {})",
                    link.tenantId(),
                    installation.installationId(),
                    installation.tenantId());
            bots.sendMessage(call, chatId, null, TelegramBotMessages.customerLinkInvalidOrExpiredCode());
            return;
        }

        UUID bindingId = customerLinks.link(
                link.tenantId(),
                installation.installationId(),
                link.brandId(),
                link.customerAccountId(),
                chatId,
                fromUserId,
                clock.instant());
        customerLinks.consume(link.tenantId(), link.id(), bindingId);

        String locale = contacts.preferredLocale(link.tenantId(), link.customerAccountId())
                .orElse(defaultLocale);
        bots.sendMessage(call, chatId, null, TelegramBotMessages.customerLinked(locale));
        log.info(
                "Linked Telegram chat {} to customer account {} in tenant {} via /start",
                chatId,
                link.customerAccountId(),
                link.tenantId());
    }

    // -------------------------------------------------------------- group link

    private void handleGroupLink(
            WebhookInstallation installation,
            ProviderCall call,
            long chatId,
            @Nullable Integer topicId,
            long fromUserId,
            String code) {
        if (code.isEmpty()) {
            return;
        }
        PendingLink pending = links.resolve(code).orElse(null);
        if (pending == null) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.invalidOrExpiredCode());
            return;
        }
        if (!pending.tenantId().equals(installation.tenantId())) {
            log.warn(
                    "Refusing a /link code issued for tenant {} against installation {} (tenant {})",
                    pending.tenantId(),
                    installation.installationId(),
                    installation.tenantId());
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.crossTenantRefused());
            return;
        }

        var verification = rights.verify(call, chatId, topicId != null);
        // Checking the reason directly (rather than !verification.sufficient())
        // is what lets the compiler carry the non-null fact into the message
        // below; Verification guarantees the two travel together (see
        // TelegramRightsVerifier.Verification.ok()/failed()).
        String insufficientReason = verification.actionableReason();
        if (insufficientReason != null) {
            bots.sendMessage(
                    call, chatId, topicId, TelegramBotMessages.insufficientRights(defaultLocale, insufficientReason));
            return;
        }

        UUID bindingId = bindings.createBinding(
                pending.tenantId(),
                installation.installationId(),
                pending.brandId(),
                pending.locationId(),
                chatId,
                topicId,
                fromUserId == 0L ? null : fromUserId);
        bindings.subscribe(pending.tenantId(), bindingId, DEFAULT_SUBSCRIPTIONS);
        links.consume(pending.tenantId(), pending.id(), bindingId);

        // ADR 0026: binding activation is an ADR 0027 audit fact. The actor is
        // the operator who requested the code, not the bot or the Telegram
        // user who typed the command — a Telegram user id is not a Keycloak
        // identity, and the accountable party is whoever the platform
        // actually authenticated and authorized to create this access.
        audit.record(AuditFact.of("integration.telegram_binding_created", AuditClass.SECURITY)
                .by(ActorRef.user(pending.requestedByPrincipalId(), null))
                .at(ResourceScope.tenant(pending.tenantId()))
                .target("IntegrationBinding", bindingId)
                .because("Telegram group-link handshake completed")
                .correlatedBy(bindingId.toString())
                .occurredAt(clock.instant())
                .build());

        bots.sendMessage(call, chatId, topicId, TelegramBotMessages.linked(defaultLocale));
        log.info(
                "Linked Telegram chat {} (topic {}) as binding {} for tenant {}",
                chatId,
                topicId,
                bindingId,
                pending.tenantId());
    }

    // ----------------------------------------------------------------- parsing

    /**
     * The command word and argument from a {@code /command[@botname] arg...}
     * message, or null when {@code text} is not a command at all.
     *
     * <p>Handles the {@code /command@botname} shape Telegram sends group
     * commands as when BotFather privacy mode is disabled and several bots
     * are present, by comparing only the part of the first token before any
     * {@code @}.
     */
    private static @Nullable ParsedCommand parseCommand(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("/")) {
            return null;
        }
        int firstSpace = trimmed.indexOf(' ');
        String commandToken = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
        int at = commandToken.indexOf('@');
        String command = (at < 0 ? commandToken : commandToken.substring(0, at))
                .substring(1)
                .toLowerCase(Locale.ROOT);
        if (command.isEmpty()) {
            return null;
        }
        String argument =
                firstSpace < 0 ? "" : trimmed.substring(firstSpace + 1).strip();
        return new ParsedCommand(command, argument);
    }

    private static long fromUserId(Map<String, Object> message) {
        return message.get("from") instanceof Map<?, ?> from && from.get("id") instanceof Number id
                ? id.longValue()
                : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private ProviderCall resolveCall(WebhookInstallation installation) {
        SecretReference reference = SecretReference.parse(installation.secretReference());
        return new ProviderCall(
                installation.baseUrl(), secrets.resolve(reference).reveal(), null, Duration.ofSeconds(15));
    }

    private record ParsedCommand(String command, String argument) {}

    private record LocationScope(UUID brandId, UUID locationId) {}
}
