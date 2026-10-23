package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * An alert told after the publisher's transaction has committed is written in a transaction of its own (ADR 0187), so it is
 * committed, or rolled back, as one whatever the publisher's state.
 */
class AfterCommitAlertTransactionTests {

    private PlatformTransactionManager manager;
    private TransactionStatus status;
    private AfterCommitAlertTransaction transaction;

    @BeforeEach
    void setUp() {
        manager = mock(PlatformTransactionManager.class);
        status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        transaction = new AfterCommitAlertTransaction(manager);
    }

    @Test
    @DisplayName("the work runs inside a new transaction, never one it would join, and the transaction commits")
    void theWorkRunsInATransactionOfItsOwn() {
        AtomicInteger ran = new AtomicInteger();

        transaction.run(ran::incrementAndGet);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        InOrder order = inOrder(manager);
        order.verify(manager).getTransaction(definition.capture());
        order.verify(manager).commit(status);
        assertThat(definition.getValue().getPropagationBehavior())
                .as("a REQUIRED transaction would join the finished one and write nothing of its own")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(ran).hasValue(1);
    }

    @Test
    @DisplayName("a failure in the work rolls the alert's transaction back and reaches the caller, which logs it")
    void aFailureRollsBackAndIsRethrown() {
        IllegalStateException fault = new IllegalStateException("the second row could not be written");

        assertThatThrownBy(() -> transaction.run(() -> {
                    throw fault;
                }))
                .isSameAs(fault);

        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    @DisplayName("without a transaction (a unit test with a recording port) the work simply runs")
    void withoutATransactionTheWorkJustRuns() {
        AtomicInteger ran = new AtomicInteger();

        AfterCommitAlertTransaction.withoutTransaction().run(ran::incrementAndGet);

        assertThat(ran).hasValue(1);
    }
}
