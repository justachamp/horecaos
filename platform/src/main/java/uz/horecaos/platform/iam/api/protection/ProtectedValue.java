package uz.horecaos.platform.iam.api.protection;

import java.util.Base64;
import java.util.Objects;

/**
 * Ciphertext together with everything needed to decrypt it later (ADR 0029).
 *
 * <p>The key identifier travels with the value so rotation never requires
 * guessing which key produced a record, and old and new keys can coexist while
 * a background re-encryption proceeds.
 */
public record ProtectedValue(String keyId, String algorithm, byte[] nonce, byte[] ciphertext, int aadVersion) {

    public ProtectedValue {
        Objects.requireNonNull(keyId, "A key id is required");
        Objects.requireNonNull(algorithm, "An algorithm is required");
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /** Compact storage form for a single column. */
    public String serialize() {
        Base64.Encoder encoder = Base64.getEncoder();
        return String.join(
                "$",
                "v" + aadVersion,
                keyId,
                algorithm,
                encoder.encodeToString(nonce),
                encoder.encodeToString(ciphertext));
    }

    public static ProtectedValue deserialize(String stored) {
        String[] parts =
                Objects.requireNonNull(stored, "A stored value is required").split("\\$");
        if (parts.length != 5 || !parts[0].startsWith("v")) {
            throw new IllegalArgumentException("Malformed protected value");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        return new ProtectedValue(
                parts[1],
                parts[2],
                decoder.decode(parts[3]),
                decoder.decode(parts[4]),
                Integer.parseInt(parts[0].substring(1)));
    }

    @Override
    public String toString() {
        // Never render ciphertext or nonce; a protected value in a log is still
        // material an attacker with the key can use.
        return "ProtectedValue[keyId=%s, algorithm=%s]".formatted(keyId, algorithm);
    }
}
