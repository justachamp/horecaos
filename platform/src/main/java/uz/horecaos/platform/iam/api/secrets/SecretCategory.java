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
     * ADR 0145 decision 4: the platform's one key for the map provider's server-side geocoder
     * (suggest, geocode, reverse geocode).
     *
     * <p>Platform-owned, and so deliberately <em>not</em> {@link #tenantWritable()}: one licence
     * serves every tenant, no tenant holds a relationship with the vendor, and a door that let a
     * tenant write here would let a tenant administrator replace the key every other tenant's
     * address search runs on. The owner writes it as an operator, the same way the database
     * password and the object-storage credentials are written
     * ({@code bao kv put horecaos/production/provider_geocoding/platform/<id> value=...}), and
     * the application only ever reads it. The browser key is a different thing: it is public by
     * construction, is delivered by {@code GET .../map-config}, and is plain configuration.
     */
    PROVIDER_GEOCODING,

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
                || this == PROVIDER_MARKETPLACE;
    }
}
