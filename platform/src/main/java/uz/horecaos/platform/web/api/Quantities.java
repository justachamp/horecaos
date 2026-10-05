package uz.horecaos.platform.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * How a line quantity is represented and multiplied (ADR 0137).
 *
 * <p>{@code ordering.order_lines.quantity} was an integer until ADR 0137 widened it
 * to {@code numeric(10,3)} so that a splittable variant can be ordered by the
 * portion (0.5 of a plov) and a weighed one by the unit it is sold in. Every reader
 * of that column now holds a {@link BigDecimal}, and this class is the one place
 * the three questions that change with the type are answered:
 *
 * <ul>
 *   <li><b>Equality.</b> {@code new BigDecimal("2.000").equals(new BigDecimal("2"))}
 *       is false, which would make two identical baskets hash differently and two
 *       equal records compare unequal. {@link #normalise} strips trailing zeros and
 *       never leaves a negative scale, so a whole quantity is always {@code 2} and a
 *       portion is always {@code 0.5}.</li>
 *   <li><b>The wire.</b> An integral quantity serialises as {@code 2}, not {@code
 *       2.000}, so a basket of whole portions looks on the wire exactly as it did
 *       when the column was an integer.</li>
 *   <li><b>Money.</b> {@link #times} rounds {@code HALF_UP} to a whole minor unit once,
 *       on the line. For a whole quantity the product is exact, so an integer order's
 *       total is byte-identical to what the old {@code long * int} produced.</li>
 * </ul>
 *
 * <p>Money itself never becomes a decimal: a quantity multiplies a minor-unit
 * {@code long} and the result is a minor-unit {@code long} again.
 */
public final class Quantities {

    /** The fraction digits the column stores; an input with more is refused rather than rounded. */
    public static final int SCALE = 3;

    /** The largest value {@code numeric(10,3)} holds. */
    public static final BigDecimal MAX = new BigDecimal("9999999.999");

    /** The integer digits {@code numeric(10,3)} holds: ten digits, three of them fractions. */
    public static final int MAX_INTEGER_DIGITS = 7;

    private Quantities() {}

    /** A whole quantity. */
    public static BigDecimal of(long whole) {
        return BigDecimal.valueOf(whole);
    }

    /**
     * The canonical form of a quantity: no trailing zeros, never a negative scale.
     *
     * <p>{@code 2.000} and {@code 2} both become {@code 2}; {@code 20} stays {@code 20}
     * (a bare {@code stripTrailingZeros} would turn it into {@code 2E+1}).
     */
    public static BigDecimal normalise(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "A quantity is required");
        BigDecimal stripped = quantity.signum() == 0 ? BigDecimal.ZERO : quantity.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Whether the quantity is a whole number of units. */
    public static boolean isWhole(BigDecimal quantity) {
        return quantity.signum() == 0 || quantity.stripTrailingZeros().scale() <= 0;
    }

    /**
     * Whether a client-supplied quantity is small enough to be worth normalising: no more integer
     * digits than the column holds.
     *
     * <p>A {@link BigDecimal} carries its exponent separately from its digits, so {@code 1e600000000}
     * is a few bytes on the wire and in memory; {@link #normalise} gives it a scale of zero, which
     * writes out six hundred million digits and costs a request thread minutes of CPU and heap. Ask
     * this before {@link #normalise} anything a client sent. A tiny value with a huge positive scale
     * ({@code 1e-600000000}) is cheap to normalise and is left to the fraction-digit rule.
     */
    public static boolean hasBoundedMagnitude(BigDecimal quantity) {
        return quantity.precision() - quantity.scale() <= MAX_INTEGER_DIGITS;
    }

    /**
     * Refuses a client-supplied quantity of unbounded magnitude, before anything expands it.
     *
     * @throws IllegalArgumentException when the quantity could never fit the column
     */
    public static BigDecimal requireBoundedMagnitude(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "A quantity is required");
        if (!hasBoundedMagnitude(quantity)) {
            throw new IllegalArgumentException("A quantity is more than zero and at most " + MAX.toPlainString()
                    + ", with at most " + SCALE + " fraction digits");
        }
        return quantity;
    }

    /**
     * Whether a client-supplied quantity fits the column: positive, at most
     * {@value #SCALE} fraction digits, and no larger than {@link #MAX}.
     *
     * <p>The magnitude is checked before the quantity is normalised, so an absurd exponent is
     * answered without being expanded.
     */
    public static boolean fitsColumn(BigDecimal quantity) {
        return quantity.signum() > 0
                && hasBoundedMagnitude(quantity)
                && normalise(quantity).scale() <= SCALE
                && quantity.compareTo(MAX) <= 0;
    }

    /**
     * A unit price multiplied by a quantity, rounded once to a whole minor unit.
     *
     * <p>{@code HALF_UP}, the rounding every other money figure in the platform
     * already uses (tax extraction, apportionment). Exact for a whole quantity.
     */
    public static long times(long unitMinor, BigDecimal quantity) {
        BigDecimal product = BigDecimal.valueOf(unitMinor).multiply(quantity);
        try {
            return product.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException overflow) {
            throw new ArithmeticException("long overflow pricing " + unitMinor + " x " + quantity);
        }
    }

    /**
     * The whole units a quantity occupies when something can only be counted whole.
     *
     * <p>Stock holds are whole-unit (ADR 0017) and ADR 0137 leaves a fractional
     * reservation to the record that next touches inventory, so a half portion
     * holds one unit: rounding up can only over-hold, never oversell.
     */
    public static int wholeUnitsCeiling(BigDecimal quantity) {
        return quantity.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** The text a quantity is hashed and exported as: plain digits, never scientific notation. */
    public static String plain(BigDecimal quantity) {
        return normalise(quantity).toPlainString();
    }
}
