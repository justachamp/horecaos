package uz.horecaos.platform.telemetry.infrastructure.realtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;

/**
 * Puts a signal on {@code realtime.signals} (ADR 0045, ADR 0032).
 *
 * <p>Deliberately not the ADR 0004 outbox, and the difference is the point of
 * ADR 0032's fourth topic class. An outbox row is written in the business
 * transaction, relayed with at-least-once delivery, retried with backoff, and
 * kept for replay and reconciliation, because the things on it are facts somebody
 * will one day argue about. A signal is none of that: it has seconds of
 * retention, no replay, no business meaning, and it is never catalogued as a
 * fact. Giving it the outbox's guarantees would cost the outbox's budget on the
 * one machine ADR 0034 provides, to durably deliver a hint that heals by itself
 * at the next resync.
 *
 * <p>Publishing never fails a business transaction. A lost signal costs one
 * screen its acceleration until the client's next poll, and every live surface
 * has a polling path that must work — which is the entire reason polling is built
 * first.
 *
 * <p>The record key is the scope key, so all of one branch's signals land on one
 * partition and arrive in order. There is no consumer group on the other side, so
 * that ordering is the only ordering guarantee in the design, and it is enough:
 * the frames are hints, and a hint out of order is a hint.
 */
@Component
@ConditionalOnProperty(name = "horecaos.realtime.signals.publish", havingValue = "true", matchIfMissing = true)
public class KafkaRealtimeSignalPublisher implements RealtimeSignalPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaRealtimeSignalPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public KafkaRealtimeSignalPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper json,
            @Value("${horecaos.messaging.topics.realtime-signals:realtime.signals}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    @Override
    // send()'s returned future is deliberately observed (below) rather than
    // awaited: whenComplete attaches the failure logging that makes fire-and-
    // forget publishing honest about broker-down failures, and the second
    // CompletableFuture whenComplete itself returns — completion of that
    // logging callback, which cannot itself fail — has nothing further to
    // check. This is the narrow case the check does not distinguish from a
    // future silently dropped with no handler at all.
    @SuppressWarnings("FutureReturnValueIgnored")
    public void publish(RealtimeSignal signal) {
        try {
            // send() itself only throws for a failure it can detect before the
            // record leaves this process (serialization, an unset topic); a
            // broker that is unreachable fails the *returned* future instead,
            // asynchronously, and that completion was previously never observed
            // — silently dropping the exact "broker down" case the comment below
            // already claims is handled. whenComplete logs that path too, without
            // making publish() block on it.
            kafka.send(topic, signal.scopeKey().canonical(), json.writeValueAsString(wireForm(signal)))
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            logPublishFailure(signal, failure);
                        }
                    });
        } catch (RuntimeException failure) {
            logPublishFailure(signal, failure);
        }
    }

    private static void logPublishFailure(RealtimeSignal signal, Throwable failure) {
        // Debug rather than warn. A broker that is down is already alarmed on
        // by ADR 0023's outbox age gauge, and one log line per signal would
        // bury it under thousands of copies of the same fact.
        log.debug(
                "Could not publish a realtime signal for {}; clients fall back to polling", signal.channel(), failure);
    }

    /**
     * The wire shape.
     *
     * <p>Everything here is an identifier, a name, or a time. ADR 0032 forbids
     * anything above {@code INTERNAL} on any topic, and a courier's signal
     * therefore carries a courier id and a scope key — the replica that receives
     * it reads the live row it already has access to.
     */
    private static Map<String, Object> wireForm(RealtimeSignal signal) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("signalId", signal.signalId().toString());
        shape.put("tenantId", signal.tenantId().toString());
        shape.put("channel", signal.channel().name());
        shape.put("scope", signal.scopeKey().canonical());
        shape.put("resourceType", signal.resourceType());
        shape.put(
                "resourceId",
                signal.resourceId() == null ? null : signal.resourceId().toString());
        shape.put("version", signal.version());
        shape.put("occurredAt", signal.occurredAt().toString());
        return shape;
    }
}
