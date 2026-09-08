package uz.horecaos.platform.ordering.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Whether a confirmed delivery order actually gets the {@code
 * ORDER_FULFILLMENT} row {@code OrderFulfillmentProcess} needs to have anything
 * to poll (ADR 0019) — the seam this suite's own {@code
 * CartCheckoutAndOrderTests#aDeliveryOrderBecomesAPlan} javadoc names as
 * untested there, because that suite publishes events into a recorder rather
 * than a real Spring context and this listener never fires.
 */
class DeliveryPlanTriggerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b01");
    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b02");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b03");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b04");
    private static final UUID PLAN = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b05");
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    private DeliveryPlanner planner;
    private OrderFulfillmentProcess fulfillmentProcess;
    private DeliveryPlanTrigger trigger;

    @BeforeEach
    void setUp() {
        planner = mock(DeliveryPlanner.class);
        fulfillmentProcess = mock(OrderFulfillmentProcess.class);
        trigger = new DeliveryPlanTrigger(planner, fulfillmentProcess);
    }

    @Test
    @DisplayName("a plan opened for a confirmed order enqueues its ORDER_FULFILLMENT row")
    void openedPlanEnqueuesTheProcessRow() {
        when(planner.planFor(TENANT, BRAND, LOCATION, ORDER, NOW)).thenReturn(Optional.of(PLAN));

        trigger.onOrderConfirmed(confirmed());

        verify(fulfillmentProcess).enqueue(ORDER, TENANT, PLAN, NOW);
    }

    @Test
    @DisplayName("nothing to plan enqueues nothing — a pickup order is not a fulfillment process")
    void nothingToPlanEnqueuesNothing() {
        when(planner.planFor(TENANT, BRAND, LOCATION, ORDER, NOW)).thenReturn(Optional.empty());

        trigger.onOrderConfirmed(confirmed());

        verify(fulfillmentProcess, never()).enqueue(any(), any(), any(), any());
    }

    private static OrderConfirmed confirmed() {
        return new OrderConfirmed(
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
                10_000L,
                "CONFIRMED",
                1);
    }
}
