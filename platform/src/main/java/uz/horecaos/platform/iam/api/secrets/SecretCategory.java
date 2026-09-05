package uz.horecaos.platform.iam.api.secrets;

/**
 * Secret categories from ADR 0028. Each has a named owner, a rotation period,
 * and a documented procedure, and a runtime role may read only its own.
 */
public enum SecretCategory {
    PROVIDER_POS,
    PROVIDER_PAYMENT,
    PROVIDER_DELIVERY,
    PROVIDER_NOTIFICATION,
    PROVIDER_VOICE,

    /** Keycloak service-account credentials, per ADR 0009. */
    IDENTITY_ADMIN,

    /** Envelope key material for ADR 0029. */
    DATA_ENCRYPTION,

    DATABASE,
    OBJECT_STORAGE;

    /**
     * Whether a tenant may write a value in this category through the ADR 0065
     * secret door.
     *
     * <p>True for exactly the five {@code PROVIDER_*} categories a tenant
     * legitimately holds credentials in. The other four name platform-internal
     * secrets — Keycloak, envelope-encryption key material, the database role,
     * object storage — that no tenant action may ever overwrite.
     */
    public boolean tenantWritable() {
        return this == PROVIDER_POS
                || this == PROVIDER_PAYMENT
                || this == PROVIDER_DELIVERY
                || this == PROVIDER_NOTIFICATION
                || this == PROVIDER_VOICE;
    }
}
