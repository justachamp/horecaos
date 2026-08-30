package uz.qoida.platform.iam.api.protection;

import java.util.UUID;

/**
 * Protects and reveals classified field values (ADR 0029).
 *
 * <p>Encryption is randomized, so equal plaintexts do not produce equal
 * ciphertexts. Lookup therefore uses a separate keyed hash rather than the
 * ciphertext: deterministic encryption would leak frequency and allow
 * confirmation attacks on small domains such as phone numbers.
 */
public interface FieldProtection {

    ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext);

    /**
     * @param purpose recorded as an ADR 0027 audit fact for sensitive and
     *                financial classes, because the difference between an agent
     *                viewing one customer and exporting fifty thousand is
     *                exactly what this control exists to capture
     */
    String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose);

    /** Deterministic, keyed, per-tenant lookup value for an equality search. */
    String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue);

    /**
     * Identifies the row a ciphertext belongs to. Bound into the AEAD associated
     * data, so a ciphertext copied to another row or tenant fails to decrypt
     * rather than silently revealing the wrong person's data.
     */
    record RecordRef(String table, String column, UUID recordId) {

        public String canonical() {
            return "%s.%s:%s".formatted(table, column, recordId);
        }
    }

    /** Thrown when a ciphertext does not belong where it was found. */
    class ProtectionIntegrityException extends RuntimeException {
        public ProtectionIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
