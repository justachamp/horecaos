package uz.horecaos.platform.tenancy.domain.channel;

import java.security.SecureRandom;

/**
 * DNS-TXT ownership challenges for a custom channel hostname (row 10.5,
 * {@code docs/operations-spec/settings.md}'s WEB section: "Domain
 * verification is DNS TXT, not credential handover").
 *
 * <p>The record an operator is asked to create is always named {@code
 * _horecaos-challenge.<their hostname>}, never the bare hostname itself --
 * writing straight to the apex would collide with whatever the tenant's own
 * DNS provider already serves there (an A/CNAME/MX record, or nothing), and a
 * dedicated subdomain label is the same pattern every other TXT-based
 * domain-ownership check (ACME's {@code dns-01}, SPF/DKIM verifiers) already
 * uses.
 */
public final class HostnameChallenges {

    private static final String RECORD_PREFIX = "_horecaos-challenge.";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 20;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HostnameChallenges() {}

    /** The TXT record name a hostname's challenge must be published under. */
    public static String recordName(String hostname) {
        return RECORD_PREFIX + hostname;
    }

    /**
     * A fresh, unguessable challenge token. Not a secret in the ADR 0028
     * sense -- it is meant to be copied into a public DNS record -- but still
     * drawn from {@link SecureRandom} so a caller who has never seen it
     * cannot predict it and claim the hostname on the tenant's behalf.
     */
    public static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return "horecaos-verify-" + hex;
    }
}
