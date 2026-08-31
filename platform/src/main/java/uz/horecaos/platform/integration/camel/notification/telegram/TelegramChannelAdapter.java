package uz.horecaos.platform.integration.camel.notification.telegram;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore.ChatRef;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramCallResult;
import uz.horecaos.platform.integration.provider.telegram.TelegramChatLockService;
import uz.horecaos.platform.integration.provider.telegram.TelegramMessageTracker;
import uz.horecaos.platform.integration.provider.telegram.TelegramMessageTracker.Tracked;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * The Telegram notification channel adapter (ADR 0058), on the ADR 0007 route
 * exactly where {@code SmsGatewayAdapter} lives for SMS.
 *
 * <p>{@link #send} does four things in order, each a named ADR 0058 mechanism:
 * resolve the exact binding the notification already names (never "the primary
 * chat" — see {@code TelegramBindingStore}'s note on fan-out), acquire the
 * per-chat lease so at most one replica is mid-call to this chat, decide
 * edit-versus-send from {@link TelegramMessageTracker}, and translate whatever
 * Telegram answered — including retiring or rewriting the binding — before
 * releasing the lease.
 *
 * <p>{@link #queryStatus} always answers uncertain. The Bot API has no request
 * idempotency and no "did you receive this key" query, unlike the SMS gateway's
 * {@code /provider/commands/{key}}: a lost reply here can only be resolved by a
 * human looking at the chat, which is what an exhausted attempt budget
 * eventually asks {@code MANUAL_REVIEW} for. Pretending otherwise — guessing
 * {@code RETRYABLE} — is exactly the mistake that sends an operations group the
 * same order confirmation twice.
 */
@Component
public class TelegramChannelAdapter implements NotificationChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelAdapter.class);

    private final TelegramBotApiClient bots;
    private final TelegramBindingStore bindings;
    private final TelegramChatLockService locks;
    private final TelegramMessageTracker tracker;
    private final TelegramCircuitBreakers breakers;
    private final Duration chatLeaseDuration;

    public TelegramChannelAdapter(
            TelegramBotApiClient bots,
            TelegramBindingStore bindings,
            TelegramChatLockService locks,
            TelegramMessageTracker tracker,
            TelegramCircuitBreakers breakers,
            @Value("${horecaos.notifications.telegram.chat-lease:PT20S}") Duration chatLeaseDuration) {
        this.bots = bots;
        this.bindings = bindings;
        this.locks = locks;
        this.tracker = tracker;
        this.breakers = breakers;
        this.chatLeaseDuration = chatLeaseDuration;
    }

    @Override
    public String providerType() {
        return TelegramCircuitBreakers.PROVIDER_TYPE;
    }

    @Override
    public String channel() {
        return "TELEGRAM";
    }

    @Override
    public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call) {
        UUID bindingId;
        try {
            bindingId = UUID.fromString(dispatch.recipientValue());
        } catch (IllegalArgumentException malformed) {
            return ProviderOutcome.rejected("MALFORMED_BINDING_REFERENCE", "recipientValue was not a binding id");
        }

        Optional<ChatRef> chatRef = bindings.chatFor(dispatch.tenantId(), bindingId);
        if (chatRef.isEmpty()) {
            // Retired (or never existed) since eligibility resolved this
            // endpoint. Terminal: nothing about retrying reaches a chat that no
            // longer has a live binding.
            return ProviderOutcome.rejected("BINDING_RETIRED", "This Telegram binding is no longer active");
        }

        UUID leaseOwner = UUID.randomUUID();
        if (!locks.tryAcquire(dispatch.tenantId(), chatRef.get().chatId(), leaseOwner, chatLeaseDuration)) {
            // ADR 0058's ordering boundary, enforced here rather than guessed:
            // another send to this exact chat is in flight right now, on this
            // replica or another one. A short retry, not a provider failure.
            return ProviderOutcome.retryable(
                    "CHAT_BUSY", "Another send to this chat is already in flight", Duration.ofSeconds(2));
        }
        try {
            return withCircuitBreaker(dispatch, call, bindingId, chatRef.get());
        } finally {
            locks.release(dispatch.tenantId(), chatRef.get().chatId(), leaseOwner);
        }
    }

    @Override
    public ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call) {
        return ProviderOutcome.uncertain(
                "NO_RECONCILIATION_AVAILABLE",
                "The Bot API has no idempotency key and no status query; an uncertain Telegram send can "
                        + "only be resolved by a person reading the chat");
    }

    private ProviderOutcome withCircuitBreaker(
            NotificationDispatch dispatch, ProviderCall call, UUID bindingId, ChatRef chat) {
        try {
            return breakers.forBotApi().executeSupplier(() -> {
                ProviderOutcome outcome = attempt(dispatch, call, bindingId, chat);
                if (outcome.status() == ProviderOutcome.Status.RETRYABLE
                        || outcome.status() == ProviderOutcome.Status.UNCERTAIN) {
                    throw new TelegramCircuitBreakers.ProviderCallFailed(outcome);
                }
                return outcome;
            });
        } catch (CallNotPermittedException circuitOpen) {
            log.warn("Telegram circuit open; send to binding {} not attempted", bindingId);
            return ProviderOutcome.retryable("CIRCUIT_OPEN", "The Telegram breaker is open", Duration.ofSeconds(30));
        } catch (TelegramCircuitBreakers.ProviderCallFailed failed) {
            return failed.outcome();
        }
    }

    /** One logical send: edit-if-live, else send-new, with one migrate_to_chat_id replay either way. */
    private ProviderOutcome attempt(NotificationDispatch dispatch, ProviderCall call, UUID bindingId, ChatRef chat) {
        String contentHash = TelegramContentHash.of(dispatch.body());

        if (dispatch.subjectType() != null && dispatch.subjectId() != null && dispatch.templateKey() != null) {
            Optional<Tracked> tracked = tracker.current(
                    dispatch.tenantId(),
                    bindingId,
                    dispatch.subjectType(),
                    dispatch.subjectId(),
                    dispatch.templateKey());
            if (tracked.isPresent()) {
                if (tracked.get().contentHash().equals(contentHash)) {
                    // Already showing this exact content; nothing to send.
                    return ProviderOutcome.success(
                            Map.of("providerStatus", "UNCHANGED"),
                            String.valueOf(tracked.get().telegramMessageId()));
                }
                Optional<ProviderOutcome> edited = tryEdit(dispatch, call, bindingId, chat, tracked.get(), contentHash);
                if (edited.isPresent()) {
                    return edited.get();
                }
                // Edit failed for a reason that means the tracked message itself
                // is gone (expired window already excluded it from "current";
                // this is the live-but-refused case: deleted, unchanged per
                // Telegram's own comparison, or otherwise un-editable). Fall
                // through to send-new and re-track, exactly as ADR 0058 states.
            }
        }

        return sendNew(dispatch, call, bindingId, chat, contentHash);
    }

    /**
     * @return the outcome if the edit concluded the attempt (success, a fault to
     *         retry/reconcile, or a binding retirement); empty when the caller
     *         should fall through to sending a new message
     */
    private Optional<ProviderOutcome> tryEdit(
            NotificationDispatch dispatch,
            ProviderCall call,
            UUID bindingId,
            ChatRef chat,
            Tracked tracked,
            String contentHash) {

        TelegramCallResult result =
                bots.editMessageText(call, chat.chatId(), tracked.telegramMessageId(), dispatch.body());

        if (result instanceof TelegramCallResult.ChatMigrated migrated) {
            ChatRef rewritten = rewriteAndFollow(dispatch.tenantId(), bindingId, chat, migrated.newChatId());
            result = bots.editMessageText(call, rewritten.chatId(), tracked.telegramMessageId(), dispatch.body());
        }

        return switch (result) {
            case TelegramCallResult.Success success -> {
                tracker.recordEdited(dispatch.tenantId(), tracked.id(), contentHash);
                yield Optional.of(ProviderOutcome.success(
                        Map.of("providerStatus", "EDITED"), String.valueOf(tracked.telegramMessageId())));
            }
            case TelegramCallResult.BindingRetirement retirement -> {
                bindings.retire(dispatch.tenantId(), bindingId, retirement.reason());
                tracker.supersede(dispatch.tenantId(), tracked.id());
                yield Optional.of(
                        ProviderOutcome.rejected("BINDING_RETIRED_" + retirement.reason(), retirement.detail()));
            }
            case TelegramCallResult.Retryable retryable ->
                Optional.of(
                        ProviderOutcome.retryable(retryable.errorCode(), retryable.detail(), retryable.retryAfter()));
            case TelegramCallResult.Uncertain uncertain ->
                Optional.of(ProviderOutcome.uncertain(uncertain.errorCode(), uncertain.detail()));
            case TelegramCallResult.BusinessRejected rejected -> {
                // Expired, deleted, or "message is not modified" by Telegram's own
                // comparison. The message is gone from under this tracked row;
                // superseding it is what lets the next update send fresh instead
                // of retrying an edit that will never succeed.
                tracker.supersede(dispatch.tenantId(), tracked.id());
                yield Optional.empty();
            }
            case TelegramCallResult.ChatMigrated migratedAgain ->
                // A second migrate answer on the replay is not expected; treat
                // it as uncertain rather than looping.
                Optional.of(ProviderOutcome.uncertain(
                        "REPEATED_CHAT_MIGRATION", "Telegram reported migrate_to_chat_id twice"));
        };
    }

    private ProviderOutcome sendNew(
            NotificationDispatch dispatch, ProviderCall call, UUID bindingId, ChatRef chat, String contentHash) {

        TelegramCallResult result = bots.sendMessage(call, chat.chatId(), chat.topicId(), dispatch.body());

        if (result instanceof TelegramCallResult.ChatMigrated migrated) {
            ChatRef rewritten = rewriteAndFollow(dispatch.tenantId(), bindingId, chat, migrated.newChatId());
            result = bots.sendMessage(call, rewritten.chatId(), rewritten.topicId(), dispatch.body());
        }

        return switch (result) {
            case TelegramCallResult.Success success -> {
                Object messageIdValue = success.result().get("message_id");
                if (!(messageIdValue instanceof Number messageIdNumber)) {
                    // sendMessage's "result" is a Telegram Message object, which
                    // always carries message_id; if it is ever absent the send
                    // still happened (Telegram answered ok:true) but nothing here
                    // can track it for a future edit or content-hash dedupe.
                    // Uncertain, not a crash: the provider accepted the call.
                    log.error(
                            "Telegram sendMessage for binding {} succeeded without a message_id in the response",
                            bindingId);
                    yield ProviderOutcome.uncertain(
                            "MISSING_MESSAGE_ID", "Telegram accepted the message but the response had no message_id");
                }
                long messageId = messageIdNumber.longValue();
                if (dispatch.subjectType() != null && dispatch.subjectId() != null && dispatch.templateKey() != null) {
                    tracker.recordSent(
                            dispatch.tenantId(),
                            bindingId,
                            dispatch.subjectType(),
                            dispatch.subjectId(),
                            dispatch.templateKey(),
                            messageId,
                            contentHash);
                }
                yield ProviderOutcome.success(Map.of("providerStatus", "SENT"), String.valueOf(messageId));
            }
            case TelegramCallResult.BindingRetirement retirement -> {
                bindings.retire(dispatch.tenantId(), bindingId, retirement.reason());
                yield ProviderOutcome.rejected("BINDING_RETIRED_" + retirement.reason(), retirement.detail());
            }
            case TelegramCallResult.BusinessRejected rejected ->
                ProviderOutcome.rejected(rejected.errorCode(), rejected.detail());
            case TelegramCallResult.Retryable retryable ->
                ProviderOutcome.retryable(retryable.errorCode(), retryable.detail(), retryable.retryAfter());
            case TelegramCallResult.Uncertain uncertain ->
                ProviderOutcome.uncertain(uncertain.errorCode(), uncertain.detail());
            case TelegramCallResult.ChatMigrated migratedAgain ->
                ProviderOutcome.uncertain("REPEATED_CHAT_MIGRATION", "Telegram reported migrate_to_chat_id twice");
        };
    }

    private ChatRef rewriteAndFollow(UUID tenantId, UUID bindingId, ChatRef previous, long newChatId) {
        bindings.rewriteChatId(tenantId, bindingId, newChatId);
        log.info("Telegram binding {} migrated from chat {} to {}", bindingId, previous.chatId(), newChatId);
        return new ChatRef(bindingId, newChatId, previous.topicId());
    }
}
