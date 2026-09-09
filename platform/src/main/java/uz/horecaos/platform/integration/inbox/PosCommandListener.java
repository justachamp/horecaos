package uz.horecaos.platform.integration.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.ConditionalOnWorkerRole;

/**
 * ADR 0012's production entry point for {@code PosSyncRequested}.
 *
 * <p>Its own listener rather than a second consumer on {@code
 * fulfillment.commands}, for the same reason {@link FulfillmentCommandListener}
 * gives for not sharing {@code tenancy.events}: a command topic's lag means
 * work not yet started, and mixing two providers' command classes on one
 * consumer group would make that figure mean two different things depending on
 * which command happened to be behind.
 *
 * <p>Carries {@link ConditionalOnWorkerRole} for the reason {@code
 * TenancyEventListener} and {@code FulfillmentCommandListener} both do: ADR
 * 0023's {@code horecaos.messaging.inbox.listener.enabled} switch is not
 * covered by the {@code app}/{@code worker} role split because it guards a
 * {@code @KafkaListener} rather than a {@code @Scheduled} method, so this
 * annotation is what keeps a strict {@code app} container from also consuming
 * sync commands.
 */
@Component
@ConditionalOnProperty(name = "horecaos.messaging.inbox.listener.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWorkerRole
public class PosCommandListener {

    private final InboxRecordDispatch dispatch;

    public PosCommandListener(InboxRecordDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @KafkaListener(
            topics = "${horecaos.messaging.topics.pos-commands:pos.commands}",
            groupId = "${horecaos.messaging.inbox.group-id:horecaos-platform}",
            containerFactory = "inboxListenerContainerFactory")
    public void onRecord(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        // The offset moves only after PosCatalogSyncService.run and the inbox
        // transition have committed. Acknowledging first could lose a command
        // whose run may already have started against the provider.
        if (dispatch.offer(record)) {
            acknowledgment.acknowledge();
        }
    }
}
