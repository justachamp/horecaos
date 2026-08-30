package uz.qoida.platform.customers.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The single-use secret a successful verification hands back (ADR 0015,
 * ADR 0003).
 *
 * <p>What it stands for: <em>the bearer proved control of this number, for this
 * brand, at this moment</em>. It is not a session and cannot be used as one —
 * Keycloak mints sessions (ADR 0003), and an in-house token would be the in-house
 * identity provider that ADR rejected outright. This is the thing an authenticator
 * redeems on the way to a session, and the thing that carries the proof across the
 * gap between "the code was right" and "a principal exists".
 *
 * <p>256 bits from a CSPRNG, stored only as a SHA-256 digest. The plaintext exists
 * once, in the response that issued it.
 *
 * <p><strong>A bare digest rather than a password KDF or a keyed MAC, and both are
 * decisions.</strong> A work factor exists to buy time against a dictionary, and a
 * value drawn uniformly from 2^256 has no dictionary — it would cost latency on
 * every sign-in and buy nothing. A keyed MAC would resist a database-only leak
 * better, but redemption presents the secret and nothing else: there is no tenant
 * to key by until the row has already been found, and ADR 0029's {@code lookupHash}
 * is per-tenant by construction. The same reasoning is recorded on the dine-in
 * bearer token, and it is restated rather than shared because a module that reached
 * into another module's domain package would be the boundary violation
 * {@code ModularArchitectureTests} exists to catch.
 *
 * <p>Note the contrast with the six-digit code, which <em>is</em> stored under a
 * keyed MAC. There the domain is a million values, the tenant is known before the
 * lookup, and a bare digest would be enumerable from a database dump in
 * milliseconds. Two secrets, two threat models, two constructions.
 */
public final class VerificationGrantSecret {

    /** 256 bits. This is an authentication credential, not a lookup key. */
    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationGrantSecret() {
    }

    /** A freshly minted secret and the digest to store for it. */
    public record Issued(String plaintext, String hash) {
    }

    public static Issued issue() {
        byte[] material = new byte[SECRET_BYTES];
        RANDOM.nextBytes(material);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        return new Issued(plaintext, hash(plaintext));
    }

    /** The stored form of a presented secret: lower-case hex, sixty-four characters. */
    public static String hash(String plaintext) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every JRE. If it is genuinely absent, the
            // platform cannot verify anybody and must not continue as though it
            // could.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
