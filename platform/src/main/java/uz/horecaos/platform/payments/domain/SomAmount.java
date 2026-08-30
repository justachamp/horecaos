package uz.horecaos.platform.payments.domain;

import java.util.Objects;

/**
 * Money as the platform holds it: whole som, with its currency (ADR 0018).
 *
 * <p>This type exists to make one class of bug impossible to write. The wire is
 * not consistent with the platform or with itself — Click's SHOP API amount and
 * its payment calls are som, Click's fiscalization {@code Price} and {@code VAT}
 * are tiyin, and every Payme amount that has ever existed is tiyin. The same
 * logical amount is som in Click's payment call and tiyin in Click's fiscal call
 * <em>for that same payment</em>.
 *
 * <p>So a bare {@code long} may not cross an adapter boundary. Every provider
 * method takes a {@link SomAmount} or a {@link TiyinAmount}, and the single
 * multiplication by 100 in the codebase is {@link TiyinAmount#of(SomAmount)}.
 * A som value passed where tiyin is expected does not compile, which turns a
 * hundredfold overcharge from a support ticket into a build failure.
 *
 * @param value    whole som; never a fraction, never a double, never a float
 * @param currency ISO 4217, upper case
 */
public record SomAmount(long value, String currency) {

    public SomAmount {
        Objects.requireNonNull(currency, "A currency is required");
        currency = currency.strip().toUpperCase(java.util.Locale.ROOT);
        if (currency.length() != 3) {
            throw new IllegalArgumentException("A currency must be a three-letter ISO 4217 code");
        }
        if (value < 0) {
            throw new IllegalArgumentException("A som amount must not be negative");
        }
    }

    public static SomAmount of(long value, String currency) {
        return new SomAmount(value, currency);
    }

    /**
     * Adds, refusing a mixed-currency sum.
     *
     * <p>Refused rather than converted: this platform has no exchange rate and
     * two currencies in one total is a configuration error somewhere upstream,
     * most plausibly a branch priced from a book in another currency.
     */
    public SomAmount plus(SomAmount other) {
        requireSameCurrency(other);
        return new SomAmount(Math.addExact(value, other.value), currency);
    }

    public SomAmount minus(SomAmount other) {
        requireSameCurrency(other);
        return new SomAmount(Math.subtractExact(value, other.value), currency);
    }

    public boolean isZero() {
        return value == 0;
    }

    public boolean isLessThan(SomAmount other) {
        requireSameCurrency(other);
        return value < other.value;
    }

    /**
     * Exact equality on the amount, after the currencies have been checked.
     *
     * <p>Both Click reference implementations compare the callback amount against
     * the order total with a floating-point tolerance of 0.01, and Django's does
     * it through a misplaced parenthesis that lets <em>underpayment pass</em>.
     * Parsing to whole som and comparing integers removes both problems at once.
     */
    public boolean matches(SomAmount other) {
        requireSameCurrency(other);
        return value == other.value;
    }

    private void requireSameCurrency(SomAmount other) {
        Objects.requireNonNull(other, "An amount is required");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine " + currency + " with " + other.currency);
        }
    }

    @Override
    public String toString() {
        return value + " " + currency;
    }
}
