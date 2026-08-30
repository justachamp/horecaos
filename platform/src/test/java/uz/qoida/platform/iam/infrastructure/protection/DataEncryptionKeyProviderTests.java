package uz.qoida.platform.iam.infrastructure.protection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.iam.api.protection.DataClass;
import uz.qoida.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;

/**
 * Key derivation, and above all what must not change about it.
 *
 * <p>{@link #generationOneStillDerivesExactlyWhatItAlwaysDid()} is the test that
 * matters: every ciphertext written before HKDF arrived carries a {@code g1} key
 * identifier, and if that derivation moves by a byte, those records are gone.
 */
class DataEncryptionKeyProviderTests {

    private static final String KEK = "a-test-key-encryption-key";
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c02");

    private final DataEncryptionKeyProvider keys = provider();

    @Test
    @DisplayName("a g1 key is still SHA-256(kek || 0x00 || keyId), byte for byte")
    void generationOneStillDerivesExactlyWhatItAlwaysDid() throws Exception {
        String keyId = TENANT + ":personal:g1";

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(KEK.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(keyId.getBytes(StandardCharsets.UTF_8));

        assertThat(keys.keyById(TENANT, keyId).getEncoded())
                .as("records written before HKDF carry this identifier and nothing else can open them")
                .isEqualTo(digest.digest());
    }

    @Test
    @DisplayName("a lookup key is still the g1 derivation, because stored hashes have no version")
    void lookupKeysAreUnchanged() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(KEK.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(("lookup:" + TENANT + ":phone").getBytes(StandardCharsets.UTF_8));

        assertThat(keys.lookupKey(TENANT, "phone").getEncoded()).isEqualTo(digest.digest());
    }

    @Test
    @DisplayName("new writes are HKDF-SHA256 over the tenant, class, and generation")
    void currentKeysUseHkdf() throws Exception {
        DataEncryptionKeyProvider.VersionedKey current = keys.currentKey(TENANT, DataClass.PERSONAL);

        SecretKey expected = KDF.getInstance("HKDF-SHA256").deriveKey("AES", HKDFParameterSpec.ofExtract()
                .addIKM(KEK.getBytes(StandardCharsets.UTF_8))
                .addSalt("qoida:dek:local".getBytes(StandardCharsets.UTF_8))
                .thenExpand(("qoida:dek|" + TENANT + "|personal|g2").getBytes(StandardCharsets.UTF_8), 32));

        assertThat(current.keyId()).isEqualTo(TENANT + ":personal:g2");
        assertThat(current.key().getEncoded()).isEqualTo(expected.getEncoded());
    }

    @Test
    @DisplayName("the bare-hash construction is gone from the write path")
    void currentKeysAreNotThePrefixHash() throws Exception {
        DataEncryptionKeyProvider.VersionedKey current = keys.currentKey(TENANT, DataClass.PERSONAL);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(KEK.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(current.keyId().getBytes(StandardCharsets.UTF_8));

        assertThat(current.key().getEncoded())
                .as("a length-extendable prefix-MAC is not a key derivation function")
                .isNotEqualTo(digest.digest());
    }

    @Test
    @DisplayName("a key identifier naming another tenant is refused")
    void aStoredIdentifierCannotReachAnotherTenantsKey() {
        assertThatThrownBy(() -> keys.keyById(TENANT, OTHER_TENANT + ":personal:g2"))
                .as("the identifier comes out of the column an attacker who can write ciphertext writes")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key identifier cannot point at the lookup key namespace")
    void aStoredIdentifierCannotBorrowTheLookupKey() {
        assertThatThrownBy(() -> keys.keyById(TENANT, "lookup:" + TENANT + ":phone"))
                .as("one key serving as both an AES-GCM key and an HMAC key is key reuse across purposes")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an invented generation is refused rather than minted")
    void anUnknownGenerationIsRefused() {
        assertThatThrownBy(() -> keys.keyById(TENANT, TENANT + ":personal:g4711"))
                .as("a free-form generation is one cache entry and one secrets lookup per value invented")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown data class is refused")
    void anUnknownDataClassIsRefused() {
        assertThatThrownBy(() -> keys.keyById(TENANT, TENANT + ":whatever:g2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a malformed identifier is refused")
    void aMalformedIdentifierIsRefused() {
        assertThatThrownBy(() -> keys.keyById(TENANT, "not-a-key-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.keyById(TENANT, "not-a-uuid:personal:g2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each data class gets its own key")
    void classesDoNotShareAKey() {
        assertThat(keys.currentKey(TENANT, DataClass.PERSONAL).key().getEncoded())
                .isNotEqualTo(keys.currentKey(TENANT, DataClass.FINANCIAL).key().getEncoded());
    }

    @Test
    @DisplayName("each tenant gets its own key")
    void tenantsDoNotShareAKey() {
        assertThat(keys.currentKey(TENANT, DataClass.PERSONAL).key().getEncoded())
                .isNotEqualTo(keys.currentKey(OTHER_TENANT, DataClass.PERSONAL).key().getEncoded());
    }

    @Test
    @DisplayName("the environment is bound into the derivation")
    void aRestoredKekFromAnotherEnvironmentDerivesDifferentKeys() {
        assertThat(keys.currentKey(TENANT, DataClass.PERSONAL).key().getEncoded())
                .isNotEqualTo(providerFor("production").currentKey(TENANT, DataClass.PERSONAL)
                        .key().getEncoded());
    }

    private static DataEncryptionKeyProvider provider() {
        return providerFor("local");
    }

    private static DataEncryptionKeyProvider providerFor(String environment) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);
        Map<String, String> secrets = Map.of(
                "qoida.secrets.data_encryption.platform.kek", KEK);
        return new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(secrets::get, clock), environment);
    }
}
