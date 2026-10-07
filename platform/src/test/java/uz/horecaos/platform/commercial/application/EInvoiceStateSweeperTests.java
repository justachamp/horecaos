package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled entry point asks for one bounded batch, reports what moved, and never lets a failure
 * escape into the scheduler -- a sweep that threw once would otherwise stop every later one.
 */
class EInvoiceStateSweeperTests {

    private final EInvoicingService service = mock(EInvoicingService.class);

    @Test
    @DisplayName("a pass asks for one batch and answers how many documents moved")
    void aPassIsOneBoundedBatch() {
        when(service.refreshOpen(25)).thenReturn(3);

        assertThat(new EInvoiceStateSweeper(service, 25).runOnce()).isEqualTo(3);
        verify(service).refreshOpen(25);
    }

    @Test
    @DisplayName("a failure is logged and swallowed by the scheduled entry point")
    void aFailureDoesNotStopTheSchedule() {
        when(service.refreshOpen(50)).thenThrow(new IllegalStateException("database is down"));
        EInvoiceStateSweeper sweeper = new EInvoiceStateSweeper(service, 50);

        sweeper.sweepOnce();

        verify(service).refreshOpen(50);
    }
}
