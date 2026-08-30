package uz.qoida.platform.iam.api.protection;

/**
 * The classification that decides storage, logging, export, and retention
 * treatment for a persisted field (ADR 0029).
 *
 * <p>Classifying first is what makes the rest mechanical: encryption, the
 * no-PII-in-events rule, masked projections, and retention all key off this.
 */
public enum DataClass {

    PUBLIC(false),
    INTERNAL(false),

    /** Name, contact, address, device, preferences. */
    PERSONAL(true),

    /** Identity documents, precise location history, biometric-adjacent data. */
    PERSONAL_SENSITIVE(true),

    /** Payment references and settlement identifiers. */
    FINANCIAL(true),

    /** Credentials. Handled by ADR 0028 and never stored in a business table. */
    SECRET(true);

    private final boolean requiresEncryption;

    DataClass(boolean requiresEncryption) {
        this.requiresEncryption = requiresEncryption;
    }

    public boolean requiresEncryption() {
        return requiresEncryption;
    }

    /** Whether a value of this class may appear in an event, log, trace, or metric. */
    public boolean mayLeaveTheDatabase() {
        return !requiresEncryption;
    }
}
