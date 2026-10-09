package uz.horecaos.platform.payments.application;

import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalSubmission;
import uz.horecaos.platform.payments.domain.ProviderBinding;

/**
 * Asking a payment partner to issue a fiscal receipt (ADR 0013, ADR 0038).
 *
 * <p>Separate from {@link PaymentProviderPort} because fiscalization is a
 * different capability with a different availability: a provider may settle
 * payments and not fiscalize them, and Click and Payme invert the timing of it
 * relative to the payment. Click fiscalizes strictly after capture, because
 * {@code payment/ofd_data/submit_items} needs a CLICK {@code payment_id} that does
 * not exist earlier. Payme fiscalizes from a {@code detail} object fixed before
 * the customer pays and reports the outcome back afterwards through
 * {@code SetFiscalData} — so on Payme this call is a no-op that answers
 * {@link FiscalSubmission#accepted} and the receipt arrives inbound later.
 *
 * <p>Each adapter builds its own wire lines from
 * {@link uz.horecaos.platform.payments.domain.FiscalReceiptLine}, and there is
 * deliberately no shared line builder: Click's {@code Price} is the line total and
 * Payme's {@code price} is the unit price, so one shared helper fiscalizes an order
 * at quantity squared times its value. Click's amounts here are tiyin even though
 * the same provider's payment call was som.
 *
 * <p>Never called for cash. A cash order has no CLICK {@code payment_id} to hang
 * {@code submit_items} on and no Payme receipt to attach fiscal data to, and its
 * document is recorded {@code NOT_APPLICABLE} with a reason instead.
 */
public interface FiscalReceiptPort {

    FiscalSubmission submit(FiscalDocument document, ProviderBinding binding);

    uz.horecaos.platform.payments.domain.PaymentProviderType providerType();

    /**
     * Whether this provider's receipt can carry a marking code per unit (ADR 0161).
     *
     * <p>Click's {@code Items[].Labels} can, so its adapter says so. Payme's {@code detail} object has no
     * such field at all, so it keeps this default and a marked good cannot lawfully be fiscalized through
     * it. A capability on the port and not a provider test in the caller, which {@code AGENTS.md} forbids:
     * the next provider declares what it can do and no core class learns its name.
     */
    default boolean carriesMarkingCodes() {
        return false;
    }
}
