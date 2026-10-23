package uz.horecaos.platform.integration.camel.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Web Push message encryption (RFC 8291) against the worked example in the RFC's Appendix A (ADR 0182,
 * D14).
 *
 * <p>A round trip would pass for an encryption that the browsers cannot read, so each step is pinned to
 * the number the RFC prints: the shared secret, the combined key material, the content key and the
 * nonce. The decryption used to close the loop is a second, separate implementation of the user agent's
 * half, and it also reads the RFC's own sender key.
 */
class WebPushEncryptionTests {

    private static final Base64.Decoder URL = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();

    // RFC 8291 Appendix A.
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
    private static final String IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg";
    private static final String CEK = "oIhVW04MRdy2XN9CiKLxTg";
    private static final String NONCE = "4h_95klXJ5E_qnoN";

    private static KeyPair senderKeys() throws Exception {
        ECPrivateKey privateKey = WebPushEncryption.decodePrivateKey(URL.decode(AS_PRIVATE));
        ECPublicKey publicKey = WebPushEncryption.decodePublicKey(URL.decode(AS_PUBLIC));
        return new KeyPair(publicKey, privateKey);
    }

    @Test
    void theDerivationReproducesEveryNumberTheRfcPrints() throws Exception {
        WebPushEncryption.Encrypted encrypted = WebPushEncryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                URL.decode(UA_PUBLIC),
                URL.decode(AUTH_SECRET),
                senderKeys(),
                URL.decode(SALT));

        WebPushEncryption.Derivation derivation = encrypted.derivation();
        assertThat(ENCODE.encodeToString(derivation.ecdhSecret())).isEqualTo(ECDH_SECRET);
        assertThat(ENCODE.encodeToString(derivation.ikm())).isEqualTo(IKM);
        assertThat(ENCODE.encodeToString(derivation.contentEncryptionKey())).isEqualTo(CEK);
        assertThat(ENCODE.encodeToString(derivation.nonce())).isEqualTo(NONCE);
    }

    @Test
    void theBodyCarriesTheHeaderTheRfcDescribes() throws Exception {
        byte[] body = WebPushEncryption.encrypt(
                        PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                        URL.decode(UA_PUBLIC),
                        URL.decode(AUTH_SECRET),
                        senderKeys(),
                        URL.decode(SALT))
                .body();

        // salt (16), record size 4096 as four big-endian octets, key id length 65, the sender's public key.
        assertThat(Arrays.copyOfRange(body, 0, 16)).isEqualTo(URL.decode(SALT));
        assertThat(Arrays.copyOfRange(body, 16, 20)).containsExactly(0, 0, 16, 0);
        assertThat(body[20]).isEqualTo((byte) 65);
        assertThat(Arrays.copyOfRange(body, 21, 86)).isEqualTo(URL.decode(AS_PUBLIC));
        // the plaintext, the delimiter and the 16 octet tag
        assertThat(body.length - 86).isEqualTo(PLAINTEXT.length() + 1 + 16);
    }

    @Test
    void theBrowsersHalfReadsWhatWasSent() throws Exception {
        byte[] body = WebPushEncryption.encrypt(
                        PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                        URL.decode(UA_PUBLIC),
                        URL.decode(AUTH_SECRET),
                        senderKeys(),
                        URL.decode(SALT))
                .body();

        byte[] read = WebPushEncryption.decrypt(
                body,
                WebPushEncryption.decodePrivateKey(URL.decode(UA_PRIVATE)),
                URL.decode(UA_PUBLIC),
                URL.decode(AUTH_SECRET));

        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
    }

    @Test
    void anEphemeralKeyAndSaltMakeTwoBodiesOfOneMessageDiffer() throws Exception {
        byte[] first = WebPushEncryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8), URL.decode(UA_PUBLIC), URL.decode(AUTH_SECRET));
        byte[] second = WebPushEncryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8), URL.decode(UA_PUBLIC), URL.decode(AUTH_SECRET));

        assertThat(first).isNotEqualTo(second);
        ECPrivateKey uaPrivate = WebPushEncryption.decodePrivateKey(URL.decode(UA_PRIVATE));
        for (byte[] body : new byte[][] {first, second}) {
            assertThat(new String(
                            WebPushEncryption.decrypt(body, uaPrivate, URL.decode(UA_PUBLIC), URL.decode(AUTH_SECRET)),
                            StandardCharsets.UTF_8))
                    .isEqualTo(PLAINTEXT);
        }
    }

    @Test
    void aWrongAuthSecretCannotReadTheBody() throws Exception {
        byte[] body = WebPushEncryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8), URL.decode(UA_PUBLIC), URL.decode(AUTH_SECRET));

        assertThatThrownBy(() -> WebPushEncryption.decrypt(
                        body,
                        WebPushEncryption.decodePrivateKey(URL.decode(UA_PRIVATE)),
                        URL.decode(UA_PUBLIC),
                        new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPayloadThatDoesNotFitOneRecordIsRefusedAndOneThatJustFitsIsNot() {
        byte[] justFits = new byte[WebPushEncryption.MAX_PLAINTEXT];
        byte[] body = WebPushEncryption.encrypt(justFits, URL.decode(UA_PUBLIC), URL.decode(AUTH_SECRET));
        // the whole HTTP body stays inside the 4096 octets every push service must accept
        assertThat(body.length).isEqualTo(4096);

        assertThatThrownBy(() -> WebPushEncryption.encrypt(
                        new byte[WebPushEncryption.MAX_PLAINTEXT + 1], URL.decode(UA_PUBLIC), URL.decode(AUTH_SECRET)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSubscriptionKeyOffTheCurveIsRefusedBeforeAnySecretIsDerived() {
        byte[] offCurve = URL.decode(UA_PUBLIC);
        offCurve[64] ^= 1;

        assertThat(WebPushEncryption.isValidPublicKey(URL.decode(UA_PUBLIC))).isTrue();
        assertThat(WebPushEncryption.isValidPublicKey(offCurve)).isFalse();
        assertThat(WebPushEncryption.isValidPublicKey(new byte[65])).isFalse();
        assertThat(WebPushEncryption.isValidPublicKey(new byte[10])).isFalse();
        assertThatThrownBy(() -> WebPushEncryption.encrypt(
                        "x".getBytes(StandardCharsets.UTF_8), offCurve, URL.decode(AUTH_SECRET)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
