package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.DueTimerRow;

/**
 * H9: a fired approval-deadline timer whose apply threw used to be logged and
 * dropped, permanently FIRED with nothing to reclaim it. No database: {@link
 * JdbcOrderStore#claimDueTimers}'s SQL is proved separately in {@code
 * CartCheckoutAndOrderTests}; this proves {@link
 * OrderProcessWorker#fireDueTimers} makes the right retry decision from a
 * given {@code attemptCount}, the same style {@code
 * OperationsOrderControllerActionCapabilitiesTests} uses for a controller.
 */
class OrderProcessWorkerTimerRetryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID TIMER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private static OrderProcessWorker workerFor(JdbcOrderStore orders, OrderStateService state) {
        return workerFor(orders, state, mock(OrderPaymentProcess.class), "SHADOW");
    }

    private static OrderProcessWorker workerFor(
            JdbcOrderStore orders, OrderStateService state, OrderPaymentProcess payments, String cancelMode) {
        return new OrderProcessWorker(
                orders,
                state,
                mock(OrderInventoryProcess.class),
                mock(OrderFulfillmentProcess.class),
                payments,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50,
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofMinutes(1),
                RETRY_BACKOFF,
                Duration.ofMinutes(2),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                cancelMode);
    }

    @Test
    @DisplayName("a failed timer apply is retried with a bounded backoff instead of being permanently dropped")
    void aFailedApplyIsRetriedWithBackoff() {
        JdbcOrderStore orders = mock(JdbcOrderStore.class);
        when(orders.claimDueTimers(any(), any(), anyInt()))
                .thenReturn(List.of(new DueTimerRow(TIMER, TENANT, ORDER, "APPROVAL_DEADLINE", 0)));
        OrderStateService state = mock(OrderStateService.class);
        doThrow(new RuntimeException("a transient failure")).when(state).approvalDeadlineReached(TENANT, ORDER);

        workerFor(orders, state).fireDueTimers();

        // attempt 1 of 8: quarantined with a backoff, not exhausted.
        verify(orders).markTimerFailed(TENANT, TIMER, 1, NOW.plus(RETRY_BACKOFF));
    }

    @Test
    @DisplayName(
            "a timer that exhausts its retry budget becomes MANUAL_ACTION_REQUIRED rather than staying silently FIRED")
    void anExhaustedRetryBudgetBecomesManualActionRequired() {
        JdbcOrderStore orders = mock(JdbcOrderStore.class);
        // Already failed seven times; this is the eighth and last attempt.
        when(orders.claimDueTimers(any(), any(), anyInt()))
                .thenReturn(List.of(new DueTimerRow(TIMER, TENANT, ORDER, "APPROVAL_DEADLINE", 7)));
        OrderStateService state = mock(OrderStateService.class);
        doThrow(new RuntimeException("still failing")).when(state).approvalDeadlineReached(TENANT, ORDER);

        workerFor(orders, state).fireDueTimers();

        // null nextRetryAt is markTimerFailed's own signal for the dead-letter
        // state (JdbcOrderStore.markTimerFailed's javadoc).
        verify(orders).markTimerFailed(eq(TENANT), eq(TIMER), eq(8), isNull());
    }

    @Test
    @DisplayName("a successful apply never touches markTimerFailed")
    void aSuccessfulApplyNeverQuarantines() {
        JdbcOrderStore orders = mock(JdbcOrderStore.class);
        when(orders.claimDueTimers(any(), any(), anyInt()))
                .thenReturn(List.of(new DueTimerRow(TIMER, TENANT, ORDER, "APPROVAL_DEADLINE", 0)));
        OrderStateService state = mock(OrderStateService.class);

        workerFor(orders, state).fireDueTimers();

        verify(orders, never()).markTimerFailed(any(), any(), anyInt(), any());
    }

    // ------------------------------------------------ ADR 0170: shadow, then enforce

    @Test
    @DisplayName("in SHADOW (the default) the payment sweep is given no lapse handler, so a CANCEL window ends nothing")
    void shadowGivesTheSweepNoWayToEndAnOrder() {
        OrderPaymentProcess payments = mock(OrderPaymentProcess.class);

        workerFor(mock(JdbcOrderStore.class), mock(OrderStateService.class), payments, "SHADOW")
                .sweepStalePayments();

        verify(payments).sweep(any(), any(), any(), anyInt(), isNull());
    }

    @Test
    @DisplayName("in ENFORCE the payment sweep is given a lapse handler that ends the order through OrderStateService")
    void enforceGivesTheSweepALapseHandler() {
        OrderPaymentProcess payments = mock(OrderPaymentProcess.class);
        OrderStateService state = mock(OrderStateService.class);
        when(state.paymentWindowReached(TENANT, ORDER, 20, 3))
                .thenReturn(new OrderStateService.DecisionResult(true, OrderStatus.PAYMENT_FAILED, 4, null));
        org.mockito.ArgumentCaptor<OrderPaymentProcess.Lapse> lapse =
                org.mockito.ArgumentCaptor.forClass(OrderPaymentProcess.Lapse.class);

        workerFor(mock(JdbcOrderStore.class), state, payments, "ENFORCE").sweepStalePayments();

        verify(payments).sweep(any(), any(), any(), anyInt(), lapse.capture());
        // The transaction manager here is a mock, so the nested template runs the callback against it.
        assertThat(lapse.getValue().apply(TENANT, ORDER, 20, 3)).isTrue();
        verify(state).paymentWindowReached(TENANT, ORDER, 20, 3);
    }
}
