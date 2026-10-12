package uz.horecaos.platform.fiscal.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;

/**
 * What was filed, as the evidence surface keeps it (ADR 0166, ADR 0029).
 *
 * <p>The exact lines a provider was given and the way it was told the payment was split are
 * stored behind ADR 0029 protected references on {@code fiscal.fiscal_documents}, because what
 * is worth keeping about an incorrect receipt is what was <em>sent</em> and not a reconstruction.
 * {@code payments} writes them when it submits; {@code fiscal} reads them for the evidence
 * surface; this class is the one place both agree on the shape, so neither can drift from the
 * other.
 *
 * <p><strong>No marking code is in either document, ever.</strong> A line carries the
 * <em>number</em> of codes it transmitted ({@link Line#markingCodeCount}) and nothing that
 * identifies one: ADR 0161 keeps every code off every screen and gives it no reveal, and a
 * format that could carry one would be a format somebody eventually fills. The structural test
 * that no marking code is reachable from an evidence response is built on this.
 */
public final class SentReceiptEvidence {

    /** The version both documents carry, so a reader can refuse a shape it does not know. */
    public static final int VERSION = 1;

    /** An ordinary priced line. */
    public static final String KIND_ITEM = "ITEM";

    /** The bridge's one-line stand-in for the order total (ADR 0166, Decision 6). Never a sale. */
    public static final String KIND_SYNTHETIC = "SYNTHETIC";

    /** The delivery fee as an ordinary item line, marked as such. Written by the real line builder. */
    public static final String KIND_DELIVERY_FEE = "DELIVERY_FEE";

    /**
     * One line of a receipt as filed.
     *
     * <p>Money is whole som in the platform's own minor unit (ADR 0018), not Click's tiyin: the
     * adapter converts at its boundary and this is what the platform means by the amount.
     *
     * @param ikpu              the product classifier sent, as sent: not what the catalogue says today
     * @param markingCodeCount  how many marking codes this line transmitted (Click's {@code Labels});
     *                          the codes themselves are not here
     * @param commissionTin     the seller's TIN in Click's {@code CommissionInfo}, a business
     *                          identifier. Null on a provider that has no such field
     */
    public record Line(
            String kind,
            String name,
            String ikpu,
            String packageCode,
            @Nullable Long unitCode,
            int quantity,
            long unitPriceMinor,
            long taxAmountMinor,
            int vatPercent,
            @Nullable Long discountMinor,
            @Nullable String barcode,
            int markingCodeCount,
            @Nullable String commissionTin) {}

    /** The lines of one filed receipt. */
    public record Lines(int version, String currency, boolean synthetic, List<Line> lines) {

        public Lines {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** How the provider was told the payment was split: Click's {@code received_card / _cash / _ecash}. */
    public record TenderSplit(
            int version, String currency, long receivedCardMinor, long receivedCashMinor, long receivedEcashMinor) {}

    /** Where the protected sent request sits, which is also part of what the ciphertext is bound to. */
    public static RecordRef requestRecord(UUID documentId) {
        return new RecordRef("fiscal.fiscal_documents", "protected_request_reference", documentId);
    }

    /** Where the protected sent tender split sits. */
    public static RecordRef splitRecord(UUID documentId) {
        return new RecordRef("fiscal.fiscal_documents", "sent_tender_split_reference", documentId);
    }

    private SentReceiptEvidence() {}
}
