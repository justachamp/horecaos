package uz.qoida.platform.payments.infrastructure.click;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Click's {@code merchant_prepare_id}, derived rather than minted (ADR 0013).
 *
 * <p>Click types this field {@code int} and hands it straight back on Complete.
 * Both reference implementations answer with the order's own primary key, which
 * makes a repeated Prepare return the same value and create no second row — and
 * their agreement is the best evidence available, because Click documents nothing
 * about repeated Prepare at all. <strong>Whether Click guarantees at-most-once
 * Prepare is an open question with CLICK.</strong>
 *
 * <p>Qoida's keys are UUIDs and Click's field is a 32-bit integer, so the value
 * cannot be the key itself. It is a stable derivation of it: the first four bytes
 * of {@code SHA-256(attempt id)}, with the sign bit cleared. Deterministic across
 * restarts and across nodes, which is the property that matters — a fresh id per
 * Prepare would break Complete, because Complete carries exactly one
 * {@code merchant_prepare_id} and there would be no way to know which Prepare it
 * belonged to.
 *
 * <p>The 32-bit space means two attempts can collide, and that is deliberately
 * tolerated. This value is never a lookup key: Complete is resolved by
 * {@code merchant_trans_id}, which is a full UUID, and this is checked against the
 * attempt that lookup found. A collision therefore produces a wrong
 * {@code -6 Transaction does not exist} on a request that was already for a
 * different order — not a payment credited to the wrong one.
 */
public final class ClickPrepareId {

    private ClickPrepareId() {
    }

    public static int forAttempt(UUID attemptId) {
        byte[] digest = sha256(attemptId.toString());
        int value = ((digest[0] & 0xFF) << 24)
                | ((digest[1] & 0xFF) << 16)
                | ((digest[2] & 0xFF) << 8)
                | (digest[3] & 0xFF);
        // Cleared rather than negated: Click types the field as an int and a
        // negative one would be indistinguishable from an error code to anyone
        // reading a support ticket.
        return value & 0x7FFFFFFF;
    }

    /**
     * Whether the value Click sent back is the one this attempt was told.
     *
     * <p>Compared as text, because it arrives as text and a Prepare id with a
     * leading zero or a stray space is a mismatch worth answering {@code -6} to
     * rather than one to normalise away.
     */
    public static boolean matches(UUID attemptId, String received) {
        return received != null && Integer.toString(forAttempt(attemptId)).equals(received.strip());
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
