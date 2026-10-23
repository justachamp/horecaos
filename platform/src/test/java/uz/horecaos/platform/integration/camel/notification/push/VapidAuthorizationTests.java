package uz.horecaos.platform.integration.camel.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The VAPID header (RFC 8292) a push service reads (ADR 0182, D14).
 *
 * <p>A signature cannot be pinned to bytes, because ECDSA draws a fresh nonce each time. What can be
 * pinned is what a push service does with it: split the token, read the claims, and verify the
 * signature with the public key the browser subscribed under. The verification here is the JCA's own,
 * given the raw {@code R || S} form JOSE uses, so it also proves the DER conversion.
 */
class VapidAuthorizationTests {

    private static final Base64.Decoder URL = Base64.getUrlDecoder();
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    @Test
    void theTokenNamesTheServiceItIsForAndIsSignedByTheKeyTheBrowserSubscribedUnder() throws Exception {
        KeyPair keys = WebPushEncryption.generateKeyPair();
        String publicKey = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryption.uncompressed((ECPublicKey) keys.getPublic()));

        String header = VapidAuthorization.header(
                URI.create("https://fcm.googleapis.com/fcm/send/abc"),
                "mailto:push@horecaos.uz",
                publicKey,
                (ECPrivateKey) keys.getPrivate(),
                NOW);

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + publicKey);
        String token = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(URL.decode(parts[0]), StandardCharsets.UTF_8))
                .isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        assertThat(new String(URL.decode(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":"
                        + NOW.plusSeconds(12 * 3600).getEpochSecond() + ",\"sub\":\"mailto:push@horecaos.uz\"}");

        byte[] raw = URL.decode(parts[2]);
        assertThat(raw).hasSize(64);
        assertThat(verifies(parts[0] + "." + parts[1], raw, (ECPublicKey) keys.getPublic()))
                .isTrue();
        // and not under another key
        assertThat(verifies(parts[0] + "." + parts[1], raw, (ECPublicKey)
                        WebPushEncryption.generateKeyPair().getPublic()))
                .isFalse();
    }

    @Test
    void theAudienceKeepsAnUnusualPortAndDropsTheUsualOne() {
        KeyPair keys = WebPushEncryption.generateKeyPair();
        String withPort = claims(VapidAuthorization.header(
                URI.create("https://push.example:8443/x"),
                "mailto:a@b.uz",
                "k",
                (ECPrivateKey) keys.getPrivate(),
                NOW));
        String standard = claims(VapidAuthorization.header(
                URI.create("https://push.example:443/x"), "mailto:a@b.uz", "k", (ECPrivateKey) keys.getPrivate(), NOW));

        assertThat(withPort).contains("\"aud\":\"https://push.example:8443\"");
        assertThat(standard).contains("\"aud\":\"https://push.example\"");
    }

    @Test
    void everySignatureIsExactlySixtyFourOctetsHoweverShortTheIntegersAre() {
        KeyPair keys = WebPushEncryption.generateKeyPair();
        for (int i = 0; i < 200; i++) {
            assertThat(VapidAuthorization.sign("input-" + i, (ECPrivateKey) keys.getPrivate()))
                    .hasSize(64);
        }
    }

    private static String claims(String header) {
        String token = header.substring("vapid t=".length(), header.indexOf(", k="));
        return new String(URL.decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static boolean verifies(String signingInput, byte[] raw, ECPublicKey key) throws Exception {
        byte[] der = rawToDer(raw);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signature.verify(der);
    }

    /** Written independently of the production conversion, so the two cannot share a mistake. */
    private static byte[] rawToDer(byte[] raw) {
        byte[] r = integer(java.util.Arrays.copyOfRange(raw, 0, 32));
        byte[] s = integer(java.util.Arrays.copyOfRange(raw, 32, 64));
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    private static byte[] integer(byte[] fixed) {
        return new java.math.BigInteger(1, fixed).toByteArray();
    }
}
