package uz.qoida.platform.ordering.domain;

/**
 * What a cancellation does to the stock the order held (ADR 0039, ADR 0017).
 *
 * <p>This closes ADR 0017's open input on cancellation restock, and it closes it
 * on the reason rather than on a checkbox in the cancel dialog. Under pressure an
 * operator picks whatever closes the dialog fastest, and the write-off rate then
 * becomes noise instead of a number the kitchen can act on.
 *
 * <p>The disposition only decides anything after the reservation has been
 * committed. Before that, cancellation always releases and the disposition is
 * ignored — a hold that was never turned into a sale has nothing to write off.
 */
public enum StockDisposition {

    /** Give the hold back. The only answer before the reservation was committed. */
    RELEASE,

    /** Put committed stock back on the shelf: an ADR 0017 return movement. */
    RETURN_TO_STOCK,

    /** The food was made and cannot be sold again: an ADR 0017 waste movement. */
    WRITE_OFF,

    /** Nothing moves. Correct only for an {@code UNTRACKED} item. */
    NO_EFFECT;

    /** Whether this disposition asks inventory to append a movement. */
    public boolean writesMovement() {
        return this == RETURN_TO_STOCK || this == WRITE_OFF;
    }
}
