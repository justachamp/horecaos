package uz.horecaos.platform.iam.application.terminals;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns a PIN, an activation code and a session token into the values stored for them (ADR 0155).
 *
 * <p>A PIN and an activation code are keyed hashes: {@code HMAC-SHA256(pepper, tenant | member | version | secret)}.
 * Not a slow hash: against a million six-digit PINs a slow hash adds nothing the lockout does not already add, and the
 * key is what keeps a database dump from being a list of working PINs. The residual risk, that whoever holds the
 * database <em>and</em> the pepper can recover any PIN in seconds, is accepted because that person can already read
 * the platform (ADR 0155, Decision 3). The tenant, the member and the PIN's version are mixed in so one digest cannot be
 * replayed against another person, another tenant, or the same person's PIN after it was replaced.
 *
 * <p>A session token is a 256-bit random value, so a plain SHA-256 is enough and needs no key.
 *
 * <p>Comparison is {@link MessageDigest#isEqual}, constant time: a short-circuiting {@code equals} on a value an
 * attacker can retry leaks the matching prefix.
 */
@Component
public class PinDigests {

    private static final String ALGORITHM = "HmacSHA256";

    private final TerminalPinPepper pepper;

    public PinDigests(TerminalPinPepper pepper) {
        this.pepper = pepper;
    }

    public int currentPepperVersion() {
        return pepper.currentVersion();
    }

    /** The digest of {@code pin} under the pepper of {@code pepperVersion}; null when that version is gone. */
    @Nullable
    String pinDigest(UUID tenantId, UUID memberId, int pinVersion, String pin, int pepperVersion) {
        byte[] key = pepper.key(pepperVersion);
        return key == null ? null : hmac(key, "pin", tenantId, memberId, pinVersion, pin);
    }

    /** The digest of an activation code, always under the current pepper: a code lives for hours, not across a rotation. */
    String activationDigest(UUID tenantId, UUID memberId, String code) {
        return hmac(pepper.currentKey(), "activation", tenantId, memberId, 0, code);
    }

    static boolean matches(@Nullable String stored, @Nullable String computed) {
        if (stored == null || computed == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), computed.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of an opaque session token, hex. */
    public static String tokenDigest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Objects.requireNonNull(token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hmac(byte[] key, String domain, UUID tenantId, UUID memberId, int version, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update(domain.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(tenantId.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(memberId.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(Integer.toString(version).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }
}
