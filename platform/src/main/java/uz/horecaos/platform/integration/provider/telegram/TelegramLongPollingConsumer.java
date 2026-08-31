package uz.horecaos.platform.integration.provider.telegram;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;

/**
 * The {@code local}-profile ingress path (ADR 0058): "no public URL exists in
 * the dev loop", so a developer's laptop polls the Bot API instead of Telegram
 * calling a webhook it cannot reach.
 *
 * <p>Every update this consumer sees is fed through the exact same
 * {@link TelegramUpdateHandler} the webhook controller uses, so the linking
 * handshake behaves identically in both environments; the only thing that
 * differs is how an update arrives. Mirrors {@code FakeClickProviderConfiguration}'s
 * layered guard: the {@link Profile} annotation so the bean does not exist
 * outside {@code local}, and the {@link ConditionalOnProperty} so it can still
 * be switched off within {@code local}.
 */
@Component
@Profile("local")
@ConditionalOnProperty(
        name = "horecaos.notifications.telegram.long-polling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TelegramLongPollingConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramLongPollingConsumer.class);

    private final TelegramWebhookInstallationLookup installations;
    private final TelegramBotApiClient bots;
    private final TelegramUpdateHandler handler;
    private final SecretResolver secrets;
    private final int pollTimeoutSeconds;
    private final Map<UUID, Long> offsets = new ConcurrentHashMap<>();

    public TelegramLongPollingConsumer(
            TelegramWebhookInstallationLookup installations,
            TelegramBotApiClient bots,
            TelegramUpdateHandler handler,
            SecretResolver secrets,
            @Value("${horecaos.notifications.telegram.long-polling.poll-timeout-seconds:5}") int pollTimeoutSeconds) {
        this.installations = installations;
        this.bots = bots;
        this.handler = handler;
        this.secrets = secrets;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.telegram.long-polling.initial-delay:PT5S}",
            fixedDelayString = "${horecaos.notifications.telegram.long-polling.interval:PT1S}")
    public void pollOnce() {
        for (WebhookInstallation installation : installations.listActive("TELEGRAM_BOT_API")) {
            try {
                poll(installation);
            } catch (RuntimeException failure) {
                log.warn("Telegram long-poll failed for installation {}", installation.installationId(), failure);
            }
        }
    }

    private void poll(WebhookInstallation installation) {
        ProviderCall call = new ProviderCall(
                installation.baseUrl(),
                secrets.resolve(SecretReference.parse(installation.secretReference()))
                        .reveal(),
                null,
                Duration.ofSeconds(pollTimeoutSeconds + 10L));

        long offset = offsets.getOrDefault(installation.installationId(), 0L);
        List<Map<String, Object>> updates = bots.getUpdates(call, offset, pollTimeoutSeconds);

        for (Map<String, Object> update : updates) {
            if (update.get("update_id") instanceof Number updateId) {
                offsets.put(installation.installationId(), updateId.longValue() + 1);
            }
            handler.handle(installation, update);
        }
    }
}
