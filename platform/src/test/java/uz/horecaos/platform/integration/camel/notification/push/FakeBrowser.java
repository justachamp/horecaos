package uz.horecaos.platform.integration.camel.notification.push;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * The browser end of a Web Push subscription, for tests: the key pair and auth secret a real browser
 * generates when a customer subscribes, and the ability to read what a push service delivers to it.
 *
 * <p>Public so the notification tests in other packages can subscribe a "browser" and read the payload
 * the platform sent, without the production crypto class being widened for tests.
 */
public final class FakeBrowser {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keys = WebPushEncryption.generateKeyPair();
    private final byte[] authSecret = new byte[16];

    public FakeBrowser() {
        new SecureRandom().nextBytes(authSecret);
    }

    /** The subscription's {@code keys.p256dh}. */
    public String p256dh() {
        return URL.encodeToString(WebPushEncryption.uncompressed((ECPublicKey) keys.getPublic()));
    }

    /** The subscription's {@code keys.auth}. */
    public String auth() {
        return URL.encodeToString(authSecret);
    }

    /** What a push service hands this browser, decrypted. */
    public String read(byte[] body) {
        return new String(
                WebPushEncryption.decrypt(
                        body,
                        (ECPrivateKey) keys.getPrivate(),
                        WebPushEncryption.uncompressed((ECPublicKey) keys.getPublic()),
                        authSecret),
                StandardCharsets.UTF_8);
    }
}
