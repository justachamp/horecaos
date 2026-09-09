package uz.horecaos.platform.iam.application.devices;

/**
 * Provisions and retires the confidential Keycloak service-account client
 * behind one ADR 0079 device principal.
 *
 * <p>Internal to {@code iam} — nothing outside this module names a Keycloak
 * client directly, the same reason {@code OrganizationProvisioner} is the
 * seam {@code KeycloakOrganizationProvisioner} sits behind rather than a
 * concrete Admin API client leaking into {@code tenancy}. Kept apart from
 * {@link uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort} rather
 * than folded into it: that interface is the module's public contract, this
 * one is a private implementation seam so {@code DeviceEnrolmentService} can
 * be tested without a real Keycloak.
 */
public interface DeviceClientProvisioner {

    /**
     * Creates a new confidential, service-account-only client — never a user,
     * never a shared client reused by more than one device. Carries no
     * secret: whatever value Keycloak assigns at creation is never read, and
     * {@link #regenerateSecret} is what the device actually receives, minted
     * at the moment of hand-off rather than at creation.
     */
    ProvisionedClient create(String displayLabel);

    /**
     * Mints a fresh client secret and returns it. Called exactly once, by the
     * enrolment poll that wins the one-shot claim (see {@link
     * uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort#poll}) — the
     * device is the only place this value is ever held outside Keycloak
     * itself, and this platform's own database never stores it.
     */
    String regenerateSecret(String keycloakClientInternalId);

    /** Disables the client so no new token can ever be minted for it. Idempotent. */
    void disable(String keycloakClientInternalId);

    /**
     * The realm's own token endpoint, so a device needs no Keycloak
     * configuration of its own beyond what an enrolment response carries.
     * Deployment configuration, not a fact about any one device — read here
     * rather than stored per row, so a device minted before a Keycloak
     * migration is not left carrying a stale value.
     */
    String tokenEndpoint();

    /**
     * @param keycloakClientInternalId Keycloak's own id for the client, used for every later Admin API call
     * @param clientId                 the OAuth {@code client_id} the device presents at the token endpoint
     * @param serviceAccountSubject    the client's service-account user id — the exact value {@code
     *                                 iam.grants.principal_subject} stores for this device
     */
    record ProvisionedClient(String keycloakClientInternalId, String clientId, String serviceAccountSubject) {}
}
