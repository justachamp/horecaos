package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.partnerclients.PartnerClientProvisioner;

/**
 * Keycloak Admin API adapter for ADR 0106's partner API credential.
 *
 * <p>Direct HTTPS, not Camel, the same reason {@code KeycloakDeviceClientProvisioner}
 * gives (ADR 0007): a small set of authenticated JSON calls on a control
 * path, not mediation or independently scaled traffic. Structurally identical
 * to that class by design — see this interface's own doc comment for why it
 * is a separate port rather than a shared one.
 *
 * <p>Runs on its own credential, {@code horecaos-partner-provisioning},
 * holding {@code manage-clients} alone — never {@code horecaos-provisioning}
 * and never {@code horecaos-device-provisioning}, the same least-privilege
 * reasoning {@code KeycloakDeviceClientProvisioner}'s own doc comment already
 * gives for its sibling credential.
 */
public class KeycloakPartnerClientProvisioner implements PartnerClientProvisioner {

    private final RestClient client;
    private final String realm;

    public KeycloakPartnerClientProvisioner(RestClient client, String realm) {
        this.client = client;
        this.realm = realm;
    }

    @Override
    public ProvisionedClient create(String clientLabel) {
        // A UUID suffix, not a slug of the label: the label is an
        // operator-chosen display name with no uniqueness guarantee, the same
        // reasoning KeycloakDeviceClientProvisioner.create already applies.
        String clientId = "partner-" + UUID.randomUUID();

        client.post()
                .uri("/admin/realms/{realm}/clients", realm)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "clientId", clientId,
                        "name", clientLabel,
                        "description", "ADR 0106 partner API credential",
                        "protocol", "openid-connect",
                        "enabled", true,
                        "publicClient", false,
                        "serviceAccountsEnabled", true,
                        "standardFlowEnabled", false,
                        "implicitFlowEnabled", false,
                        "directAccessGrantsEnabled", false))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new PartnerProvisioningException(
                            "Creating Keycloak client %s failed with %s".formatted(clientId, response.getStatusCode()));
                })
                .toBodilessEntity();

        // Read back rather than trust a Location header — the same discipline
        // KeycloakDeviceClientProvisioner and KeycloakOrganizationProvisioner
        // already apply: the response body is not proof of what exists.
        String internalId = internalIdOf(clientId);
        return new ProvisionedClient(internalId, clientId);
    }

    @Override
    public String regenerateSecret(String keycloakClientRef) {
        Map<String, Object> secret = client.post()
                .uri("/admin/realms/{realm}/clients/{id}/client-secret", realm, keycloakClientRef)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new PartnerProvisioningException(
                            "Regenerating a client secret failed with %s".formatted(response.getStatusCode()));
                })
                .body(SINGLE);
        if (secret == null || secret.get("value") == null) {
            throw new PartnerProvisioningException("Keycloak did not return a client secret value");
        }
        return String.valueOf(secret.get("value"));
    }

    @Override
    public void disable(String keycloakClientRef) {
        Map<String, Object> current = client.get()
                .uri("/admin/realms/{realm}/clients/{id}", realm, keycloakClientRef)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new PartnerProvisioningException(
                            "Client %s does not exist in Keycloak".formatted(keycloakClientRef));
                })
                .body(SINGLE);
        if (current == null) {
            throw new PartnerProvisioningException("Client %s does not exist in Keycloak".formatted(keycloakClientRef));
        }

        // Idempotent by inspection — the same shape KeycloakDeviceClientProvisioner.disable
        // and KeycloakOrganizationProvisioner.setOrganizationEnabled already use: a
        // retried revoke must not be a second write Keycloak has no reason to refuse
        // but that would still cost a round trip on every retry of an already-applied disable.
        if (Boolean.FALSE.equals(current.get("enabled"))) {
            return;
        }

        Map<String, Object> updated = new java.util.LinkedHashMap<>(current);
        updated.put("enabled", false);
        client.put()
                .uri("/admin/realms/{realm}/clients/{id}", realm, keycloakClientRef)
                .contentType(MediaType.APPLICATION_JSON)
                .body(updated)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new PartnerProvisioningException("Disabling client %s failed with %s"
                            .formatted(keycloakClientRef, response.getStatusCode()));
                })
                .toBodilessEntity();
    }

    private String internalIdOf(String clientId) {
        List<Map<String, Object>> found = client.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/clients")
                        .queryParam("clientId", clientId)
                        .build(realm))
                .retrieve()
                .body(LIST);
        if (found == null || found.isEmpty()) {
            throw new PartnerProvisioningException("Client %s was created but cannot be read back".formatted(clientId));
        }
        return String.valueOf(found.getFirst().get("id"));
    }

    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> SINGLE =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    private static final org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    /** Wraps a Keycloak Admin API failure this adapter cannot recover from on its own. */
    public static final class PartnerProvisioningException extends RuntimeException {
        public PartnerProvisioningException(String message) {
            super(message);
        }
    }
}
