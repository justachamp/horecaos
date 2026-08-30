package uz.horecaos.platform.integration.inbox;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * What every {@code @KafkaListener} does with one record (ADR 0005).
 *
 * <p>Extracted the moment there was a second listener. The logic that decides
 * whether an offset may advance is the safety property of the whole inbox, and
 * two copies of it drift: the copy that gets the fix and the copy that acknowledges
 * a record whose effect never committed look identical in review.
 *
 * <p>One record is offered to every consumer registered for <em>that topic</em>,
 * independently, because deduplication is per consumer: a second consumer must
 * still see an event the first has already processed.
 */
@Component
public class InboxRecordDispatch {

    private static final Logger log = LoggerFactory.getLogger(InboxRecordDispatch.class);
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final InboxExecutor executor;
    private final InboxHandlerRegistry registry;

    public InboxRecordDispatch(InboxExecutor executor, InboxHandlerRegistry registry) {
        this.executor = executor;
        this.registry = registry;
    }

    /**
     * @return whether the Kafka offset may be acknowledged, which is true only
     *         when every consumer for this topic has taken durable
     *         responsibility for the record
     */
    public boolean offer(ConsumerRecord<String, String> record) {
        Map<String, String> headers = headersOf(record);
        String correlationId = headers.get("horecaos-correlation-id");

        if (correlationId != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
        try {
            boolean allAcknowledgeable = true;

            for (String consumerName : registry.consumerNamesFor(record.topic())) {
                InboxResult result = executor.execute(
                        consumerName,
                        record.key(),
                        record.value(),
                        headers,
                        record.topic(),
                        record.partition(),
                        record.offset());

                if (!result.mayAcknowledgeOffset()) {
                    // One consumer needing a retry holds the offset for all of
                    // them. The others deduplicate on redelivery, so the cost is
                    // a repeated no-op rather than a repeated effect.
                    allAcknowledgeable = false;
                    log.debug("Holding offset {}-{}@{} for consumer {}: {}",
                            record.topic(), record.partition(), record.offset(), consumerName, result);
                }
            }
            return allAcknowledgeable;
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    private static Map<String, String> headersOf(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            if (header.value() != null) {
                headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return headers;
    }
}
