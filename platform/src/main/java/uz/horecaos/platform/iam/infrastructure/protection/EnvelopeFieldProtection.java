package uz.horecaos.platform.iam.infrastructure.protection;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * Envelope encryption for classified fields (ADR 0029).
 *
 * <p>AES-256-GCM with a per-record random nonce, authenticated with associated
 * data binding the ciphertext to its tenant and record identity. Moving a
 * ciphertext to another row or another tenant then fails to decrypt, which turns
 * a copy-paste mistake or a mis-joined query into an error rather than a silent
 * cross-tenant leak.
 *
 * <p>Keys are scoped per tenant and per class, not per customer. That decision
 * and its trade-off are recorded in ADR 0029: per-customer keys would give a
 * stronger erasure proof at the cost of a key inventory sized by customer count,
 * where a key lost to a backup gap becomes indistinguishable from a deliberate
 * erasure. Customer erasure is anonymisation instead.
 *
 * <p><strong>On the nonce.</strong> The nonce is 96 random bits per record, so
 * two records under one key collide with probability about n²/2<sup>97</sup>
 * after n writes, and a repeated nonce under GCM is the failure that leaks the
 * keystream and the authentication key together. NIST SP 800-38D §8.3 puts the
 * limit for random nonces at 2<sup>32</sup> invocations per key. A key here is
 * one tenant's one data class: at a hundred thousand orders a day — an order of
 * magnitude beyond the largest operator in this market — and ten protected field
 * writes per order, 2<sup>32</sup> is about twelve years away, and at a realistic
 * few thousand orders a day it is centuries. The bound is therefore documented
 * rather than engineered around; a deterministic counter nonce would need a
 * durable per-key counter that survives restore-from-backup, and a counter that
 * silently rewinds is a worse failure than the one it prevents. The seam if that
 * judgement ever changes is
 * {@link DataEncryptionKeyProvider#CURRENT_GENERATION}: advancing the generation
 * gives every tenant a fresh key and resets n to zero.
 */
@Component
public class EnvelopeFieldProtection implements FieldProtection {

    static final String ALGORITHM = "AES/GCM/NoPadding";
    static final int NONCE_LENGTH = 12;
    static final int TAG_LENGTH_BITS = 128;
    static final int CURRENT_AAD_VERSION = 1;

    private final DataEncryptionKeyProvider keys;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> lookupKeys = new ConcurrentHashMap<>();

    public EnvelopeFieldProtection(DataEncryptionKeyProvider keys) {
        this.keys = keys;
    }

    @Override
    public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
        if (!dataClass.requiresEncryption()) {
            throw new IllegalArgumentException(
                    "%s is not an encrypted class; storing it protected would hide queryable data"
                            .formatted(dataClass));
        }
        DataEncryptionKeyProvider.VersionedKey key = keys.currentKey(tenantId, dataClass);

        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData(tenantId, record));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new ProtectedValue(key.keyId(), ALGORITHM, nonce, ciphertext, CURRENT_AAD_VERSION);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Field encryption failed", failure);
        }
    }

    @Override
    public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
        SecretKey key;
        try {
            key = keys.keyById(tenantId, value.keyId());
        } catch (IllegalArgumentException rejected) {
            // The stored identifier names a key this tenant may not use. Same
            // class of event as a failed tag check, and reported the same way, so
            // a caller cannot tell a swapped identifier from a swapped ciphertext.
            throw new ProtectionIntegrityException(
                    "A protected value names a key it may not be read with", rejected);
        }
        try {
            Cipher cipher = Cipher.getInstance(value.algorithm());
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, value.nonce()));
            cipher.updateAAD(associatedData(tenantId, record));
            return new String(cipher.doFinal(value.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            // Deliberately does not echo the record or tenant into the message;
            // an integrity failure is a security event, not a debugging aid.
            throw new ProtectionIntegrityException(
                    "A protected value failed to decrypt in the context it was found in", failure);
        }
    }

    @Override
    public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
        SecretKey key = lookupKeys.computeIfAbsent(
                tenantId + ":" + lookupDomain, ignored -> keys.lookupKey(tenantId, lookupDomain));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Lookup hashing failed", failure);
        }
    }

    /**
     * Binds the ciphertext to its tenant and its exact row and column. Any of
     * those changing makes decryption fail.
     */
    private byte[] associatedData(UUID tenantId, RecordRef record) {
        return "v%d|%s|%s".formatted(CURRENT_AAD_VERSION, tenantId, record.canonical())
                .getBytes(StandardCharsets.UTF_8);
    }

    static SecretKey keyFrom(byte[] material) {
        return new SecretKeySpec(material, "AES");
    }
}
