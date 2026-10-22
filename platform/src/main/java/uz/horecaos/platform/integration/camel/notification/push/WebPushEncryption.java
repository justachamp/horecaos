package uz.horecaos.platform.integration.camel.notification.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import uz.horecaos.platform.notifications.api.WebPushKeys;

/**
 * Message encryption for Web Push (RFC 8291) over the {@code aes128gcm} content coding (RFC 8188),
 * on plain JCA (ADR 0182, D14).
 *
 * <p>A push service stores a message it cannot read: the payload is encrypted to the browser's own
 * key pair (the subscription's {@code p256dh}) with a secret only that browser and the platform
 * share (its {@code auth}). Nothing here touches a network, a database or a secret store; the only
 * inputs are the subscription's two keys and the plaintext, so the whole derivation is checked
 * against the worked example in RFC 8291 Appendix A.
 *
 * <p>One record per message: a payload is a notification's title, body and a few links, nowhere near
 * the 4096 octets a push service must accept, and {@link #MAX_PLAINTEXT} is the most one record can
 * carry once the 86 octet header, the delimiter and the tag are counted.
 */
public final class WebPushEncryption {

    /** The record size this encoder declares: the 4096 every push service must accept. */
    static final int RECORD_SIZE = 4096;

    /** salt (16) + record size (4) + key id length (1) + the sender's uncompressed public key (65). */
    static final int HEADER_LENGTH = 16 + 4 + 1 + 65;

    private static final int TAG_LENGTH = 16;

    /** The most plaintext one record carries: the body is capped at {@code RECORD_SIZE} in total. */
    public static final int MAX_PLAINTEXT = RECORD_SIZE - HEADER_LENGTH - TAG_LENGTH - 1;

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {}

    /** What was derived on the way, exposed so the RFC's own example can be checked step by step. */
    record Derivation(byte[] ecdhSecret, byte[] ikm, byte[] contentEncryptionKey, byte[] nonce) {}

    /**
     * Encrypts one payload to a subscription with a fresh ephemeral key pair and salt.
     *
     * @param userAgentPublicKey the subscription's {@code p256dh}: an uncompressed P-256 point, 65 octets
     * @param authSecret the subscription's {@code auth}: 16 octets
     * @return the whole HTTP body: header, then the single encrypted record
     * @throws IllegalArgumentException when a key is malformed or the payload does not fit one record
     */
    public static byte[] encrypt(byte[] plaintext, byte[] userAgentPublicKey, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, userAgentPublicKey, authSecret, generateKeyPair(), salt)
                .body();
    }

    record Encrypted(byte[] body, Derivation derivation) {}

    /** The same, with the sender's key pair and the salt chosen by the caller (the RFC's example fixes both). */
    static Encrypted encrypt(
            byte[] plaintext, byte[] userAgentPublicKey, byte[] authSecret, KeyPair senderKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("A push payload is at most " + MAX_PLAINTEXT + " octets");
        }
        if (authSecret.length != 16) {
            throw new IllegalArgumentException("A push auth secret is 16 octets");
        }
        if (salt.length != 16) {
            throw new IllegalArgumentException("The salt is 16 octets");
        }
        try {
            byte[] senderPublic = uncompressed((ECPublicKey) senderKeys.getPublic());
            ECPublicKey uaPublic = decodePublicKey(userAgentPublicKey);

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(senderKeys.getPrivate());
            agreement.doPhase(uaPublic, true);
            byte[] ecdhSecret = agreement.generateSecret();

            // RFC 8291 section 3.4: the shared secret is mixed with the auth secret before RFC 8188's own
            // derivation, so a push service that sees the ciphertext and both public keys learns nothing.
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat(KEY_INFO_PREFIX, userAgentPublicKey, senderPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));

            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[] {1})), 12);

            // The last (here, only) record ends in the delimiter 0x02, then nothing.
            byte[] record = concat(plaintext, new byte[] {2});
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(record);

            ByteBuffer body = ByteBuffer.allocate(HEADER_LENGTH + ciphertext.length);
            body.put(salt).putInt(RECORD_SIZE).put((byte) senderPublic.length).put(senderPublic);
            body.put(ciphertext);
            return new Encrypted(body.array(), new Derivation(ecdhSecret, ikm, cek, nonce));
        } catch (GeneralSecurityException failure) {
            // Never the cause's message: a malformed key would print the key.
            throw new IllegalArgumentException("The push payload could not be encrypted to this subscription");
        }
    }

    /**
     * The browser's side of the same exchange: what a user agent does with a body it receives. Not used
     * to send anything; it exists so the tests can prove a body decrypts to what was meant, with code
     * that shares nothing with {@link #encrypt} but the specification.
     */
    static byte[] decrypt(byte[] body, ECPrivateKey userAgentPrivateKey, byte[] userAgentPublicKey, byte[] authSecret) {
        try {
            ByteBuffer in = ByteBuffer.wrap(body);
            byte[] salt = new byte[16];
            in.get(salt);
            int recordSize = in.getInt();
            int keyIdLength = in.get() & 0xFF;
            byte[] senderPublic = new byte[keyIdLength];
            in.get(senderPublic);
            byte[] ciphertext = new byte[in.remaining()];
            in.get(ciphertext);
            if (recordSize < ciphertext.length) {
                throw new IllegalArgumentException("More than one record");
            }

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(userAgentPrivateKey);
            agreement.doPhase(decodePublicKey(senderPublic), true);
            byte[] prkKey = hmac(authSecret, agreement.generateSecret());
            byte[] ikm = hmac(prkKey, concat(KEY_INFO_PREFIX, userAgentPublicKey, senderPublic, new byte[] {1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[] {1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] record = cipher.doFinal(ciphertext);
            int end = record.length;
            while (end > 0 && record[end - 1] == 0) {
                end--;
            }
            if (end == 0 || record[end - 1] != 2) {
                throw new IllegalArgumentException("Not the last record");
            }
            return Arrays.copyOf(record, end - 1);
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("The push body could not be decrypted");
        }
    }

    // --------------------------------------------------------------------------------- keys

    /** A new P-256 key pair: the sender's ephemeral pair for one message. */
    static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("P-256 is part of every JVM", impossible);
        }
    }

    /** Whether these 65 octets are a point on P-256: what a subscription's {@code p256dh} must be. */
    public static boolean isValidPublicKey(byte[] encoded) {
        return WebPushKeys.isValidPublicKey(encoded);
    }

    /** An uncompressed SEC1 point (0x04, X, Y) as a public key. Rejects any point that is not on the curve. */
    static ECPublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        return WebPushKeys.decodePublicKey(encoded);
    }

    /** A raw 32 octet private scalar as a private key. */
    static ECPrivateKey decodePrivateKey(byte[] scalar) throws GeneralSecurityException {
        if (scalar.length != 32) {
            throw new IllegalArgumentException("A P-256 private key is 32 octets");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), WebPushKeys.p256()));
    }

    static byte[] uncompressed(ECPublicKey key) {
        byte[] encoded = new byte[65];
        encoded[0] = 4;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, encoded, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, encoded, 33, 32);
        return encoded;
    }

    /** An unsigned big-endian number as exactly 32 octets. */
    static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    // ------------------------------------------------------------------------------- helpers

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }
}
