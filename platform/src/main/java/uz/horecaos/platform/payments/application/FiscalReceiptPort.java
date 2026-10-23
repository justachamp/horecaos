package uz.horecaos.platform.payments.application;

import java.util.Optional;
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

    /**
     * Whether this provider takes the receipt lines <em>with the checkout</em>, fixed before the customer pays
     * (ADR 0166 Decision 9), instead of after capture from a document.
     *
     * <p>Payme does: the lines travel in the checkout form and the {@code CheckPerformTransaction} result, so they
     * are built when the payment is first presented and kept on the attempt. Click does not: it fiscalizes from a
     * {@code payment_id} that exists only after capture. A capability on the port, for the reason {@link
     * #carriesMarkingCodes} is: no core class learns a provider's name.
     */
    default boolean takesLinesWithTheCheckout() {
        return false;
    }

    /**
     * The receipt the provider already holds for this document, asked without sending anything (ADR 0038).
     *
     * <p>A document that was asked about once may have been filed whatever the reply said: Click's {@code
     * submit_items} is not documented as idempotent, and its answer can be lost after the receipt exists. This is
     * the read that settles it, the one {@link #submit} makes before a resubmission, offered to the caller that has
     * to make it <em>before</em> it builds the lines a submission needs, because an order that can no longer be
     * built (amended, refunded, reclassified since) must not hide a receipt the tax authority already has.
     *
     * @return an {@code ISSUED} submission carrying the evidence when the provider holds a receipt; empty when it
     *     holds none yet, cannot be asked, or this provider has no such read (the default)
     */
    default Optional<FiscalSubmission> heldReceipt(FiscalDocument document, ProviderBinding binding) {
        return Optional.empty();
    }
}
