package uz.horecaos.platform.iam.application.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The {@code deviceCode} an unenrolled device polls with (ADR 0079).
 *
 * <p>The identical shape and reasoning as {@code
 * uz.horecaos.platform.customers.domain.VerificationGrantSecret}: 256 bits
 * from a CSPRNG, stored only as a bare SHA-256 digest, never a password KDF
 * or a keyed MAC. A work factor buys time against a dictionary and a value
 * drawn uniformly from 2^256 has none; a keyed MAC would resist a
 * database-only leak better, but this secret is looked up by its own hash
 * with no tenant known yet to key by, exactly {@code
 * VerificationGrantSecret}'s own case. Restated here rather than imported,
 * because a module reaching into another module's domain package is the
 * boundary violation {@code ModularArchitectureTests} exists to catch.
 */
final class DeviceEnrolmentSecret {

    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceEnrolmentSecret() {}

    record Issued(String plaintext, String hash) {}

    static Issued issue() {
        byte[] material = new byte[SECRET_BYTES];
        RANDOM.nextBytes(material);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        return new Issued(plaintext, hash(plaintext));
    }

    static String hash(String plaintext) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
