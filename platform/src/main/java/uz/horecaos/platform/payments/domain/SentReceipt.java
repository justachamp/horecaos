package uz.horecaos.platform.payments.domain;

import java.util.List;
import java.util.Objects;

/**
 * What an adapter actually put on the wire when it asked a provider for a receipt
 * (ADR 0166, ADR 0013).
 *
 * <p>Present on a {@link FiscalSubmission} only when the request <em>left</em>. A Click
 * submission deferred because no payment id exists yet, and one refused for having no lines, sent
 * nothing, and recording "sent lines" for either would be evidence of a request that never
 * happened. A request the provider then refused, or whose answer was lost, did leave, and is kept:
 * the exact lines are what makes an incorrect or contested receipt explicable a year later.
 *
 * <p>The tender split is the way the adapter told the provider the payment was paid (Click's
 * {@code received_card}, {@code received_cash}, {@code received_ecash}). HorecaOS does not split
 * tender inside one Click payment, so today the whole amount is {@code receivedCard}; the split is
 * recorded because that is a fact about the filing and not an assumption about it.
 */
public record SentReceipt(
        List<FiscalReceiptLine> lines, SomAmount receivedCard, SomAmount receivedCash, SomAmount receivedEcash) {

    public SentReceipt {
        lines = lines == null ? List.of() : List.copyOf(lines);
        Objects.requireNonNull(receivedCard, "The card share of the split is required");
        Objects.requireNonNull(receivedCash, "The cash share of the split is required");
        Objects.requireNonNull(receivedEcash, "The e-cash share of the split is required");
    }
}
