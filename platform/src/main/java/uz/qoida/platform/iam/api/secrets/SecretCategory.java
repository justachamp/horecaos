package uz.qoida.platform.iam.api.secrets;

/**
 * Secret categories from ADR 0028. Each has a named owner, a rotation period,
 * and a documented procedure, and a runtime role may read only its own.
 */
public enum SecretCategory {

    PROVIDER_POS,
    PROVIDER_PAYMENT,
    PROVIDER_DELIVERY,
    PROVIDER_NOTIFICATION,

    /** Keycloak service-account credentials, per ADR 0009. */
    IDENTITY_ADMIN,

    /** Envelope key material for ADR 0029. */
    DATA_ENCRYPTION,

    DATABASE,
    OBJECT_STORAGE
}
