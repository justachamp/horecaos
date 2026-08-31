package uz.horecaos.platform.iam.api.secrets;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * A resolved secret (ADR 0028).
 *
 * <p>The redacted {@code toString} is not cosmetic. It is the last defence
 * against a credential reaching a log line, an exception message, or a
 * serialized error response, which is how secrets usually escape.
 */
public final class SecretValue {

    private final char[] value;

    private SecretValue(char[] value) {
        this.value = value;
    }

    public static SecretValue of(String value) {
        return new SecretValue(
                Objects.requireNonNull(value, "A secret value is required").toCharArray());
    }

    /** Exposes the value to the caller that needs it. Never log the result. */
    public String reveal() {
        return new String(value);
    }

    /**
     * A UTF-8 copy for callers that must clear the material afterwards, byte for
     * byte what {@code reveal().getBytes(UTF_8)} would produce.
     *
     * <p>Exists because a {@code String} cannot be zeroed: it lives until the
     * collector happens to reach it, and a heap dump taken in between still has
     * the key in it. Key derivation is the caller that matters here, and it
     * clears what this returns.
     */
    public byte[] revealBytes() {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) {
            // The encoder sizes its buffer for the worst case, so the copy it
            // hands back is usually larger than the secret and is about to be
            // dropped with the secret still in it. arrayOffset() is respected
            // rather than assumed zero, since array() alone says nothing about
            // where this buffer's own view into it starts.
            byte[] backing = encoded.array();
            Arrays.fill(backing, encoded.arrayOffset(), backing.length, (byte) 0);
        }
        return bytes;
    }

    /** Clears the backing array where the runtime allows it. */
    public void dispose() {
        Arrays.fill(value, '\0');
    }

    @Override
    public String toString() {
        return "SecretValue[REDACTED]";
    }

    /**
     * Compares in time that does not depend on how much of the secret matched.
     *
     * <p>{@code Arrays.equals} stops at the first differing character, which
     * turns any caller that compares an attacker-supplied value against a stored
     * one — a webhook signature, a shared-secret header — into an oracle that
     * recovers the secret one character at a time.
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SecretValue secret)) {
            return false;
        }
        byte[] mine = revealBytes();
        byte[] theirs = secret.revealBytes();
        try {
            return MessageDigest.isEqual(mine, theirs);
        } finally {
            Arrays.fill(mine, (byte) 0);
            Arrays.fill(theirs, (byte) 0);
        }
    }

    /**
     * Constant, and deliberately carries nothing of the value.
     *
     * <p>A content hash would be a 32-bit oracle: anyone able to hash candidates
     * could confirm a guess without ever reaching {@code equals}, and a hash
     * printed by a collection's {@code toString} would be a partial credential in
     * a log. Nothing keys a map by a secret, so the degenerate bucket costs
     * nothing.
     */
    @Override
    public int hashCode() {
        return 0;
    }
}
