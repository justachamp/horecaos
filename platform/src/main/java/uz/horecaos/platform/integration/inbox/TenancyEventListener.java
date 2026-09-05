package uz.horecaos.platform.integration.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.ConditionalOnWorkerRole;

/**
 * The tenancy listener (ADR 0005).
 *
 * <p>Acknowledgement is manual and deliberate. The offset advances only when the
 * inbox reports an outcome that means the platform has taken durable
 * responsibility for the record; a retry-scheduled outcome leaves the offset
 * where it is so the record comes back. {@link InboxRecordDispatch} holds that
 * decision, so it cannot differ between this listener and the next one.
 *
 * <p>ADR 0023 names this switch as one of the four the {@code app}/{@code worker}
 * split "no longer covers": it guards a {@code @KafkaListener}, not a {@code
 * @Scheduled} method, so {@code SchedulingConfiguration}'s blanket role gate never
 * touched it. {@link ConditionalOnWorkerRole} closes that gap directly: this consumer
 * now requires both the operational switch below and a role that runs worker work.
 */
@Component
@ConditionalOnProperty(name = "horecaos.messaging.inbox.listener.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWorkerRole
public class TenancyEventListener {

    private final InboxRecordDispatch dispatch;

    public TenancyEventListener(InboxRecordDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @KafkaListener(
            topics = "${horecaos.messaging.topics.tenancy-events:tenancy.events}",
            groupId = "${horecaos.messaging.inbox.group-id:horecaos-platform}",
            containerFactory = "inboxListenerContainerFactory")
    public void onRecord(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        if (dispatch.offer(record)) {
            acknowledgment.acknowledge();
        }
    }
}
