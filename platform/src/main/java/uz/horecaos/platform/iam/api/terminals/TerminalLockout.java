package uz.horecaos.platform.iam.api.terminals;

import java.time.Duration;

/**
 * The lockout numbers of ADR 0155, Decision 3. Code constants and not a tenant's setting: a security control a tenant can
 * weaken is not one. The Policy card states them beside the PIN-length choice so a tenant sees what it is choosing.
 */
public final class TerminalLockout {

    /** Five consecutive wrong PINs lock that person on that device... */
    public static final int DEVICE_CONSECUTIVE_FAILURES = 5;

    /** ...for fifteen minutes. */
    public static final Duration DEVICE_LOCK = Duration.ofMinutes(15);

    /** Twelve wrong PINs in a rolling 24 hours, across every device, lock the credential until it is reset. */
    public static final int CREDENTIAL_FAILURES_PER_DAY = 12;

    public static final Duration FAILURE_WINDOW = Duration.ofHours(24);

    private TerminalLockout() {}

    /**
     * The odds of guessing a PIN of this length inside one rolling day, as «one in N»: twelve tries against {@code 10^length}
     * values. Twelve a day against a million six-digit PINs is about 1 in 83,000; against ten thousand four-digit PINs about 1 in
     * 830 -- the price of choosing four.
     */
    public static long guessOneIn(int pinLength) {
        long space = (long) Math.pow(10, pinLength);
        return space / CREDENTIAL_FAILURES_PER_DAY;
    }
}
