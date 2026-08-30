package uz.horecaos.platform.dinein.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The opaque bearer tokens behind a table's QR code and behind the guest session
 * a scan is exchanged for (ADR 0047).
 *
 * <p>Both are 128 bits from a CSPRNG, rendered Base64url without padding, and both
 * are stored only as a SHA-256 digest. The plaintext exists once — in the response
 * that issued it, and on a piece of card in the case of the table token — and is
 * never written anywhere else.
 *
 * <p>A digest rather than a password KDF, and that is a decision rather than an
 * omission. Argon2 exists to buy time against a dictionary, and a token drawn
 * uniformly from 2^128 has no dictionary: a work factor would buy nothing and
 * would be paid on every scan, at a table, on a phone, on restaurant wifi. A keyed
 * digest would resist a database-only leak better, but the key would have to be
 * one global key — the scan presents a token and nothing else, so there is no
 * tenant to key by until after the lookup, and ADR 0029's {@code lookupHash} is
 * per-tenant by construction.
 *
 * <p>The token encodes nothing. Not a table id, not a location, not a sequence
 * number, not a tenant — ADR 0047 forbids all of them, because a guest at table 7
 * who can read their own code can read table 8's, and because a value a client can
 * decode is a value a client will try to edit. Every binding lives in the row the
 * digest finds.
 */
public final class BearerToken {

    /**
     * 128 bits. Enough that enumerating the space is not a threat model, which is
     * what lets the per-token rate limit below be about abuse of one photographed
     * code rather than about guessing.
     */
    private static final int TOKEN_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BearerToken() {}

    /** A freshly minted token and the digest to store for it. */
    public record Issued(String plaintext, String hash) {}

    public static Issued issue() {
        byte[] material = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(material);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        return new Issued(plaintext, hash(plaintext));
    }

    /**
     * The stored form of a presented token.
     *
     * <p>Lower-case hex, sixty-four characters, which V0034 constrains on both
     * columns so a malformed digest cannot be stored and then never match.
     */
    public static String hash(String plaintext) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every JRE. If it is genuinely absent, the
            // platform cannot authenticate a scan at all and must not continue as
            // though it could.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
