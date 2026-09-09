package uz.horecaos.platform.iam.application.devices;

import java.security.SecureRandom;

/**
 * The short, human-typeable code a device shows on its own screen for a
 * manager to read off and enter (ADR 0079).
 *
 * <p>Crockford's Base32 alphabet minus the usual confusables (already dropped
 * by the alphabet itself: {@code I}, {@code L}, {@code O}, {@code U}), eight
 * characters grouped as {@code XXXX-XXXX} for the same reason a phone number
 * is grouped — not a security property, the group is not even part of the
 * stored value, only something a person can read back correctly under
 * kitchen lighting without a magnifying glass. This code is not the
 * enrolment's real credential: it identifies a pending request to a manager
 * who is about to authorize it from their own session, is spent the instant
 * it is approved, and a guessed one grants an attacker nothing beyond
 * naming which pending row to approve — approving still needs {@code
 * kitchen.station.manage} at that location.
 */
final class EnrolmentUserCode {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private EnrolmentUserCode() {}

    /** Eight characters, no separator — the caller decides how to render it for display. */
    static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
