package uz.qoida.platform.web.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An opaque, signed pagination cursor (ADR 0031).
 *
 * <p>Offset pagination is not offered: Operations lists change constantly, and
 * offsets silently skip and duplicate rows while a user is paging. In an order
 * feed that means a missed order.
 *
 * <p>The cursor is signed and carries a hash of the filter set, so changing
 * filters mid-iteration fails loudly instead of returning incoherent pages, and
 * a hand-edited cursor cannot walk another tenant's data.
 */
public record Cursor(String sortKey, String filterHash) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String SEPARATOR = "|";

    public Cursor {
        Objects.requireNonNull(sortKey, "A sort key is required");
        Objects.requireNonNull(filterHash, "A filter hash is required");
        if (sortKey.contains(SEPARATOR)) {
            throw new IllegalArgumentException("A sort key must not contain the cursor separator");
        }
    }

    public String encode(CursorSigner signer) {
        String payload = sortKey + SEPARATOR + filterHash;
        return ENCODER.encodeToString((payload + SEPARATOR + signer.sign(payload))
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and verifies a cursor.
     *
     * @return the cursor, or empty when it is malformed, unsigned, or was issued
     *         for a different filter set
     */
    public static Optional<Cursor> decode(String encoded, String expectedFilterHash, CursorSigner signer) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        String[] parts = decoded.split("\\" + SEPARATOR, 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        String payload = parts[0] + SEPARATOR + parts[1];
        if (!signer.verify(payload, parts[2])) {
            return Optional.empty();
        }
        if (!parts[1].equals(expectedFilterHash)) {
            return Optional.empty();
        }
        return Optional.of(new Cursor(parts[0], parts[1]));
    }

    /** Signs and verifies cursor payloads. */
    public interface CursorSigner {

        String sign(String payload);

        default boolean verify(String payload, String signature) {
            return java.security.MessageDigest.isEqual(
                    sign(payload).getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        }

        static CursorSigner hmacSha256(byte[] key) {
            return payload -> {
                try {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(key, "HmacSHA256"));
                    return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
                } catch (java.security.GeneralSecurityException exception) {
                    throw new IllegalStateException("Cursor signing failed", exception);
                }
            };
        }
    }
}
