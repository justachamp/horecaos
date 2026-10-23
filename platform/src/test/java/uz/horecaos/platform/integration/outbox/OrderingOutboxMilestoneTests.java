package uz.horecaos.platform.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.api.OrderFulfilling;
import uz.horecaos.platform.ordering.api.OrderPreparing;
import uz.horecaos.platform.ordering.api.OrderReady;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * ADR 0184: the three milestone facts reach the outbox the way every other ordering fact does (ADR 0004,
 * ADR 0032): catalogued, keyed by the order, on the ordering topic, and carrying no customer.
 */
class OrderingOutboxMilestoneTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Test
    @DisplayName("each milestone is appended once, on ordering.events, keyed by the order, with its own payload")
    void eachMilestoneIsAppended() {
        JdbcOutboxStore outbox = mock(JdbcOutboxStore.class);
        OrderingOutboxEventListener listener =
                new OrderingOutboxEventListener(outbox, JsonMapper.builder().build(), "ordering.events");

        List<OrderingEvent> milestones = List.of(
                new OrderPreparing(
                        UUID.randomUUID(), new TenantId(TENANT), ORDER, NOW, BRAND, LOCATION, "DELIVERY", NOW, 4),
                new OrderReady(UUID.randomUUID(), new TenantId(TENANT), ORDER, NOW, BRAND, LOCATION, "PICKUP", NOW, 5),
                new OrderFulfilling(
                        UUID.randomUUID(), new TenantId(TENANT), ORDER, NOW, BRAND, LOCATION, "DELIVERY", NOW, 6));
        milestones.forEach(listener::append);

        ArgumentCaptor<NewOutboxEvent> appended = ArgumentCaptor.forClass(NewOutboxEvent.class);
        verify(outbox, times(3)).append(appended.capture());
        assertThat(appended.getAllValues())
                .extracting(NewOutboxEvent::eventType)
                .containsExactly("OrderPreparing", "OrderReady", "OrderFulfilling");
        for (NewOutboxEvent event : appended.getAllValues()) {
            assertThat(event.topic()).isEqualTo("ordering.events");
            assertThat(event.partitionKey())
                    .as("every fact about one order stays in order")
                    .isEqualTo(ORDER.toString());
            assertThat(event.aggregateId()).isEqualTo(ORDER);
            assertThat(event.tenantId()).isEqualTo(TENANT);
            assertThat(event.payloadJson())
                    .contains(ORDER.toString())
                    .contains("fulfillmentMode")
                    .doesNotContain("customer")
                    .doesNotContain("courier");
        }
        assertThat(appended.getAllValues().get(1).payloadJson()).contains("\"status\":\"READY\"");
    }
}
