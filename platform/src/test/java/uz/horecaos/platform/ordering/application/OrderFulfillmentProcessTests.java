package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner.SourcingOutcome;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore.ProcessRow;

/**
 * Whether this class faithfully mirrors fulfillment's own sourcing signal
 * rather than inventing a second, competing opinion about how long sourcing
 * should be allowed to take (ADR 0019).
 */
class OrderFulfillmentProcessTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b01");
    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b02");
    private static final UUID PLAN = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b03");
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final Duration RECHECK = Duration.ofSeconds(30);

    private JdbcOrderProcessStore processes;
    private DeliveryPlanner planner;
    private OrderFulfillmentProcess process;

    @BeforeEach
    void setUp() {
        processes = mock(JdbcOrderProcessStore.class);
        planner = mock(DeliveryPlanner.class);
        process = new OrderFulfillmentProcess(
                processes, planner, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("the plan DeliveryPlanTrigger just opened is enqueued as this order's checkpoint")
    void enqueueWritesThePlanId() {
        process.enqueue(ORDER, TENANT, PLAN, NOW);

        verify(processes)
                .enqueue(
                        eq(ORDER),
                        eq(TENANT),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq("{\"planId\":\"%s\"}".formatted(PLAN)),
                        eq(NOW));
    }

    @Test
    @DisplayName("a courier found closes the row")
    void sourcedSettlesCompleted() {
        when(processes.claim(eq(OrderFulfillmentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(0, 1)));
        when(planner.sourcingOutcome(TENANT, ORDER)).thenReturn(Optional.of(SourcingOutcome.SOURCED));

        int checked = process.runOnce(50, NOW, RECHECK);

        assertThat(checked).isEqualTo(1);
        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq(1),
                        eq("COMPLETED"),
                        anyString(),
                        eq(null),
                        eq(null),
                        eq(NOW));
    }

    @Test
    @DisplayName("a cancelled plan is a resolved order, not a stuck one")
    void cancelledSettlesCompleted() {
        when(processes.claim(eq(OrderFulfillmentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(0, 1)));
        when(planner.sourcingOutcome(TENANT, ORDER)).thenReturn(Optional.of(SourcingOutcome.CANCELLED));

        process.runOnce(50, NOW, RECHECK);

        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq(1),
                        eq("COMPLETED"),
                        anyString(),
                        eq(null),
                        eq(null),
                        eq(NOW));
    }

    @Test
    @DisplayName("fulfillment's own MANUAL_ACTION_REQUIRED is mirrored, not re-decided")
    void manualActionIsMirroredDirectly() {
        when(processes.claim(eq(OrderFulfillmentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(0, 1)));
        when(planner.sourcingOutcome(TENANT, ORDER)).thenReturn(Optional.of(SourcingOutcome.MANUAL_ACTION_REQUIRED));

        process.runOnce(50, NOW, RECHECK);

        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq(1),
                        eq("MANUAL_ACTION_REQUIRED"),
                        anyString(),
                        eq(null),
                        anyString(),
                        eq(NOW));
    }

    @Test
    @DisplayName("still sourcing is rescheduled rather than timed out on this class's own clock")
    void inProgressIsRescheduled() {
        when(processes.claim(eq(OrderFulfillmentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(5, 6)));
        when(planner.sourcingOutcome(TENANT, ORDER)).thenReturn(Optional.of(SourcingOutcome.IN_PROGRESS));

        process.runOnce(50, NOW, RECHECK);

        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq(6),
                        eq("WAITING"),
                        anyString(),
                        eq(NOW.plus(RECHECK)),
                        eq(null),
                        eq(NOW));
    }

    @Test
    @DisplayName("a plan this row names but fulfillment cannot find gets its own small ladder")
    void missingPlanIsRetriedThenEscalated() {
        when(processes.claim(eq(OrderFulfillmentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(0, 1)));
        when(planner.sourcingOutcome(TENANT, ORDER)).thenReturn(Optional.empty());

        process.runOnce(50, NOW, RECHECK);

        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq(1),
                        eq("FAILED_RETRYABLE"),
                        anyString(),
                        eq(NOW.plus(RECHECK)),
                        anyString(),
                        eq(NOW));
    }

    @Test
    @DisplayName("a persistently missing plan reaches a person rather than looping forever")
    void missingPlanEventuallyEscalates() {
        when(processes.claim(eq(OrderFulfillmentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(7, 8)));
        when(planner.sourcingOutcome(TENANT, ORDER)).thenReturn(Optional.empty());

        process.runOnce(50, NOW, RECHECK);

        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderFulfillmentProcess.PROCESS_NAME),
                        eq(8),
                        eq("MANUAL_ACTION_REQUIRED"),
                        anyString(),
                        eq(null),
                        anyString(),
                        eq(NOW));
    }

    private static ProcessRow row(int attemptCount, int version) {
        String checkpoint = "{\"planId\":\"%s\"}".formatted(PLAN);
        return new ProcessRow(
                ORDER, OrderFulfillmentProcess.PROCESS_NAME, TENANT, "WAITING", checkpoint, attemptCount, version);
    }
}
