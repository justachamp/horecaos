package uz.horecaos.platform.iam.application.terminals;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a PIN may not be, refused at entry with the reason stated (ADR 0155, Decision 3).
 *
 * <p>Three kinds of weak PIN, none of them a policy a tenant can loosen: a run of one digit, an ascending or descending
 * run of the whole length, and a short deny-list of the values people pick first. A fourth rule -- a PIN equal to the
 * last digits of the person's own phone or employee number -- needs the person's decrypted record, so {@code PinService}
 * asks for it and hands the tails here as data.
 *
 * <p>The PIN is never derived from, or stored beside, anything about the person; this class only says no.
 */
public final class PinRules {

    /** Reasons a PIN is refused. Stable: the shell renders its own sentence per code. */
    public enum Refusal {
        NOT_DIGITS,
        WRONG_LENGTH,
        REPEATED_DIGIT,
        SEQUENCE,
        COMMON,
        PERSONAL_NUMBER
    }

    private static final Set<String> COMMON = Set.of(
            // The four ADR 0155 names, and the four-digit forms of the same habits.
            "123456", "654321", "000000", "121212", "1234", "4321", "0000", "1212");

    private PinRules() {}

    /**
     * @param personalTails the last {@code pin.length()} digits of the person's phone and employee number, if any
     * @return the first reason the PIN is refused, or null when it is acceptable
     */
    public static @Nullable Refusal check(String pin, int requiredLength, Set<String> personalTails) {
        if (!pin.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Refusal.NOT_DIGITS;
        }
        if (pin.length() != requiredLength) {
            return Refusal.WRONG_LENGTH;
        }
        if (pin.chars().distinct().count() == 1) {
            return Refusal.REPEATED_DIGIT;
        }
        if (isRun(pin, 1) || isRun(pin, -1)) {
            return Refusal.SEQUENCE;
        }
        if (COMMON.contains(pin)) {
            return Refusal.COMMON;
        }
        if (personalTails.contains(pin)) {
            return Refusal.PERSONAL_NUMBER;
        }
        return null;
    }

    private static boolean isRun(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}
