package uz.qoida.platform.courier.domain;

/**
 * A claim about a delivery-cost number, not a status (ADR 0042).
 *
 * <p>A total may only be taken over a single basis, and every delivery-cost
 * figure names the one it was taken at. The internal figure exists at delivery;
 * the partner figure exists when the invoice arrives, days or weeks later. A
 * same-day report summing both under-states partner cost and then jumps when
 * invoices land, and no reader can tell whether the jump is a cost increase or
 * an arrival.
 */
public enum CostBasis {

    ACCRUED,
    INVOICED,
    SETTLED;

    /**
     * {@code INVOICED} is meaningless on the internal path: a self-employed
     * courier's accrual becomes {@code SETTLED} when the period closes, and
     * there is no invoice from Qoida to itself in between.
     */
    public boolean validFor(CostPath path) {
        return path != CostPath.INTERNAL || this != INVOICED;
    }
}
