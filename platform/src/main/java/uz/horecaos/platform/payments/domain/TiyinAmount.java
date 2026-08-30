package uz.horecaos.platform.payments.domain;

import java.util.Objects;

/**
 * Money in the subunit both providers' wires use (ADR 0013).
 *
 * <p>One som is one hundred tiyin. {@link #of(SomAmount)} is the only
 * multiplication by 100 on money anywhere in the codebase, and {@link #toSom()}
 * is the only division: a second one appearing elsewhere is how half a receipt
 * ends up in the wrong unit after an otherwise harmless refactor.
 *
 * <p>Which side of the boundary a value is on is readable from its type, which is
 * the point. A method taking a {@code TiyinAmount} cannot be handed a som figure
 * by mistake, and an adapter that forgets to scale fails to compile rather than
 * charging a customer a hundred times the price.
 */
public record TiyinAmount(long value, String currency) {

    private static final long TIYIN_PER_SOM = 100L;

    public TiyinAmount {
        Objects.requireNonNull(currency, "A currency is required");
        currency = currency.strip().toUpperCase(java.util.Locale.ROOT);
        if (value < 0) {
            throw new IllegalArgumentException("A tiyin amount must not be negative");
        }
    }

    /** The conversion. There is no other. */
    public static TiyinAmount of(SomAmount som) {
        Objects.requireNonNull(som, "An amount is required");
        return new TiyinAmount(Math.multiplyExact(som.value(), TIYIN_PER_SOM), som.currency());
    }

    /**
     * Reads a tiyin figure that arrived from a provider back into som.
     *
     * <p>Refuses a value that is not a whole number of som rather than rounding
     * it. UZS is transacted in whole som and ADR 0018 stores whole som, so a
     * fractional result means the platform and the provider disagree about what
     * was charged — and silently rounding that away is how the disagreement
     * survives to the settlement file.
     */
    public SomAmount toSom() {
        if (value % TIYIN_PER_SOM != 0) {
            throw new IllegalArgumentException("A provider amount of " + value + " tiyin is not a whole number of som");
        }
        return new SomAmount(value / TIYIN_PER_SOM, currency);
    }

    @Override
    public String toString() {
        return value + " tiyin " + currency;
    }
}
