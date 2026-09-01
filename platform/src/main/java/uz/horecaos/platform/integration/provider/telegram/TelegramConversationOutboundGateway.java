package uz.horecaos.platform.integration.provider.telegram;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.conversations.api.ConversationCallbackToken;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationOutboundGateway;
import uz.horecaos.platform.conversations.api.OutboundButtonKind;
import uz.horecaos.platform.conversations.api.OutboundMessage;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;

/**
 * The ADR 0059 outbound seam, implemented on the Telegram side. Deliberately
 * calls {@link TelegramBotApiClient} directly rather than routing through the
 * generic {@code NotificationDispatch}/{@code TelegramChannelAdapter}
 * pipeline (ADR 0020's async retry/attempt machinery for a rendered,
 * templated message with at most one hardcoded keyboard shape) — a flow turn
 * needs to render an arbitrary, flow-authored button set synchronously,
 * inside the same webhook handling this class is called from, which that
 * pipeline was never built for. What it does reuse from that pipeline's own
 * neighbourhood: the same {@link TelegramBotApiClient} HTTP boundary and the
 * same {@link TelegramChatLockService} per-chat ordering lease every other
 * Telegram send already goes through — so a flow reply can never interleave
 * with a digest or an order notification landing in the same chat at the same
 * moment. No new Bot API caller, no new secret handling: this class is a
 * second caller of existing machinery, not a second path to Telegram.
 *
 * <p>Never throws. A failed send is logged and the engine has already moved
 * on — see {@link ConversationOutboundGateway}'s own doc on that accepted
 * trade-off.
 */
@Component
public class TelegramConversationOutboundGateway implements ConversationOutboundGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramConversationOutboundGateway.class);

    private final TelegramWebhookInstallationLookup installations;
    private final TelegramChatLockService locks;
    private final TelegramBotApiClient bots;
    private final SecretResolver secrets;
    private final Duration chatLeaseDuration;

    public TelegramConversationOutboundGateway(
            TelegramWebhookInstallationLookup installations,
            TelegramChatLockService locks,
            TelegramBotApiClient bots,
            SecretResolver secrets,
            @Value("${horecaos.notifications.telegram.chat-lease:PT20S}") Duration chatLeaseDuration) {
        this.installations = installations;
        this.locks = locks;
        this.bots = bots;
        this.secrets = secrets;
        this.chatLeaseDuration = chatLeaseDuration;
    }

    @Override
    public boolean send(ConversationChannelRef channel, OutboundMessage message) {
        Optional<WebhookInstallation> installation = installations.find(channel.installationId());
        if (installation.isEmpty()) {
            log.error(
                    "Conversation outbound send for installation {} found no such installation",
                    channel.installationId());
            return false;
        }

        UUID leaseOwner = UUID.randomUUID();
        if (!locks.tryAcquire(channel.tenantId(), channel.externalChatId(), leaseOwner, chatLeaseDuration)) {
            log.warn("Chat {} is busy; a flow send was skipped rather than interleaved", channel.externalChatId());
            return false;
        }
        try {
            ProviderCall call = resolveCall(installation.get());
            TelegramInlineKeyboard keyboard = keyboardFor(message);
            TelegramCallResult result =
                    bots.sendMessage(call, channel.externalChatId(), null, message.text(), keyboard);
            return switch (result) {
                case TelegramCallResult.Success ignored -> true;
                case TelegramCallResult.BindingRetirement retirement -> {
                    log.info(
                            "Conversation send to chat {} was refused ({}): a flow will not retry it",
                            channel.externalChatId(),
                            retirement.reason());
                    yield false;
                }
                case TelegramCallResult.BusinessRejected rejected -> {
                    log.warn(
                            "Conversation send to chat {} was rejected ({})",
                            channel.externalChatId(),
                            rejected.errorCode());
                    yield false;
                }
                case TelegramCallResult.Retryable retryable -> {
                    log.warn(
                            "Conversation send to chat {} was retryable ({}); a flow will not retry it",
                            channel.externalChatId(),
                            retryable.errorCode());
                    yield false;
                }
                case TelegramCallResult.Uncertain uncertain -> {
                    log.warn(
                            "Conversation send to chat {} was uncertain ({})",
                            channel.externalChatId(),
                            uncertain.errorCode());
                    yield false;
                }
                case TelegramCallResult.ChatMigrated ignored -> {
                    // A flow-created chat is always private (a customer's own
                    // 1:1 chat); private chats never migrate — this branch is
                    // unreachable in practice and refused rather than
                    // followed, since following it would need the same
                    // rewrite-and-retry TelegramChannelAdapter does for a
                    // binding this class has none of.
                    log.error(
                            "Conversation send to chat {} reported migrate_to_chat_id unexpectedly",
                            channel.externalChatId());
                    yield false;
                }
            };
        } finally {
            locks.release(channel.tenantId(), channel.externalChatId(), leaseOwner);
        }
    }

    private static @Nullable TelegramInlineKeyboard keyboardFor(OutboundMessage message) {
        if (message.buttons().isEmpty()) {
            return null;
        }
        List<TelegramInlineKeyboard.Button> row = message.buttons().stream()
                .map(button -> button.kind() == OutboundButtonKind.URL
                        ? TelegramInlineKeyboard.Button.url(button.label(), button.value())
                        : new TelegramInlineKeyboard.Button(
                                button.label(), ConversationCallbackToken.wrap(button.value())))
                .toList();
        return new TelegramInlineKeyboard(List.of(row));
    }

    private ProviderCall resolveCall(WebhookInstallation installation) {
        SecretReference reference = SecretReference.parse(installation.secretReference());
        return new ProviderCall(
                installation.baseUrl(), secrets.resolve(reference).reveal(), null, Duration.ofSeconds(15));
    }
}
