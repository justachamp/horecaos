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

    /**
     * ADR 0106: a {@code partner.api_clients} row's Keycloak-minted
     * {@code client_credentials} secret. Written here for the same
     * masked/rotate/last-rotated console treatment every other provider
     * secret gets, even though nothing in the application ever calls
     * {@link SecretResolver#resolve(SecretReference)} on it — Keycloak
     * validates the token on its own. The value originates from the platform
     * calling Keycloak, never from a tenant typing it, but the credential is
     * still the tenant's own, exactly like the other {@code PROVIDER_*}
     * categories.
     */
    PROVIDER_MARKETPLACE,

    /**
     * ADR 0070: the secret a CONFIDENTIAL storefront app presents beside its app
     * id. The platform mints the value, writes it here once, and the vendor is
     * shown it once; what the registry keeps is this reference. Unlike the other
     * {@code PROVIDER_*} categories the application does resolve it, on every
     * request that carries the app's secret header, to compare in constant time.
     *
     * <p>Written through the same door as the rest, by the control plane's
     * registration and rotation and by nothing a tenant reaches: the owner scope
     * is a platform-derived constant, never a tenant's.
     */
    PROVIDER_STOREFRONT_APP,

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
     * <p>True for exactly the six {@code PROVIDER_*} categories a tenant
     * legitimately holds credentials in. The other four name platform-internal
     * secrets — Keycloak, envelope-encryption key material, the database role,
     * object storage — that no tenant action may ever overwrite.
     */
    public boolean tenantWritable() {
        return this == PROVIDER_POS
                || this == PROVIDER_PAYMENT
                || this == PROVIDER_DELIVERY
                || this == PROVIDER_NOTIFICATION
                || this == PROVIDER_VOICE
                || this == PROVIDER_MARKETPLACE
                || this == PROVIDER_STOREFRONT_APP;
    }
}
