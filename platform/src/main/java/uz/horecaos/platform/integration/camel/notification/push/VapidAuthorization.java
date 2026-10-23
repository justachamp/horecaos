package uz.horecaos.platform.integration.camel.notification.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * The application server's identity to a push service: a VAPID header (RFC 8292), an ES256 JSON Web
 * Token that names the push service it is for, who to contact about it, and when it lapses (ADR 0182,
 * D14).
 *
 * <p>Built by hand on JCA, as {@link WebPushEncryption} is: the token has three fixed fields, and the
 * one place a library would help (turning a DER signature into the fixed-width pair JOSE wants) is a
 * dozen lines. The private key is never held here; the caller passes it for one call.
 */
final class VapidAuthorization {

    /** A token lives half a day: long enough to be reused by a burst, far under the 24 hours RFC 8292 allows. */
    static final Duration LIFETIME = Duration.ofHours(12);

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private VapidAuthorization() {}

    /**
     * The {@code Authorization} header value for a push to {@code endpoint}.
     *
     * @param subject {@code mailto:} or {@code https:} contact the push service may use
     * @param publicKey the application server key, base64url, exactly as the browser subscribed with it
     */
    static String header(URI endpoint, String subject, String publicKey, ECPrivateKey key, Instant now) {
        String audience = endpoint.getScheme() + "://" + authority(endpoint);
        String header = URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = URL.encodeToString(("{\"aud\":\"" + escape(audience) + "\",\"exp\":"
                        + now.plus(LIFETIME).getEpochSecond() + ",\"sub\":\"" + escape(subject) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        return "vapid t=" + signingInput + "." + URL.encodeToString(sign(signingInput, key)) + ", k=" + publicKey;
    }

    /** ES256: the ECDSA signature as {@code R || S}, 32 octets each, not the DER the JCA produces. */
    static byte[] sign(String signingInput, ECPrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return derToRaw(signature.sign());
        } catch (GeneralSecurityException failure) {
            // No message: the cause would describe the key.
            throw new IllegalStateException("The VAPID token could not be signed");
        }
    }

    /** A DER {@code SEQUENCE { INTEGER r, INTEGER s }} as fixed-width {@code r || s}. */
    static byte[] derToRaw(byte[] der) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset += der[1] & 0x7F;
        }
        byte[] raw = new byte[64];
        for (int half = 0; half < 2; half++) {
            int length = der[offset + 1] & 0xFF;
            int start = offset + 2;
            // A leading zero keeps the integer positive; a short integer is padded on the left.
            while (length > 32 && der[start] == 0) {
                start++;
                length--;
            }
            System.arraycopy(der, start, raw, half * 32 + (32 - length), length);
            offset = start + length;
        }
        return raw;
    }

    /** Host and, when it is not the scheme's own, the port. Lower case, as the push service compares it. */
    private static String authority(URI endpoint) {
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        int port = endpoint.getPort();
        boolean standard = port == -1 || ("https".equals(endpoint.getScheme()) && port == 443);
        return standard ? host : host + ":" + port;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
