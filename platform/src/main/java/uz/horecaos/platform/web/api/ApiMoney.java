package uz.horecaos.platform.web.api;

import java.util.Currency;
import java.util.Objects;

/**
 * The wire representation of money (ADR 0031).
 *
 * <p>Money is always an object. A bare number is never a money value: it loses
 * the currency, and a client that guesses the scale eventually charges someone
 * a hundred times too much. Integer minor units avoid the rounding drift that
 * makes a total impossible to reproduce.
 */
public record ApiMoney(long amountMinor, String currency) {

    public ApiMoney {
        Objects.requireNonNull(currency, "A currency is required");
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be an ISO 4217 alphabetic code: " + currency);
        }
        // Rejects a well-formed but non-existent code such as "XYZ".
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency: " + currency, unknown);
        }
    }

    public static ApiMoney of(long amountMinor, String currency) {
        return new ApiMoney(amountMinor, currency);
    }

    public ApiMoney plus(ApiMoney other) {
        requireSameCurrency(other);
        return new ApiMoney(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public ApiMoney minus(ApiMoney other) {
        requireSameCurrency(other);
        return new ApiMoney(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    private void requireSameCurrency(ApiMoney other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine %s with %s".formatted(currency, other.currency));
        }
    }
}
