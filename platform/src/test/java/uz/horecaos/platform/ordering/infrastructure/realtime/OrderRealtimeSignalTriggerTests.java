package uz.horecaos.platform.ordering.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderExpired;
import uz.horecaos.platform.ordering.api.OrderReceived;
import uz.horecaos.platform.ordering.api.OrderRejected;
import uz.horecaos.platform.ordering.api.OrderRevisionCreated;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * {@link OrderRealtimeSignalTrigger} turning a real {@code ordering.api} fact
 * into the ADR 0045 push {@code OperationsStreamController} always had a
 * channel for and nothing ever fed (wave P08, rows {@code 0.1f}/{@code 1.1b}).
 *
 * <p>Constructed directly with {@code new} rather than through Spring, the
 * same shape {@code ReferralOrderCompletionTriggerTests} already established
 * for this class of listener — {@code @TransactionalEventListener} is inert
 * on a plain call, so what is under test is the mapping {@code
 * onOrderingEvent} performs, not the annotation.
 */
class OrderRealtimeSignalTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");

    private final RecordingPublisher realtime = new RecordingPublisher();
    private final OrderRealtimeSignalTrigger trigger = new OrderRealtimeSignalTrigger(realtime);

    @Test
    @DisplayName("a confirmation publishes on both ORDER_QUEUE and ORDER_DETAIL, at the order's location")
    void confirmationPublishesOnQueueAndDetail() {
        trigger.onOrderingEvent(new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                NOW,
                BRAND,
                LOCATION,
                "AUTO_CONFIRM",
                null,
                NOW,
                "UZS",
                45_000L,
                "CONFIRMED",
                2));

        assertThat(realtime.signals).hasSize(2);
        assertThat(realtime.signals)
                .extracting(RealtimeSignal::channel)
                .containsExactlyInAnyOrder(StreamChannel.ORDER_QUEUE, StreamChannel.ORDER_DETAIL);

        for (RealtimeSignal signal : realtime.signals) {
            assertThat(signal.tenantId()).isEqualTo(TENANT);
            assertThat(signal.scopeKey()).isEqualTo(ScopeKey.location(LOCATION));
            assertThat(signal.resourceType()).isEqualTo("Order");
            assertThat(signal.resourceId()).isEqualTo(ORDER);
            assertThat(signal.version()).isEqualTo(2L);
            assertThat(signal.occurredAt()).isEqualTo(NOW);
        }
    }

    @Test
    @DisplayName("every one of the six status transitions, plus the order's own arrival, signals")
    void everyTransitionSignals() {
        trigger.onOrderingEvent(new OrderReceived(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                NOW,
                BRAND,
                LOCATION,
                "WEB",
                "R-1",
                "DELIVERY",
                "AUTO_CONFIRM",
                null,
                0,
                "RECEIVED",
                0,
                "UZS",
                45_000L,
                2));
        trigger.onOrderingEvent(new OrderAwaitingApproval(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                NOW,
                BRAND,
                LOCATION,
                "OPERATIONS",
                NOW.plusSeconds(120),
                "AUTO_REJECT",
                "AWAITING_APPROVAL",
                1));
        trigger.onOrderingEvent(new OrderRejected(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                NOW,
                BRAND,
                LOCATION,
                "OPERATIONS",
                "OUT_OF_STOCK",
                "REJECTED",
                2));
        trigger.onOrderingEvent(new OrderExpired(
                UUID.randomUUID(), new TenantId(TENANT), ORDER, NOW, BRAND, LOCATION, NOW, "EXPIRED", 2));
        trigger.onOrderingEvent(new OrderCancelled(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                NOW,
                BRAND,
                LOCATION,
                "SYSTEM",
                "OUT_OF_STOCK",
                "CONFIRMED",
                "CANCELLED",
                2,
                null,
                null,
                null));
        trigger.onOrderingEvent(new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), ORDER, NOW, BRAND, LOCATION, NOW, "UZS", 45_000L, 3));

        // Six events, two frames each.
        assertThat(realtime.signals).hasSize(12);
    }

    @Test
    @DisplayName("an event that is not a status transition publishes nothing")
    void aNonTransitionEventPublishesNothing() {
        trigger.onOrderingEvent(new OrderRevisionCreated(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                NOW,
                BRAND,
                LOCATION,
                2,
                null,
                "UZS",
                50_000L,
                5_000L,
                3));

        assertThat(realtime.signals).isEmpty();
    }

    private static final class RecordingPublisher implements RealtimeSignalPublisher {

        private final List<RealtimeSignal> signals = new ArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            signals.add(signal);
        }
    }
}
