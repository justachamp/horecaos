package uz.horecaos.platform.partner.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a handover code into the value stored beside the order (ADR 0040).
 *
 * <p>A handover code in a readable column is a code anyone with a read replica
 * can use, and a read replica is the least-guarded copy of any production
 * database. So the column holds an HMAC under a pepper that lives in the ADR
 * 0028 secret store and never in the database, which means a stolen table dump
 * is not a set of working codes.
 *
 * <p>HMAC rather than a bare digest, because the code space is small — four or
 * six digits at every aggregator that issues one — and an unkeyed SHA-256 over a
 * four-digit code is a ten-thousand-entry rainbow table somebody builds in a
 * second. The order id is mixed in as well, so one order's hash cannot be
 * replayed against another order that happens to have drawn the same code.
 *
 * <p>Comparison is {@link MessageDigest#isEqual}, which is constant time. A
 * short-circuiting {@code equals} on a code an attacker can retry leaks the
 * matching prefix, and the whole value of the challenge is that the wrong person
 * cannot produce the right answer.
 */
public final class HandoverCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    /**
     * @param pepper resolved from the ADR 0028 secret store at startup. Never a
     *               literal, never a property with a default, and never
     *               committed — ADR 0028's rule, and the reason this constructor
     *               takes bytes rather than reading configuration itself.
     */
    public HandoverCodeHasher(byte[] pepper) {
        Objects.requireNonNull(pepper, "A handover pepper is required");
        if (pepper.length < 16) {
            throw new IllegalArgumentException("A handover pepper shorter than 16 bytes is not worth having");
        }
        this.pepper = pepper.clone();
    }

    /**
     * @param orderId bound into the digest so a hash cannot be lifted from one
     *                order and replayed against another
     */
    public String hash(java.util.UUID orderId, String code) {
        Objects.requireNonNull(orderId, "An order id is required");
        String normalised = normalise(code);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            mac.update(orderId.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    public boolean matches(java.util.UUID orderId, String storedHash, String attempt) {
        if (storedHash == null || attempt == null || attempt.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                hash(orderId, attempt).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Couriers read codes back with spaces and hyphens in them, and a branch that
     * has to type the separators exactly is a branch that bypasses the check.
     * Normalising here rather than at the call site means the stored hash and
     * every attempt agree on one form.
     */
    private static String normalise(String code) {
        Objects.requireNonNull(code, "A handover code is required");
        String trimmed = code.strip().replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A handover code is required");
        }
        return trimmed;
    }
}
