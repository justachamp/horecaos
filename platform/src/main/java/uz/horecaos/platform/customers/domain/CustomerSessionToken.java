package uz.horecaos.platform.customers.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The bearer a signed-in customer holds (ADR 0051).
 *
 * <p>256 bits from a CSPRNG behind a fixed prefix, stored only as a SHA-256
 * digest of the whole presented string. The plaintext exists once, in the
 * response that issued it.
 *
 * <p><strong>The prefix is not decoration.</strong> The platform's resource
 * server accepts exactly one JWT issuer, and it must never be offered one of
 * these: a customer token handed to the JWT decoder fails, and the 401 that comes
 * back describes a malformed token rather than the actual situation. The prefix
 * is what lets {@code CustomerSessionBearerTokenResolver} decide, before any
 * decoding is attempted, which of the two principal models a request belongs to.
 * It also survives being pasted into a support conversation as an obviously
 * HorecaOS-issued value, which shortens the conversation about what it is.
 *
 * <p>A version segment because rotating the construction — a longer secret, a
 * different digest — has to be possible without a flag day. Tokens carrying the
 * old prefix can be refused by name on the day that happens.
 *
 * <p><strong>A bare digest rather than a password KDF or a keyed MAC.</strong>
 * The same reasoning {@link VerificationGrantSecret} records, and it is stronger
 * here: this value is presented on <em>every</em> request a signed-in customer
 * makes, so a work factor would be paid per page view to buy nothing against a
 * value drawn uniformly from 2^256, which has no dictionary. A keyed MAC would
 * resist a database-only leak better, but resolution presents the token and
 * nothing else — there is no tenant to key by until the row has been found, and
 * ADR 0029's {@code lookupHash} is per-tenant by construction.
 *
 * <p>The token encodes nothing. Not an account, not a tenant, not an expiry.
 * Every one of those is a column on the row the digest finds, which is what stops
 * a client editing one.
 */
public final class CustomerSessionToken {

    /**
     * What every customer session token starts with.
     *
     * <p>Chosen to be impossible to confuse with a JWT, which is Base64url of a
     * JSON header and therefore always begins {@code eyJ}.
     */
    public static final String PREFIX = "qcs1.";

    /** 256 bits. This is an authentication credential, not a lookup key. */
    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CustomerSessionToken() {
    }

    /** A freshly minted token and the digest to store for it. */
    public record Issued(String plaintext, String hash) {

        /** A record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "Issued[hash=" + hash + "]";
        }
    }

    public static Issued issue() {
        byte[] material = new byte[SECRET_BYTES];
        RANDOM.nextBytes(material);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        return new Issued(plaintext, hash(plaintext));
    }

    /**
     * Whether a presented string is shaped like one of ours at all.
     *
     * <p>Asked before anything is looked up, so that a staff JWT arriving on a
     * customer path is routed to the resource server rather than probed against
     * this table — and so that the resolver's decision is made on the string's
     * own shape rather than on whether a database query happened to miss.
     */
    public static boolean looksLikeOne(String presented) {
        return presented != null && presented.startsWith(PREFIX)
                && presented.length() > PREFIX.length();
    }

    /** The stored form of a presented token: lower-case hex, sixty-four characters. */
    public static String hash(String plaintext) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every JRE. If it is genuinely absent, no
            // customer can be authenticated and the platform must not continue as
            // though one could.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
