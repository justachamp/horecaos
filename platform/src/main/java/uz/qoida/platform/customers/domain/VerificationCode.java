package uz.qoida.platform.customers.domain;

import java.security.SecureRandom;

/**
 * The one-time code itself (ADR 0015).
 *
 * <p>Six digits, drawn from a CSPRNG. Six is a product decision as much as a
 * security one — it is what an Uzbek customer expects to be read out of an SMS —
 * and it is why the code is worth almost nothing on its own: twenty bits of
 * entropy is guessable in a million tries, so the security lives in the five
 * attempts a challenge allows, the five minutes it lives, and the per-destination
 * issuance budget. This class exists to make sure the twenty bits are at least
 * real.
 *
 * <p>{@link SecureRandom#nextInt(int)} rather than {@code nextInt() % 1_000_000}:
 * the modulus of a uniform 32-bit draw is not uniform over a million, and the
 * low codes would be drawn slightly more often forever. The bias is small enough
 * to be invisible and large enough to be worth not having.
 */
public final class VerificationCode {

    /** Digits in a code. Changing it changes what customers are asked to type. */
    public static final int LENGTH = 6;

    private static final int UPPER_BOUND = 1_000_000;

    private static final String ZERO_PADDED = "%0" + LENGTH + "d";

    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCode() {
    }

    /**
     * A fresh code, zero-padded so every code is the same width.
     *
     * <p>The padding matters beyond tidiness: without it {@code 000123} would be
     * sent as {@code 123}, and a customer typing what they were sent would fail
     * against a MAC computed over the padded form.
     */
    public static String issue() {
        return ZERO_PADDED.formatted(RANDOM.nextInt(UPPER_BOUND));
    }

    /**
     * Whether a submitted value could be a code at all.
     *
     * <p>Checked before anything is spent on it, so a client sending a whole
     * paragraph does not consume one of five attempts. Deliberately not a check
     * that the code is <em>right</em> — that comparison happens against the stored
     * MAC and costs an attempt however it turns out.
     */
    public static boolean isWellFormed(String candidate) {
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (candidate.charAt(index) < '0' || candidate.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }
}
