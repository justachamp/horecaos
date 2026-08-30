package uz.horecaos.platform.partner.domain;

import java.util.Objects;

/**
 * The money a partner claims, and the only thing HorecaOS checks about it
 * (ADR 0040).
 *
 * <p>ADR 0018 promises that the same context and clock always produce the same
 * total, and that every total is reconstructible from stored evidence. An
 * aggregator's total is not: the discount was computed on the partner's side,
 * from campaign data HorecaOS is not given, funded partly out of the partner's own
 * margin. Re-deriving it would refuse every legitimate promotional order, and
 * admitting it unchecked fills reconciliation with rows nobody can explain. So
 * the totals are stored verbatim and exactly one property is enforced:
 *
 * <pre>{@code total = subtotal + tax + fee - discount}</pre>
 *
 * <p>which is the same reconciliation {@code ordering.orders} already carries, so
 * an externally priced order satisfies the platform's own arithmetic rather than
 * an exemption from it.
 *
 * <p>All amounts are integer minor units. For UZS a minor unit is a whole som,
 * so there are no fractions to lose and no reason for a double to appear
 * anywhere in this path.
 *
 * @param taxMinor null when the partner stated no tax, which is not the same as
 *                 zero tax. Most aggregator protocols report gross prices and no
 *                 tax line, and writing zero there would be a VAT claim nobody
 *                 made. It is treated as zero for the arithmetic and stays null
 *                 in the column.
 */
public record ExternalTotals(
        String currency,
        long customerPaidTotalMinor,
        long subtotalMinor,
        long discountMinor,
        long feeMinor,
        Long taxMinor) {

    public ExternalTotals {
        Objects.requireNonNull(currency, "A currency is required");
        currency = currency.strip().toUpperCase(java.util.Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("A currency is an ISO 4217 alphabetic code");
        }
        if (customerPaidTotalMinor < 0
                || subtotalMinor < 0
                || discountMinor < 0
                || feeMinor < 0
                || (taxMinor != null && taxMinor < 0)) {
            throw new IllegalArgumentException("Partner amounts are non-negative minor units");
        }
    }

    /** The tax figure the arithmetic uses; the column keeps the partner's null. */
    public long effectiveTaxMinor() {
        return taxMinor == null ? 0L : taxMinor;
    }

    /**
     * Whether the parts sum to the stated total. The ingestion refuses a push
     * that fails this, and the same rule is a CHECK constraint on
     * {@code ordering.order_external_pricing} — twice on purpose, because the
     * service is what gives the partner a usable rejection code and the
     * constraint is what stops a later import path bypassing it.
     */
    public boolean reconciles() {
        return customerPaidTotalMinor == subtotalMinor + effectiveTaxMinor() + feeMinor - discountMinor;
    }

    /**
     * The net figure {@code ordering.orders.subtotal_minor} is booked at, so an
     * externally priced order satisfies {@code ck_order_total_reconciles} with
     * the same numbers the partner sent rather than with derived ones.
     */
    public long bookedSubtotalMinor() {
        return subtotalMinor;
    }
}
