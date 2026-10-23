package uz.horecaos.platform.notifications.api;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/**
 * What makes a browser's {@code p256dh} a key the platform will encrypt to (ADR 0182, D14).
 *
 * <p>Shared by the two places that must agree: the subscription endpoint refuses a key that is not a
 * point on P-256 when the browser hands it over, and the Web Push encryption refuses it again before a
 * secret is derived from it. A point off the curve is the first move of an invalid-curve attack on the
 * key agreement, and checking it explicitly does not depend on what a given JDK happens to verify.
 */
public final class WebPushKeys {

    private WebPushKeys() {}

    /** Whether these octets are an uncompressed SEC1 point (0x04, X, Y) on P-256. */
    public static boolean isValidPublicKey(byte[] encoded) {
        try {
            decodePublicKey(encoded);
            return true;
        } catch (IllegalArgumentException | GeneralSecurityException notAKey) {
            return false;
        }
    }

    /**
     * The key for an uncompressed point.
     *
     * @throws IllegalArgumentException when the octets are not a point on the curve
     */
    public static ECPublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        if (encoded.length != 65 || encoded[0] != 4) {
            throw new IllegalArgumentException("Not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
        ECParameterSpec curve = p256();
        if (!onCurve(x, y, curve)) {
            throw new IllegalArgumentException("Not a point on P-256");
        }
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve));
    }

    public static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static boolean onCurve(BigInteger x, BigInteger y, ECParameterSpec curve) {
        BigInteger p = ((ECFieldFp) curve.getCurve().getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3)
                .add(curve.getCurve().getA().multiply(x))
                .add(curve.getCurve().getB())
                .mod(p);
        return left.equals(right);
    }
}
