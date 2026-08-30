package uz.qoida.platform.customers.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.stereotype.Component;

import uz.qoida.platform.iam.api.protection.FieldProtection;

/**
 * How a one-time code is stored, and how a submitted one is compared to it
 * (ADR 0015, ADR 0028, ADR 0029).
 *
 * <p><strong>The code is never stored.</strong> What the row holds is a keyed MAC
 * over it. A code kept in any form it can be read back from is a password in
 * plaintext for the five minutes it is alive, and a database dump, a backup, a
 * replica or a support query would each be enough to sign in as somebody else.
 *
 * <p><strong>Keyed, not merely hashed, and not a password KDF.</strong> Six digits
 * is a domain of one million. An unkeyed SHA-256 over that is enumerable from a
 * table dump in milliseconds, so the digest would be the code. A slow KDF —
 * Argon2, bcrypt — is the usual answer to a small domain, and it is the wrong one
 * here: it would have to be cheap enough to run on every verification attempt of
 * every customer, which puts the affordable work factor well below what a million
 * candidates costs an attacker with a GPU. What actually closes the gap is a key
 * the database does not contain: the MAC key is derived from the ADR 0028
 * key-encryption key and lives in the secrets manager, so a dump of
 * {@code customer.verification_challenges} yields nothing to attack.
 *
 * <p><strong>The challenge id is folded into the value, not into the key.</strong>
 * It gives the same per-challenge salt — two live challenges holding the same six
 * digits store different values, so a dump shows no repeats and no cross-challenge
 * precomputation helps. Folding it into the {@code lookupDomain} instead would
 * have given every challenge its own derived key, and
 * {@code EnvelopeFieldProtection} caches derived lookup keys in a map: an
 * unauthenticated caller would then have been able to grow that map by one entry
 * per request.
 */
@Component
public class CodeProtection {

    /** Fixed, so the derived-key cache stays bounded. See the class comment. */
    private static final String LOOKUP_DOMAIN = "customer.verification.code";

    private final FieldProtection protection;

    public CodeProtection(FieldProtection protection) {
        this.protection = protection;
    }

    /** The stored form of a code. */
    public String hash(UUID tenantId, UUID challengeId, String code) {
        return protection.lookupHash(tenantId, LOOKUP_DOMAIN, challengeId + ":" + code);
    }

    /**
     * Whether a submitted code matches the stored MAC, in constant time.
     *
     * <p>{@code String.equals} short-circuits on the first differing character, and
     * over a six-digit secret a prefix oracle collapses a million guesses into
     * sixty. The MACs being compared are of equal, fixed length, so
     * {@link MessageDigest#isEqual} compares them without leaking where they
     * diverge.
     */
    public boolean matches(UUID tenantId, UUID challengeId, String submittedCode, String storedHash) {
        return MessageDigest.isEqual(
                hash(tenantId, challengeId, submittedCode).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
