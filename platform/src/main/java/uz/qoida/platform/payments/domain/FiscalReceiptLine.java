package uz.qoida.platform.payments.domain;

import java.util.List;
import java.util.Objects;

/**
 * One line of a fiscal receipt, in Qoida's terms (ADR 0038).
 *
 * <p>This carries <strong>unit price and quantity</strong>, and the line total is
 * derived. That is deliberate, and it is the shape that makes the sharpest trap in
 * this integration survivable: Click's {@code Price} is the <em>line total</em>
 * and Payme's {@code price} is the <em>unit price</em>. Same word, a factor of
 * quantity apart. Each adapter therefore builds its own wire lines from this
 * neutral shape, and a shared line builder between the two would fiscalize an
 * order at quantity squared times its value.
 *
 * <p>{@link #taxAmount()} comes from the accepted quote's recorded tax share and
 * is never recomputed at fiscalization time: recomputing it means the receipt can
 * disagree with the sale it is evidence of.
 *
 * @param vatPercent whole percent, which is all either provider's integer field
 *                   can express. ADR 0018's basis points must therefore be a
 *                   multiple of 100 or the adapter rejects the tax profile rather
 *                   than rounding it
 * @param markingCodes Click's {@code Labels}. Payme has no field for these at all,
 *                     so a marked good cannot lawfully be fiscalized through
 *                     Payme's {@code detail} object, and a tenant selling one must
 *                     have Payme removed from the channel's payment methods
 */
public record FiscalReceiptLine(
        String fiscalName,
        String mxikCode,
        String packageCode,
        Long unitCode,
        int quantity,
        SomAmount unitPrice,
        SomAmount taxAmount,
        int vatPercent,
        SomAmount discount,
        String barcode,
        List<String> markingCodes,
        String commissionTin,
        String commissionPinfl) {

    public FiscalReceiptLine {
        Objects.requireNonNull(fiscalName, "A fiscal name is required");
        Objects.requireNonNull(mxikCode, "An MXIK code is required on every receipt line");
        Objects.requireNonNull(packageCode, "A package code is required on every receipt line");
        Objects.requireNonNull(unitPrice, "A unit price is required");
        Objects.requireNonNull(taxAmount, "A tax share is required; it comes from the quote");
        markingCodes = markingCodes == null ? List.of() : List.copyOf(markingCodes);
        if (quantity < 1) {
            throw new IllegalArgumentException("A receipt line needs a positive quantity");
        }
        if (vatPercent < 0) {
            throw new IllegalArgumentException("A VAT percent cannot be negative");
        }
    }

    /**
     * Quantity times unit price.
     *
     * <p>Named for what it is so that neither adapter has to decide what
     * {@code price} means. The Click adapter sends this; the Payme adapter sends
     * {@link #unitPrice()}.
     */
    public SomAmount lineTotal() {
        return new SomAmount(
                Math.multiplyExact(unitPrice.value(), (long) quantity), unitPrice.currency());
    }

    public boolean marked() {
        return !markingCodes.isEmpty();
    }
}
