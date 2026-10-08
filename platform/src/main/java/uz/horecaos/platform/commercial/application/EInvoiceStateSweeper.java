package uz.horecaos.platform.commercial.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks each e-invoicing operator what became of the documents it has not yet settled
 * (ADR 0096): an invoice sent and not yet signed, refused or cancelled, and a send whose
 * answer was lost. It also marks an attempt that was recorded and never concluded -- the
 * process died between writing it and hearing back -- as uncertain, so that staff and this
 * sweep resolve it by asking rather than by sending again.
 *
 * <p>Same shape as {@link WalletBonusExpirySweeper}: {@code runOnce()} does the pass and
 * answers how many documents moved, for a deterministic test; {@code sweepOnce()} is the
 * {@code @Scheduled} entry point, which catches and logs so one bad pass cannot stop the
 * schedule. With no operator account connected there is nothing to ask and the pass does
 * no operator call at all: an unconnected account is never called.
 *
 * <p>The state is polled rather than pushed: no operator's callback is built (ADR 0096's
 * specification defers "the state callbacks" to each operator's documentation), and a poll
 * every few minutes for the handful of documents a month produces is no load.
 */
@Component
public class EInvoiceStateSweeper {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceStateSweeper.class);

    private final EInvoicingService einvoicing;
    private final int batchSize;

    public EInvoiceStateSweeper(
            EInvoicingService einvoicing,
            @Value("${horecaos.commercial.einvoice.state-sweep.batch-size:50}") int batchSize) {
        this.einvoicing = einvoicing;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.commercial.einvoice.state-sweep.initial-delay:PT3M}",
            fixedDelayString = "${horecaos.commercial.einvoice.state-sweep.interval:PT15M}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The e-invoice state sweep could not run", failure);
        }
    }

    /** @return how many documents' state moved, for a deterministic test */
    public int runOnce() {
        int moved = einvoicing.refreshOpen(batchSize);
        if (moved > 0) {
            log.info("E-invoice state sweep: {} documents moved", moved);
        }
        return moved;
    }
}
