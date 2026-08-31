package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
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
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.provider.telegram.TelegramLinkService.PendingLink;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;

/**
 * Everything an authenticated Telegram update may cause, this rollout stage:
 * exactly one command, {@code /link <code>} (ADR 0058). Every other update shape
 * — a plain message with no command, an edited message, a callback query, a
 * channel post — is read and ignored. There are no interactive elements in this
 * slice (ADR 0060 is a separate, later record) and no reply is owed to a message
 * that was not a command this bot understands.
 */
@Service
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    private static final String LINK_PREFIX = "/link";

    /** Every event class an operations chat is subscribed to by default (ADR 0058 stage 1). */
    private static final Set<String> DEFAULT_SUBSCRIPTIONS =
            Set.of("ORDER_CONFIRMED", "ORDER_REJECTED", "ORDER_APPROVAL_DEADLINE_WARNING");

    private final TelegramLinkService links;
    private final TelegramRightsVerifier rights;
    private final TelegramBindingStore bindings;
    private final TelegramBotApiClient bots;
    private final SecretResolver secrets;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String defaultLocale;

    public TelegramUpdateHandler(
            TelegramLinkService links,
            TelegramRightsVerifier rights,
            TelegramBindingStore bindings,
            TelegramBotApiClient bots,
            SecretResolver secrets,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.notifications.telegram.group-locale:ru}") String defaultLocale) {
        this.links = links;
        this.rights = rights;
        this.bindings = bindings;
        this.bots = bots;
        this.secrets = secrets;
        this.audit = audit;
        this.clock = clock;
        this.defaultLocale = defaultLocale;
    }

    /**
     * @param installation already authenticated by the controller's secret-token
     *                     check; nothing here re-verifies it
     * @param update the parsed Bot API {@code Update} object
     */
    public void handle(WebhookInstallation installation, Map<String, Object> update) {
        Object messageObject = update.get("message");
        if (!(messageObject instanceof Map<?, ?> rawMessage)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) rawMessage;

        Object textObject = message.get("text");
        if (!(textObject instanceof String text)) {
            return;
        }
        String code = linkCode(text);
        if (code == null) {
            return;
        }

        ProviderCall call = resolveCall(installation);
        Object chatObject = message.get("chat");
        if (!(chatObject instanceof Map<?, ?> chat)) {
            return;
        }
        if (!(chat.get("id") instanceof Number chatIdNumber)) {
            return;
        }
        long chatId = chatIdNumber.longValue();
        String chatType = String.valueOf(chat.get("type"));
        if (!("group".equals(chatType) || "supergroup".equals(chatType))) {
            bots.sendMessage(call, chatId, null, TelegramBotMessages.notAGroup(defaultLocale));
            return;
        }

        Integer topicId = message.get("message_thread_id") instanceof Number threadId ? threadId.intValue() : null;
        long fromUserId = message.get("from") instanceof Map<?, ?> from && from.get("id") instanceof Number id
                ? id.longValue()
                : 0L;

        PendingLink pending = links.resolve(code).orElse(null);
        if (pending == null) {
            bots.sendMessage(call, chatId, topicId, TelegramBotMessages.invalidOrExpiredCode());
            return;
        }
        if (!pending.tenantId().equals(installation.tenantId())) {
            // A code issued against one tenant's installation must never bind a
            // chat into a different tenant's operations feed. The webhook's own
            // tenant (established by the secret-token check, not by anything in
            // the update) is the only tenant this call is allowed to write for.
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
        // the operator who requested the code, not the bot or the Telegram user
        // who typed the command — a Telegram user id is not a Keycloak identity,
        // and the accountable party is whoever the platform actually
        // authenticated and authorized to create this access.
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

    /**
     * The code from a {@code /link <code>} command, or null if this message is
     * not one.
     *
     * <p>Handles the {@code /link@botname <code>} shape Telegram sends group
     * commands as when BotFather privacy mode is disabled and several bots are
     * present, by comparing only the part of the first token before any
     * {@code @}.
     */
    private static @Nullable String linkCode(String text) {
        String trimmed = text.strip();
        int firstSpace = trimmed.indexOf(' ');
        String commandToken = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
        int at = commandToken.indexOf('@');
        String command = at < 0 ? commandToken : commandToken.substring(0, at);
        if (!LINK_PREFIX.equalsIgnoreCase(command)) {
            return null;
        }
        String code = firstSpace < 0 ? "" : trimmed.substring(firstSpace + 1).strip();
        return code.isEmpty() ? null : code;
    }

    private ProviderCall resolveCall(WebhookInstallation installation) {
        SecretReference reference = SecretReference.parse(installation.secretReference());
        return new ProviderCall(
                installation.baseUrl(), secrets.resolve(reference).reveal(), null, Duration.ofSeconds(15));
    }
}
