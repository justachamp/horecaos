package uz.horecaos.platform.payments.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One line of a fiscal receipt, in HorecaOS's terms (ADR 0038).
 *
 * <p>This carries <strong>unit price and quantity</strong>, and the line total is
 * derived. That is deliberate, and it is the shape that makes the sharpest trap in
 * this integration survivable: Click's {@code Price} is the <em>line total</em>
 * and Payme's {@code price} is the <em>unit price</em>. Same word, a factor of
 * quantity apart. Each adapter therefore builds its own wire lines from this
 * neutral shape, and a shared line builder between the two would fiscalize an
 * order at quantity squared times its value.
 *
 * <p><strong>The quantity is a decimal</strong> (ADR 0166, Decision 9, ADR 0137): a weighed line is
 * sold by the kilogram and its count is {@code 1.040}. The invariant that keeps this exact is that
 * {@code unitPrice × quantity} is a whole minor unit: the line builder picks the unit price so it
 * is, and carries the remainder in {@link #discount()}, so that no provider is ever asked to round
 * a receipt that is supposed to equal the payment to the minor unit.
 *
 * <p>{@link #taxAmount()} comes from the accepted quote's recorded tax share and
 * is never recomputed at fiscalization time: recomputing it means the receipt can
 * disagree with the sale it is evidence of. (The one place it is derived again is a points
 * redemption, which ADR 0046 says moves the VAT base; that is {@code ReceiptLineAllocator}'s, in
 * one named place.)
 *
 * @param quantity the count as the provider is told it: whole for a piece, decimal for a weighed
 *     line. Positive
 * @param vatPercent whole percent, which is all either provider's integer field
 *                   can express. ADR 0018's basis points must therefore be a
 *                   multiple of 100 or the adapter rejects the tax profile rather
 *                   than rounding it
 * @param markingCodes Click's {@code Labels}. Payme has no field for these at all,
 *                     so a marked good cannot lawfully be fiscalized through
 *                     Payme's {@code detail} object, and a tenant selling one must
 *                     have Payme removed from the channel's payment methods
 * @param kind what the line is for, so the evidence says «the delivery fee» about the one line that
 *     is that and nothing else
 */
public record FiscalReceiptLine(
        String fiscalName,
        String mxikCode,
        String packageCode,
        @Nullable Long unitCode,
        BigDecimal quantity,
        SomAmount unitPrice,
        SomAmount taxAmount,
        int vatPercent,
        @Nullable SomAmount discount,
        @Nullable String barcode,
        List<String> markingCodes,
        @Nullable String commissionTin,
        @Nullable String commissionPinfl,
        Kind kind) {

    /** What a line is for. */
    public enum Kind {

        /** A priced item: a dish, a modifier option, anything on the menu. */
        ITEM,

        /**
         * The delivery fee, as an ordinary item line (ADR 0038). Never Payme's {@code shipping}, which
         * carries no code, no package code and no VAT percent.
         */
        DELIVERY_FEE
    }

    public FiscalReceiptLine {
        Objects.requireNonNull(fiscalName, "A fiscal name is required");
        Objects.requireNonNull(mxikCode, "An MXIK code is required on every receipt line");
        Objects.requireNonNull(packageCode, "A package code is required on every receipt line");
        Objects.requireNonNull(quantity, "A quantity is required");
        Objects.requireNonNull(unitPrice, "A unit price is required");
        Objects.requireNonNull(taxAmount, "A tax share is required; it comes from the quote");
        Objects.requireNonNull(kind, "A line kind is required");
        markingCodes = markingCodes == null ? List.of() : List.copyOf(markingCodes);
        quantity = quantity.stripTrailingZeros();
        if (quantity.scale() < 0) {
            // 10, not 1E+1: the count is written into evidence and onto the wire as a plain number.
            quantity = quantity.setScale(0);
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("A receipt line needs a positive quantity");
        }
        if (vatPercent < 0) {
            throw new IllegalArgumentException("A VAT percent cannot be negative");
        }
        if (BigDecimal.valueOf(unitPrice.value())
                        .multiply(quantity)
                        .stripTrailingZeros()
                        .scale()
                > 0) {
            throw new IllegalArgumentException(
                    "A line's unit price times its quantity must be a whole minor unit, or no provider can add it up exactly");
        }
    }

    /**
     * A whole-count line, which is every line there was before a quantity could be a decimal.
     *
     * <p>Kept so that a caller with an {@code int} says so; the quantity is the same number either way.
     */
    public FiscalReceiptLine(
            String fiscalName,
            String mxikCode,
            String packageCode,
            @Nullable Long unitCode,
            int quantity,
            SomAmount unitPrice,
            SomAmount taxAmount,
            int vatPercent,
            @Nullable SomAmount discount,
            @Nullable String barcode,
            List<String> markingCodes,
            @Nullable String commissionTin,
            @Nullable String commissionPinfl) {
        this(
                fiscalName,
                mxikCode,
                packageCode,
                unitCode,
                BigDecimal.valueOf(quantity),
                unitPrice,
                taxAmount,
                vatPercent,
                discount,
                barcode,
                markingCodes,
                commissionTin,
                commissionPinfl,
                Kind.ITEM);
    }

    /**
     * Quantity times unit price: a whole minor unit, by the invariant above.
     *
     * <p>Named for what it is so that neither adapter has to decide what
     * {@code price} means. The Click adapter sends this; the Payme adapter sends
     * {@link #unitPrice()}.
     */
    public SomAmount lineTotal() {
        return new SomAmount(
                BigDecimal.valueOf(unitPrice.value()).multiply(quantity).longValueExact(), unitPrice.currency());
    }

    /** What the customer pays for this line: the total less its discount. */
    public SomAmount netTotal() {
        SomAmount total = lineTotal();
        return discount == null ? total : total.minus(discount);
    }

    public boolean marked() {
        return !markingCodes.isEmpty();
    }

    /** Whether the count is not a whole number, which Click's {@code uint64} {@code Amount} cannot say on its own. */
    public boolean fractional() {
        return quantity.scale() > 0;
    }
}
