package uz.horecaos.platform.telemetry.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignal.Subscription;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * A brand-scoped {@code ORDER_QUEUE} signal across the whole hop, minus the broker: published
 * the way {@code ordering} publishes it, serialized by {@link KafkaRealtimeSignalPublisher},
 * read back by {@link RealtimeSignalConsumer} and routed by {@link SseStreamRegistry} to the
 * board that subscribed at the brand (gap map row {@code 1.1}).
 *
 * <p>Each end is tested on its own elsewhere; what none of them proves is that the wire form
 * one writes is one the other reads when the scope is a {@code BRAND} -- the first channel
 * that is carried at two levels, so a key that parses as a branch's would pass every one of
 * those tests and still deliver nothing to the brand.
 */
class RealtimeSignalBrandHopTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final Instant NOON = Instant.parse("2026-10-05T07:00:00Z");
    private static final String TOPIC = "realtime.signals";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName(
            "a brand-scoped queue signal is keyed by its brand, read back as one, and delivered to the brand's board")
    void aBrandSignalSurvivesTheHop() {
        KafkaTemplate<String, String> kafka = kafkaTemplate();
        KafkaRealtimeSignalPublisher publisher = new KafkaRealtimeSignalPublisher(kafka, json, TOPIC);
        SseStreamRegistry registry =
                new SseStreamRegistry(json, Clock.fixed(NOON, ZoneOffset.UTC), new SimpleMeterRegistry(), List.of());
        @SuppressWarnings("unchecked")
        RealtimeSignalConsumer consumer =
                new RealtimeSignalConsumer(mock(ConsumerFactory.class), registry, json, TOPIC);

        Frames brandBoard = new Frames();
        Frames branchBoard = new Frames();
        registry.open(
                TENANT,
                "brand-board",
                Set.of(new Subscription(StreamChannel.ORDER_QUEUE, ScopeKey.brand(BRAND))),
                brandBoard,
                NOON.plusSeconds(600),
                null);
        registry.open(
                TENANT,
                "branch-board",
                Set.of(new Subscription(StreamChannel.ORDER_QUEUE, ScopeKey.location(BRANCH))),
                branchBoard,
                NOON.plusSeconds(600),
                null);

        UUID order = UUID.randomUUID();
        publisher.publish(
                RealtimeSignal.of(TENANT, StreamChannel.ORDER_QUEUE, ScopeKey.brand(BRAND), "Order", order, 4L, NOON));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(TOPIC), key.capture(), payload.capture());
        assertThat(key.getValue())
                .as("the record key is the scope key, so one brand's signals stay in order on one partition")
                .isEqualTo("BRAND:" + BRAND);

        RealtimeSignal read = consumer.parse(payload.getValue()).orElseThrow();
        assertThat(read.channel()).isEqualTo(StreamChannel.ORDER_QUEUE);
        assertThat(read.scopeKey()).isEqualTo(ScopeKey.brand(BRAND));
        assertThat(read.resourceId()).isEqualTo(order);
        assertThat(read.version()).isEqualTo(4L);

        registry.onSignal(read);
        registry.tick(NOON.plusSeconds(1));

        assertThat(brandBoard.signals).hasSize(1);
        assertThat(brandBoard.signals.getFirst())
                .contains("\"channel\":\"order_queue\"")
                .contains("\"scope\":\"BRAND:" + BRAND + "\"")
                .contains("\"resourceId\":\"" + order + "\"");
        assertThat(branchBoard.signals)
                .as("a branch board is not woken by the brand's copy of a change")
                .isEmpty();
    }

    @Test
    @DisplayName("a queue signal for a scope the channel does not carry is dropped as malformed, not delivered")
    void aTenantScopedQueueSignalIsDropped() {
        @SuppressWarnings("unchecked")
        RealtimeSignalConsumer consumer = new RealtimeSignalConsumer(
                mock(ConsumerFactory.class),
                new SseStreamRegistry(json, Clock.fixed(NOON, ZoneOffset.UTC), new SimpleMeterRegistry(), List.of()),
                json,
                TOPIC);
        String tenantScoped = """
                {"signalId":"%s","tenantId":"%s","channel":"ORDER_QUEUE","scope":"TENANT:%s",
                 "resourceType":"Order","resourceId":null,"version":null,"occurredAt":"%s"}""".formatted(UUID.randomUUID(), TENANT, TENANT, NOON);

        assertThat(consumer.parse(tenantScoped)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        return kafka;
    }

    /** The data of every {@code signal} event a stream was sent. */
    private static final class Frames implements StreamSink {

        final List<String> signals = new ArrayList<>();

        @Override
        public void send(String eventName, String id, String data) {
            if ("signal".equals(eventName)) {
                signals.add(data);
            }
        }

        @Override
        public void heartbeat() {}

        @Override
        public void complete() {}

        @Override
        public void completeWithError(Throwable failure) {}
    }
}
