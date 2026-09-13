package uz.horecaos.platform.iam.api.partnerclients;

/**
 * Provisions and retires the confidential Keycloak {@code client_credentials}
 * client behind one ADR 0040 partner API credential (ADR 0106).
 *
 * <p>The same three jobs {@link uz.horecaos.platform.iam.application.devices.DeviceClientProvisioner}
 * does for an ADR 0079 device principal, kept as a separate, purpose-named
 * port rather than a shared generic one: a partner credential and a device
 * principal are different machine-principal kinds with different owning
 * modules ({@code partner} calls this one; {@code iam} calls its own), and
 * folding them into one interface now would couple two working flows for a
 * benefit neither needs yet — see ADR 0106's own Alternatives table.
 *
 * <p>Lives in {@code iam.api} rather than {@code partner} because every
 * Keycloak Admin API call in this codebase is made from {@code iam}
 * (organizations, staff accounts, device clients); {@code partner} depends on
 * this port exactly as it already depends on {@code iam.api.Capability} and
 * {@code iam.api.secrets.*}, never the other way around.
 */
public interface PartnerClientProvisioner {

    /**
     * Creates a new confidential, service-account-only client — never a user,
     * never shared between installations. Carries no secret at creation:
     * whatever value Keycloak assigns then is never read, and {@link
     * #regenerateSecret} is what the tenant actually receives, minted at the
     * moment of hand-off.
     */
    ProvisionedClient create(String clientLabel);

    /**
     * Mints a fresh client secret and returns it. Called at issuance and at
     * every rotation; the platform additionally writes the returned value
     * through the ADR 0028 secret door under {@code PROVIDER_MARKETPLACE} —
     * see ADR 0106's Decision for why, given Keycloak validates the token
     * on its own and never needs the platform to resolve this reference.
     */
    String regenerateSecret(String keycloakClientRef);

    /** Disables the client so no new token can ever be minted for it. Idempotent. */
    void disable(String keycloakClientRef);

    /**
     * @param keycloakClientRef Keycloak's own internal id for the client, used for every later Admin API call
     * @param clientId          the OAuth {@code client_id} the partner presents at the token endpoint
     */
    record ProvisionedClient(String keycloakClientRef, String clientId) {}
}
