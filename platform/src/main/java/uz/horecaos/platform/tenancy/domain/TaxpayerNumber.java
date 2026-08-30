package uz.horecaos.platform.tenancy.domain;

import java.util.Objects;

/**
 * An Uzbek taxpayer identification number — ИНН / СТИР (ADR 0038).
 *
 * <p>Nine digits, and the type exists so that the check happens once rather than
 * in every screen that captures one. This is the number that appears on the
 * customer's fiscal receipt as the seller, so a transposed digit is not a
 * validation nicety: it names a different company as the party that took the
 * money.
 *
 * <p>Deliberately not a PINFL. A PINFL is fourteen digits and identifies a
 * natural person; ADR 0038's seller is a company, and the one place a personal
 * identifier could legitimately appear is Click's per-line {@code CommissionInfo},
 * which is an open input to that ADR and has no writer here. Accepting both
 * lengths in one type would let a fourteen-digit value reach the
 * {@code legal_entities.tin} column that the database constrains to nine.
 *
 * <p>No checksum. Uzbek INN check-digit rules are not published in a form this
 * codebase can cite, and a guessed algorithm would reject valid numbers — which
 * is worse than accepting a wrong one, because a rejected onboarding has no
 * workaround while a wrong INN is visible on the first receipt and correctable.
 */
public record TaxpayerNumber(String value) {

    private static final int DIGITS = 9;

    public TaxpayerNumber {
        Objects.requireNonNull(value, "A taxpayer number is required");
        value = value.strip();
        if (value.length() != DIGITS || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "An Uzbek taxpayer number is %d digits: '%s' is not".formatted(DIGITS, value));
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
