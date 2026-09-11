package uz.horecaos.platform.web.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

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
        return ENCODER.encodeToString((payload + SEPARATOR + signer.sign(payload)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and verifies a cursor.
     *
     * @return the cursor, or empty when it is malformed, unsigned, or was issued
     *         for a different filter set
     */
    public static Optional<Cursor> decode(@Nullable String encoded, String expectedFilterHash, CursorSigner signer) {
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

    /**
     * Encodes a cursor with no signature (ADR 0102).
     *
     * <p>The interim form, for the lists that page today while the platform has
     * no {@link CursorSigner} bean. {@code AuditController},
     * {@code FailureOperationsController}, the migration console and the tenant
     * directory each carry a raw identifier as their cursor for the same reason;
     * this at least keeps the filter set pinned, which a raw identifier cannot.
     *
     * <p>What the missing signature gives up is narrow and worth stating: a
     * hand-edited token cannot be told from a minted one, so a caller can hand
     * itself an incoherent window. What it does <strong>not</strong> give up is
     * scope — no cursor in this codebase carries a tenant, a brand or a
     * location, because those come from the path and the ADR 0025 capability
     * check on every request, so an edited cursor reaches nothing its holder
     * could not already read.
     */
    public String encodeUnsigned() {
        return ENCODER.encodeToString((sortKey + SEPARATOR + filterHash).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor minted by {@link #encodeUnsigned()}.
     *
     * @return the cursor, or empty when it is malformed or was issued for a
     *         different filter set — the caller answers both the same way,
     *         because "this token is not usable here" is the whole of what a
     *         client can act on
     */
    public static Optional<Cursor> decodeUnsigned(@Nullable String encoded, String expectedFilterHash) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        String[] parts = decoded.split("\\" + SEPARATOR, 2);
        if (parts.length != 2 || !parts[1].equals(expectedFilterHash)) {
            return Optional.empty();
        }
        return Optional.of(new Cursor(parts[0], parts[1]));
    }

    /** Signs and verifies cursor payloads. */
    public interface CursorSigner {

        String sign(String payload);

        default boolean verify(String payload, String signature) {
            return java.security.MessageDigest.isEqual(
                    sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
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
