package uz.qoida.platform.integration.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The tenancy listener (ADR 0005).
 *
 * <p>Acknowledgement is manual and deliberate. The offset advances only when the
 * inbox reports an outcome that means the platform has taken durable
 * responsibility for the record; a retry-scheduled outcome leaves the offset
 * where it is so the record comes back. {@link InboxRecordDispatch} holds that
 * decision, so it cannot differ between this listener and the next one.
 */
@Component
@ConditionalOnProperty(name = "qoida.messaging.inbox.listener.enabled", havingValue = "true", matchIfMissing = true)
public class TenancyEventListener {

    private final InboxRecordDispatch dispatch;

    public TenancyEventListener(InboxRecordDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @KafkaListener(
            topics = "${qoida.messaging.topics.tenancy-events:tenancy.events}",
            groupId = "${qoida.messaging.inbox.group-id:qoida-platform}",
            containerFactory = "inboxListenerContainerFactory")
    public void onRecord(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        if (dispatch.offer(record)) {
            acknowledgment.acknowledge();
        }
    }
}
