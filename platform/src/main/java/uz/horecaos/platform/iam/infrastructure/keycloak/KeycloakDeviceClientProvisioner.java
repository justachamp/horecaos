package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.application.devices.DeviceClientProvisioner;

/**
 * Keycloak Admin API adapter for ADR 0079's device principal.
 *
 * <p>Direct HTTPS, not Camel — the same call {@code KeycloakOrganizationProvisioner}
 * makes for the identical reason (ADR 0007): this is a small set of
 * authenticated JSON calls on a control path, not mediation, throttling, or
 * independently scaled traffic. No Keycloak Admin Client DTO escapes this
 * class, so a Keycloak upgrade must not become a device-enrolment change.
 *
 * <p>Runs on its own credential, {@code horecaos-device-provisioning}, holding
 * only {@code manage-clients} — never {@code horecaos-provisioning}, which
 * {@code assign-service-account-roles.sh}'s own comment already records as
 * deliberately excluding {@code manage-clients}. Widening that credential to
 * cover this would be the least-privilege regression ADR 0079's own
 * Consequences names as a cost worth avoiding.
 */
public class KeycloakDeviceClientProvisioner implements DeviceClientProvisioner {

    private final RestClient client;
    private final String realm;
    private final String baseUrl;

    public KeycloakDeviceClientProvisioner(RestClient client, String realm, String baseUrl) {
        this.client = client;
        this.realm = realm;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProvisionedClient create(String displayLabel) {
        // A UUID suffix rather than a slug of the display name: the label is
        // an operator-chosen string with no uniqueness guarantee and no
        // character-set guarantee, and a client_id derived from it would
        // either collide or need its own escaping rules for no benefit — the
        // label lives on iam.device_principals.display_name, this identifier
        // never needs to be read by a person.
        String clientId = "kds-device-" + UUID.randomUUID();

        client.post()
                .uri("/admin/realms/{realm}/clients", realm)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "clientId", clientId,
                        "name", displayLabel,
                        "description", "ADR 0079 kitchen display device principal",
                        "protocol", "openid-connect",
                        "enabled", true,
                        "publicClient", false,
                        "serviceAccountsEnabled", true,
                        "standardFlowEnabled", false,
                        "implicitFlowEnabled", false,
                        "directAccessGrantsEnabled", false))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new DeviceProvisioningException(
                            "Creating Keycloak client %s failed with %s".formatted(clientId, response.getStatusCode()));
                })
                .toBodilessEntity();

        // Read back rather than trust a Location header, the identical
        // discipline KeycloakOrganizationProvisioner already applies: the
        // response body is not proof of what exists.
        String internalId = internalIdOf(clientId);

        Map<String, Object> serviceAccountUser = client.get()
                .uri("/admin/realms/{realm}/clients/{id}/service-account-user", realm, internalId)
                .retrieve()
                .body(SINGLE);
        if (serviceAccountUser == null || serviceAccountUser.get("id") == null) {
            throw new DeviceProvisioningException(
                    "Client %s was created but has no service-account user".formatted(clientId));
        }

        return new ProvisionedClient(internalId, clientId, String.valueOf(serviceAccountUser.get("id")));
    }

    @Override
    public String regenerateSecret(String keycloakClientInternalId) {
        Map<String, Object> secret = client.post()
                .uri("/admin/realms/{realm}/clients/{id}/client-secret", realm, keycloakClientInternalId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new DeviceProvisioningException(
                            "Regenerating a client secret failed with %s".formatted(response.getStatusCode()));
                })
                .body(SINGLE);
        if (secret == null || secret.get("value") == null) {
            throw new DeviceProvisioningException("Keycloak did not return a client secret value");
        }
        return String.valueOf(secret.get("value"));
    }

    @Override
    public void disable(String keycloakClientInternalId) {
        Map<String, Object> current = client.get()
                .uri("/admin/realms/{realm}/clients/{id}", realm, keycloakClientInternalId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new DeviceProvisioningException(
                            "Client %s does not exist in Keycloak".formatted(keycloakClientInternalId));
                })
                .body(SINGLE);
        if (current == null) {
            throw new DeviceProvisioningException(
                    "Client %s does not exist in Keycloak".formatted(keycloakClientInternalId));
        }

        // Idempotent by inspection, the same shape
        // KeycloakOrganizationProvisioner.setOrganizationEnabled uses: a
        // retried revoke must not be a second write Keycloak has no reason to
        // refuse but that would still cost a round trip on every retry of an
        // already-applied disable.
        if (Boolean.FALSE.equals(current.get("enabled"))) {
            return;
        }

        Map<String, Object> updated = new java.util.LinkedHashMap<>(current);
        updated.put("enabled", false);
        client.put()
                .uri("/admin/realms/{realm}/clients/{id}", realm, keycloakClientInternalId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(updated)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new DeviceProvisioningException("Disabling client %s failed with %s"
                            .formatted(keycloakClientInternalId, response.getStatusCode()));
                })
                .toBodilessEntity();
    }

    @Override
    public String tokenEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    private String internalIdOf(String clientId) {
        List<Map<String, Object>> found = client.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/clients")
                        .queryParam("clientId", clientId)
                        .build(realm))
                .retrieve()
                .body(LIST);
        if (found == null || found.isEmpty()) {
            throw new DeviceProvisioningException("Client %s was created but cannot be read back".formatted(clientId));
        }
        return String.valueOf(found.getFirst().get("id"));
    }

    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> SINGLE =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    private static final org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    /** Wraps a Keycloak Admin API failure this adapter cannot recover from on its own. */
    public static final class DeviceProvisioningException extends RuntimeException {
        public DeviceProvisioningException(String message) {
            super(message);
        }
    }
}
