package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore.ProcessRow;

/**
 * What one unrunnable row does to every other order's inventory.
 *
 * <p>The batch is one transaction, which is the right shape — the claim and the
 * work have to settle together. What that shape cannot survive is an exception
 * escaping the loop: the transaction rolls back, the claim rolls back with it,
 * and the same batch is claimed and throws again on the next tick, for ever.
 * Nothing about that is visible. {@code attempt_count} never advances, so the
 * eight-attempt ladder never reaches a person, and the queue simply stops moving
 * for every tenant on the platform.
 *
 * <p>The realistic source is not a corrupt row but a rolling deploy: a new node
 * writes a checkpoint naming an action an old node has never heard of, and the
 * old node stops settling anything at all until it is replaced.
 */
class OrderInventoryProcessTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b01");
    private static final UUID STUCK_ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b02");
    private static final UUID GOOD_ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b03");
    private static final UUID QUOTE = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121b04");

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    /** Mirrors the ladder in the class under test. */
    private static final int MAX_ATTEMPTS = 8;

    private JdbcOrderProcessStore processes;
    private InventoryReservationPort inventory;
    private OrderInventoryProcess process;

    @BeforeEach
    void setUp() {
        processes = mock(JdbcOrderProcessStore.class);
        inventory = mock(InventoryReservationPort.class);
        process = new OrderInventoryProcess(
                processes, inventory, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a row this version cannot run does not stop the rest of the batch")
    void oneUnrunnableRowIsNotEverybodysProblem() {
        when(processes.claim(eq(OrderInventoryProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(
                        row(STUCK_ORDER, """
                                {"action":"RETURN","quoteId":"%s"}""".formatted(QUOTE), 0, 3), row(GOOD_ORDER, """
                                {"action":"COMMIT","quoteId":"%s"}""".formatted(QUOTE), 0, 1)));
        when(inventory.commit(TENANT, QUOTE)).thenReturn(true);

        int settled = process.runOnce(50);

        assertThat(settled)
                .as("both rows are accounted for; the batch does not abandon its claim")
                .isEqualTo(2);
        verify(processes)
                .settle(eq(GOOD_ORDER), anyString(), eq(1), eq("COMPLETED"), anyString(), eq(null), eq(null), eq(NOW));
        verify(processes)
                .settle(
                        eq(STUCK_ORDER),
                        anyString(),
                        eq(3),
                        eq("FAILED_RETRYABLE"),
                        anyString(),
                        any(Instant.class),
                        anyString(),
                        eq(NOW));
    }

    @Test
    @DisplayName("the instruction survives quarantine, because nothing was done to it")
    void aQuarantinedRowKeepsItsCheckpoint() {
        String checkpoint = """
                {"action":"RETURN","quoteId":"%s"}""".formatted(QUOTE);
        when(processes.claim(eq(OrderInventoryProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(STUCK_ORDER, checkpoint, 0, 3)));

        process.runOnce(50);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(processes)
                .settle(
                        eq(STUCK_ORDER),
                        anyString(),
                        anyInt(),
                        eq("FAILED_RETRYABLE"),
                        written.capture(),
                        any(Instant.class),
                        anyString(),
                        eq(NOW));
        assertThat(written.getValue())
                .as("a node that does understand the action still has to be able to carry it out")
                .isEqualTo(checkpoint);
    }

    @Test
    @DisplayName("a row that keeps failing ends with a person rather than with a loop")
    void theLadderReachesAnOperator() {
        when(processes.claim(eq(OrderInventoryProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(STUCK_ORDER, """
                        {"action":"RETURN","quoteId":"%s"}""".formatted(QUOTE), MAX_ATTEMPTS - 1, 9)));

        process.runOnce(50);

        // No next attempt: the row leaves the runnable set entirely and appears on
        // the stuck list instead, which is where a process nobody can finish
        // belongs.
        verify(processes)
                .settle(
                        eq(STUCK_ORDER),
                        anyString(),
                        eq(9),
                        eq("MANUAL_ACTION_REQUIRED"),
                        anyString(),
                        eq(null),
                        anyString(),
                        eq(NOW));
    }

    @Test
    @DisplayName("a release against a hold that is already gone is done, not stuck")
    void aReplayedReleaseIsNotAnIncident() {
        when(processes.claim(eq(OrderInventoryProcess.PROCESS_NAME), any(), anyInt()))
                .thenReturn(List.of(row(GOOD_ORDER, """
                        {"action":"RELEASE","quoteId":"%s"}""".formatted(QUOTE), 0, 1)));
        when(inventory.release(TENANT, QUOTE)).thenReturn(false);

        process.runOnce(50);

        verify(processes)
                .settle(eq(GOOD_ORDER), anyString(), eq(1), eq("COMPLETED"), anyString(), eq(null), eq(null), eq(NOW));
    }

    private static ProcessRow row(UUID orderId, String checkpoint, int attemptCount, int version) {
        return new ProcessRow(
                orderId, OrderInventoryProcess.PROCESS_NAME, TENANT, "WAITING", checkpoint, attemptCount, version);
    }
}
