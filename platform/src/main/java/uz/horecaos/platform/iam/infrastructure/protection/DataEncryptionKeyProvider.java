package uz.horecaos.platform.iam.infrastructure.protection;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/**
 * Derives per-tenant data encryption keys from a key-encryption key held in the
 * ADR 0028 secrets manager.
 *
 * <p>The key-encryption key never leaves the manager's control in phase one and
 * moves to a hardware-backed KMS in ADR 0034 phase two. Envelope encryption is
 * what makes that migration a re-wrap of data keys rather than a decrypt and
 * re-encrypt of every record.
 *
 * <p><strong>Derivation is versioned by generation, and the generation is part
 * of the key identifier stored with every ciphertext.</strong> That is what lets
 * this class change how it derives without making a single existing record
 * unreadable:
 *
 * <ul>
 *   <li>{@code g1} — {@code SHA-256(kek || 0x00 || label)}. Frozen, never used
 *       for a new write, and kept only so records written under it still open.
 *       It is a prefix-MAC, and prefix-MACs over a Merkle–Damgård hash are
 *       length-extendable: anyone holding one derived key can compute the key
 *       for any label that extends it, without the KEK. No two labels in use
 *       are extensions of each other, so nothing was exploitable, but the
 *       construction has no security argument behind it either.</li>
 *   <li>{@code g2} — HKDF-SHA256, extract-and-expand (RFC 5869), with the
 *       tenant, data class, and generation bound into the expand info rather
 *       than concatenated into a free-form label.</li>
 * </ul>
 *
 * <p>Old records are moved onto the current generation by the ADR 0029
 * re-encryption job, which is not built yet. Until it is, {@code g1} records
 * stay readable and stay on {@code g1}; nothing is lost, and nothing new is
 * written that way.
 */
@Component
public class DataEncryptionKeyProvider {

    /**
     * Frozen. Reproduces the pre-HKDF derivation exactly so existing ciphertext
     * still decrypts; changing anything reachable from here silently destroys
     * data.
     */
    static final String LEGACY_GENERATION = "g1";

    /** What new writes use. Advancing this is the rotation seam. */
    static final String CURRENT_GENERATION = "g2";

    /**
     * Every generation this build can derive.
     *
     * <p>An allowlist rather than a pattern, so a stored identifier naming
     * {@code g4711} is rejected instead of quietly minting a key and a cache
     * entry for it.
     */
    private static final List<String> GENERATIONS = List.of(LEGACY_GENERATION, CURRENT_GENERATION);

    private static final Set<String> DATA_CLASSES = Arrays.stream(DataClass.values())
            .map(dataClass -> dataClass.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private static final String KEK_OWNER = "platform";
    private static final String KEK_ID = "kek";

    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretResolver secrets;
    private final String environment;
    private final Map<String, SecretKey> derived = new ConcurrentHashMap<>();

    public DataEncryptionKeyProvider(
            SecretResolver secrets,
            @org.springframework.beans.factory.annotation.Value("${horecaos.environment:local}") String environment) {
        this.secrets = secrets;
        this.environment = environment;
    }

    /** The key new writes use, with the identifier that travels with the ciphertext. */
    public VersionedKey currentKey(UUID tenantId, DataClass dataClass) {
        KeyIdentity identity = new KeyIdentity(
                tenantId, dataClass.name().toLowerCase(Locale.ROOT), CURRENT_GENERATION);
        return new VersionedKey(identity.keyId(), key(identity));
    }

    /**
     * Resolves a key by the identifier stored with a record, so old and new keys
     * coexist while a background re-encryption proceeds.
     *
     * @throws IllegalArgumentException if the identifier is not one this tenant
     *                                  may name; see {@link KeyIdentity}
     */
    public SecretKey keyById(UUID tenantId, String keyId) {
        return key(KeyIdentity.parse(keyId, tenantId));
    }

    /**
     * A separate key per lookup domain, so a lookup hash cannot decrypt anything.
     *
     * <p>Still on the {@code g1} derivation, and not by oversight. A lookup hash
     * is stored as an indexed equality value with no version marker beside it, so
     * changing this key invalidates every stored hash at once — every contact
     * lookup would miss, and rebuilding means decrypting each plaintext and
     * recomputing. That is a migration with a column to carry the version, not an
     * edit to this method.
     */
    public SecretKey lookupKey(UUID tenantId, String lookupDomain) {
        return legacyDerive("lookup:" + tenantId + ":" + lookupDomain);
    }

    private SecretKey key(KeyIdentity identity) {
        return derived.computeIfAbsent(identity.keyId(), ignored -> derive(identity));
    }

    private SecretKey derive(KeyIdentity identity) {
        return LEGACY_GENERATION.equals(identity.generation())
                ? legacyDerive(identity.keyId())
                : hkdf(identity);
    }

    private SecretKey hkdf(KeyIdentity identity) {
        byte[] kek = kekMaterial();
        try {
            return KDF.getInstance("HKDF-SHA256").deriveKey("AES", HKDFParameterSpec.ofExtract()
                    .addIKM(kek)
                    // A fixed salt rather than a random one: the salt has to be
                    // reproducible to derive the same key twice, and there is
                    // nowhere to store a per-key salt that the key identifier
                    // does not already say. Naming the environment keeps a
                    // staging KEK restored into production from producing
                    // production keys.
                    .addSalt(("horecaos:dek:" + environment).getBytes(StandardCharsets.UTF_8))
                    .thenExpand(identity.info(), KEY_LENGTH_BYTES));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HKDF-SHA256 key derivation failed", failure);
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** The {@code g1} construction, reproduced byte for byte. Do not change. */
    private SecretKey legacyDerive(String label) {
        byte[] kek = kekMaterial();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(kek);
            digest.update((byte) 0);
            digest.update(label.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest.digest(), "AES");
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required", unreachable);
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /**
     * The KEK as bytes the caller clears.
     *
     * <p>The resolver owns and caches the {@link
     * uz.horecaos.platform.iam.api.secrets.SecretValue}, so it is read and let go
     * rather than disposed; the array returned here is this class's own copy.
     */
    private byte[] kekMaterial() {
        return secrets.resolve(new SecretReference(
                environment, SecretCategory.DATA_ENCRYPTION, KEK_OWNER, KEK_ID)).revealBytes();
    }

    /**
     * The three facts a data key is derived from, recovered from the identifier
     * stored beside a ciphertext.
     *
     * <p>Parsed and checked rather than used as an opaque KDF label, because the
     * identifier comes out of the same column as the ciphertext: whoever can
     * write one can write the other. As a free-form label it let that writer
     * choose which key a record decrypts under — including a {@code lookup:*}
     * label, which would have made one key serve as both an AES-GCM key and an
     * HMAC key — and grow the derived-key cache by one entry, and one secrets
     * lookup, per value they invented. A fixed shape whose tenant must match the
     * tenant asking closes both.
     *
     * <p>It does not stop a writer pointing a <em>new</em> record at {@code g1};
     * that is inherent to keeping old generations readable, and is what the
     * re-encryption job's completion check is for.
     */
    private record KeyIdentity(UUID tenantId, String dataClass, String generation) {

        static KeyIdentity parse(String keyId, UUID tenantId) {
            String[] parts = Objects.requireNonNull(keyId, "A key id is required").split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("A data key identifier has three parts");
            }
            UUID owner;
            try {
                owner = UUID.fromString(parts[0]);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException(
                        "A data key identifier starts with the tenant it belongs to", malformed);
            }
            if (!owner.equals(tenantId)) {
                throw new IllegalArgumentException(
                        "A protected value names a data key belonging to another tenant");
            }
            if (!DATA_CLASSES.contains(parts[1])) {
                throw new IllegalArgumentException("A data key identifier names a data class");
            }
            if (!GENERATIONS.contains(parts[2])) {
                throw new IllegalArgumentException("A data key identifier names a known generation");
            }
            return new KeyIdentity(owner, parts[1], parts[2]);
        }

        /** The stored form. Unchanged from before generations were versioned. */
        String keyId() {
            return "%s:%s:%s".formatted(tenantId, dataClass, generation);
        }

        /**
         * HKDF expand info. Delimited by a character none of the components can
         * contain — a UUID, a validated enum name, a validated generation — so no
         * two identities share an info string.
         */
        byte[] info() {
            return "horecaos:dek|%s|%s|%s".formatted(tenantId, dataClass, generation)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A key together with the identifier stored alongside every ciphertext. */
    public record VersionedKey(String keyId, SecretKey key) { }
}
