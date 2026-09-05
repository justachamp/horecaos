package uz.horecaos.platform.integration.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.ConditionalOnWorkerRole;

/**
 * ADR 0007's production command entry point.
 *
 * <p>Until this existed, every route in the build was called in-process and
 * returned its outcome to whoever was holding the thread, and rule 9 — persist
 * the outcome and the inbox state before acknowledging the command — was proven
 * only by a route in test sources. This is the same shape in production: a
 * versioned command arrives here, the ADR 0005 inbox deduplicates it, an ADR
 * 0007 route performs the provider call, and the canonical result is written to
 * the ADR 0004 outbox in the transaction that also marks the record processed.
 *
 * <p>Its own listener rather than a second topic on the tenancy one, because the
 * two carry different classes of record. A command topic keeps hours of
 * retention where a fact topic keeps days, and a consumer group that mixed them
 * would make one lag figure that means nothing: a backlog of commands is work
 * not being done, while a backlog of facts is a projection being behind.
 *
 * <p>Carries {@link ConditionalOnWorkerRole} alongside its existing switch for the same
 * reason {@code TenancyEventListener} does: ADR 0023 names {@code
 * horecaos.messaging.inbox.listener.enabled} as a switch the {@code app}/{@code worker}
 * role split does not cover, because it guards a {@code @KafkaListener} rather than a
 * {@code @Scheduled} method.
 */
@Component
@ConditionalOnProperty(name = "horecaos.messaging.inbox.listener.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWorkerRole
public class FulfillmentCommandListener {

    private final InboxRecordDispatch dispatch;

    public FulfillmentCommandListener(InboxRecordDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @KafkaListener(
            topics = "${horecaos.messaging.topics.fulfillment-commands:fulfillment.commands}",
            groupId = "${horecaos.messaging.inbox.group-id:horecaos-platform}",
            containerFactory = "inboxListenerContainerFactory")
    public void onRecord(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        // The offset moves only after the route's outcome and the inbox
        // transition have committed. Acknowledging first would lose a command
        // whose provider call may already have happened, which is the one thing
        // a courier integration must never do.
        if (dispatch.offer(record)) {
            acknowledgment.acknowledge();
        }
    }
}
