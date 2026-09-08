package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore.ProcessRow;

/**
 * The one process manager ADR 0019 called "driven by nothing at all" — whether
 * the row this class now maintains actually behaves like the other durable
 * process state in {@code ordering.order_process_states} rather than merely
 * existing.
 */
class OrderPaymentProcessTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b01");
    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b02");
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    private JdbcOrderProcessStore processes;
    private OrderPaymentProcess process;

    @BeforeEach
    void setUp() {
        processes = mock(JdbcOrderProcessStore.class);
        process = new OrderPaymentProcess(processes, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("checkout enqueues a row for the order it just put into PAYMENT_AUTHORIZING")
    void enqueueWritesTheEnteredInstant() {
        process.enqueue(ORDER, TENANT, NOW);

        verify(processes)
                .enqueue(
                        eq(ORDER),
                        eq(TENANT),
                        eq(OrderPaymentProcess.PROCESS_NAME),
                        org.mockito.ArgumentMatchers.contains(NOW.toString()),
                        eq(NOW));
    }

    @Test
    @DisplayName("a resolved order settles its row without a claimed version")
    void settleResolvedClosesTheRow() {
        when(processes.settleCompletedIfActive(eq(ORDER), eq(OrderPaymentProcess.PROCESS_NAME), anyString(), eq(NOW)))
                .thenReturn(true);

        process.settleResolved(ORDER, NOW);

        verify(processes)
                .settleCompletedIfActive(eq(ORDER), eq(OrderPaymentProcess.PROCESS_NAME), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("a fresh order in PAYMENT_AUTHORIZING is rescheduled, not flagged")
    void aFreshRowIsNotStuck() {
        Instant enteredAt = NOW.minus(Duration.ofMinutes(2));
        when(processes.claim(eq(OrderPaymentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(enteredAt, 0, 1)));

        int checked = process.sweep(NOW, Duration.ofMinutes(30), Duration.ofMinutes(1), 50);

        assertThat(checked).isEqualTo(1);
        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderPaymentProcess.PROCESS_NAME),
                        eq(1),
                        eq("WAITING"),
                        anyString(),
                        eq(NOW.plus(Duration.ofMinutes(1))),
                        eq(null),
                        eq(NOW));
        verify(processes, never())
                .settle(any(), any(), anyInt(), eq("MANUAL_ACTION_REQUIRED"), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("an order still authorizing past the threshold is flagged, never cancelled")
    void aStaleRowIsFlaggedForAnOperator() {
        Instant enteredAt = NOW.minus(Duration.ofMinutes(45));
        when(processes.claim(eq(OrderPaymentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(enteredAt, 3, 4)));

        process.sweep(NOW, Duration.ofMinutes(30), Duration.ofMinutes(1), 50);

        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderPaymentProcess.PROCESS_NAME),
                        eq(4),
                        eq("MANUAL_ACTION_REQUIRED"),
                        anyString(),
                        eq(null),
                        anyString(),
                        eq(NOW));
    }

    @Test
    @DisplayName("a checkpoint this sweep cannot parse keeps its schedule instead of stopping the batch")
    void anUnparseableRowIsRequeuedNotLost() {
        ProcessRow broken =
                new ProcessRow(ORDER, OrderPaymentProcess.PROCESS_NAME, TENANT, "WAITING", "{not json", 0, 1);
        when(processes.claim(eq(OrderPaymentProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(broken));

        int checked = process.sweep(NOW, Duration.ofMinutes(30), Duration.ofMinutes(1), 50);

        assertThat(checked).isEqualTo(1);
        verify(processes)
                .settle(
                        eq(ORDER),
                        eq(OrderPaymentProcess.PROCESS_NAME),
                        eq(1),
                        eq("WAITING"),
                        eq("{not json"),
                        eq(NOW.plus(Duration.ofMinutes(1))),
                        anyString(),
                        eq(NOW));
    }

    private static ProcessRow row(Instant enteredAt, int attemptCount, int version) {
        String checkpoint = """
                {"enteredAuthorizingAt":"%s"}""".formatted(enteredAt);
        return new ProcessRow(
                ORDER, OrderPaymentProcess.PROCESS_NAME, TENANT, "WAITING", checkpoint, attemptCount, version);
    }
}
