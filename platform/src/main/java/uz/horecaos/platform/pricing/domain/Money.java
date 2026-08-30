package uz.horecaos.platform.pricing.domain;

import java.util.Objects;

/**
 * An amount in integer minor units (ADR 0018).
 *
 * <p>Never a {@code double}. Binary floating point cannot represent most decimal
 * amounts exactly, so a total computed twice can differ in the last unit — and a
 * quote's entire promise is that it is reproducible.
 *
 * <p>For UZS a minor unit is a whole som. Tiyin are obsolete in practice and both
 * payment providers settle in whole som, so storing a sub-unit would be precision
 * nobody can pay.
 */
public record Money(long minor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "A currency is required");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("A currency must be an ISO 4217 code");
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money times(int quantity) {
        return new Money(Math.multiplyExact(minor, (long) quantity), currency);
    }

    public boolean isNegative() {
        return minor < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            // Currencies are never converted implicitly. A quote that silently
            // added som to dollars would produce a plausible, wrong total.
            throw new IllegalArgumentException(
                    "Cannot combine %s and %s".formatted(currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return minor + " " + currency;
    }
}
