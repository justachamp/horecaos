package uz.horecaos.platform.iam.infrastructure.protection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;

/**
 * ADR 0029. The cross-tenant and cross-record tests are the point: encryption
 * that decrypts anywhere protects against a stolen disk and nothing else.
 */
class EnvelopeFieldProtectionTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c02");
    private static final UUID RECORD = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c03");
    private static final RecordRef CONTACT =
            new RecordRef("customer.contact_points", "encrypted_value", RECORD);

    private EnvelopeFieldProtection protection;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);
        Map<String, String> secrets = Map.of(
                "horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key");
        protection = new EnvelopeFieldProtection(
                new DataEncryptionKeyProvider(new EnvironmentSecretResolver(secrets::get, clock), "local"));
    }

    @Test
    void roundTripsAProtectedValue() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");

        assertThat(protection.reveal(TENANT, protectedValue, CONTACT, "support lookup"))
                .isEqualTo("+998901231076");
    }

    @Test
    void equalPlaintextsProduceDifferentCiphertexts() {
        ProtectedValue first = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");
        ProtectedValue second = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");

        assertThat(first.ciphertext())
                .as("deterministic encryption would leak frequency on a small domain like phone numbers")
                .isNotEqualTo(second.ciphertext());
    }

    @Test
    void equalPlaintextsProduceEqualLookupHashes() {
        assertThat(protection.lookupHash(TENANT, "phone", "+998901231076"))
                .isEqualTo(protection.lookupHash(TENANT, "phone", "+998901231076"));
    }

    @Test
    void aLookupHashIsScopedToItsTenant() {
        assertThat(protection.lookupHash(TENANT, "phone", "+998901231076"))
                .as("one tenant must not be able to probe another's contact set")
                .isNotEqualTo(protection.lookupHash(OTHER_TENANT, "phone", "+998901231076"));
    }

    @Test
    void aLookupHashIsScopedToItsDomain() {
        assertThat(protection.lookupHash(TENANT, "phone", "shared-value"))
                .isNotEqualTo(protection.lookupHash(TENANT, "email", "shared-value"));
    }

    @Test
    void aCiphertextMovedToAnotherTenantFailsToDecrypt() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");

        assertThatThrownBy(() -> protection.reveal(OTHER_TENANT, protectedValue, CONTACT, "attack"))
                .as("a mis-joined query must fail, not quietly return another tenant's data")
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    void aCiphertextMovedToAnotherRecordFailsToDecrypt() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");
        RecordRef otherRecord = new RecordRef(
                "customer.contact_points", "encrypted_value",
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120cff"));

        assertThatThrownBy(() -> protection.reveal(TENANT, protectedValue, otherRecord, "attack"))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    void aCiphertextMovedToAnotherColumnFailsToDecrypt() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");
        RecordRef otherColumn = new RecordRef("customer.contact_points", "delivery_instructions", RECORD);

        assertThatThrownBy(() -> protection.reveal(TENANT, protectedValue, otherColumn, "attack"))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    void aTamperedCiphertextFailsToDecrypt() {
        ProtectedValue original = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");
        byte[] tampered = original.ciphertext();
        tampered[0] ^= 0x01;
        ProtectedValue altered = new ProtectedValue(
                original.keyId(), original.algorithm(), original.nonce(), tampered, 1);

        assertThatThrownBy(() -> protection.reveal(TENANT, altered, CONTACT, "attack"))
                .as("GCM authenticates the ciphertext, so a silent modification is impossible")
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    void theKeyIdentifierTravelsWithTheValueSoRotationCanCoexist() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "value");

        assertThat(protectedValue.keyId())
                .contains(TENANT.toString())
                .contains("personal");
    }

    @Test
    void newWritesUseTheCurrentGenerationAndOldOnesKeepTheirs() {
        assertThat(protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "value").keyId())
                .endsWith(":g2");
    }

    /**
     * The compatibility test for the move off the bare-hash derivation. The
     * ciphertext here is built the way a record written before HKDF was built,
     * under a key derived the way that code derived it.
     */
    @Test
    void aRecordWrittenUnderTheOldDerivationStillDecrypts() throws Exception {
        String legacyKeyId = TENANT + ":personal:g1";

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("a-test-key-encryption-key".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(legacyKeyId.getBytes(StandardCharsets.UTF_8));

        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(digest.digest(), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("v1|" + TENANT + "|" + CONTACT.canonical()).getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal("+998901231076".getBytes(StandardCharsets.UTF_8));

        ProtectedValue stored = new ProtectedValue(
                legacyKeyId, "AES/GCM/NoPadding", nonce, ciphertext, 1);

        assertThat(protection.reveal(TENANT, stored, CONTACT, "support lookup"))
                .as("changing how keys are derived must not orphan a single existing record")
                .isEqualTo("+998901231076");
    }

    @Test
    void aCiphertextNamingAnotherTenantsKeyIsRefused() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "value");
        ProtectedValue swapped = new ProtectedValue(
                OTHER_TENANT + ":personal:g2", protectedValue.algorithm(),
                protectedValue.nonce(), protectedValue.ciphertext(), 1);

        assertThatThrownBy(() -> protection.reveal(TENANT, swapped, CONTACT, "attack"))
                .as("the identifier is read from the same column an attacker who can write "
                        + "ciphertext can write")
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    void aCiphertextPointedAtTheLookupKeyIsRefused() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "value");
        ProtectedValue borrowed = new ProtectedValue(
                "lookup:" + TENANT + ":phone", protectedValue.algorithm(),
                protectedValue.nonce(), protectedValue.ciphertext(), 1);

        assertThatThrownBy(() -> protection.reveal(TENANT, borrowed, CONTACT, "attack"))
                .as("the lookup key is an HMAC key; using it as a GCM key is reuse across purposes")
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    void anUnencryptedClassIsRejected() {
        assertThatThrownBy(() -> protection.protect(TENANT, DataClass.INTERNAL, CONTACT, "value"))
                .as("protecting queryable business data would hide it for no benefit")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProtectedValueSerializesAndNeverRendersItsContents() {
        ProtectedValue protectedValue = protection.protect(TENANT, DataClass.PERSONAL, CONTACT, "+998901231076");

        assertThat(ProtectedValue.deserialize(protectedValue.serialize()).ciphertext())
                .isEqualTo(protectedValue.ciphertext());
        assertThat(protectedValue.toString())
                .contains("keyId")
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(protectedValue.ciphertext()));
    }

    @Test
    void classificationDecidesWhatMayLeaveTheDatabase() {
        assertThat(DataClass.PERSONAL.mayLeaveTheDatabase()).isFalse();
        assertThat(DataClass.PERSONAL_SENSITIVE.mayLeaveTheDatabase()).isFalse();
        assertThat(DataClass.FINANCIAL.mayLeaveTheDatabase()).isFalse();
        assertThat(DataClass.INTERNAL.mayLeaveTheDatabase()).isTrue();
    }
}
