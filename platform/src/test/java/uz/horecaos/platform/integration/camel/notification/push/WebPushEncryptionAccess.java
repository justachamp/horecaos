package uz.horecaos.platform.integration.camel.notification.push;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

/**
 * The few key helpers of the package-private encryption class that tests in other packages need to make
 * a VAPID key pair. Test-side only, so the production class stays package-private.
 */
public final class WebPushEncryptionAccess {

    private WebPushEncryptionAccess() {}

    public static KeyPair generateKeyPair() {
        return WebPushEncryption.generateKeyPair();
    }

    public static byte[] uncompressed(ECPublicKey key) {
        return WebPushEncryption.uncompressed(key);
    }

    public static byte[] fixed(BigInteger value) {
        return WebPushEncryption.fixed(value);
    }
}
