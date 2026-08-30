package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.organizations.OrganizationDirectory;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner.OrganizationSnapshot;

/**
 * The read half of the ADR 0009 Keycloak adapter.
 *
 * <p>Separate from {@link KeycloakOrganizationProvisioner} because it is
 * instantiated twice against two different credentials: once with
 * {@code horecaos-provisioning}, so the provisioner can read back what it wrote,
 * and once with {@code horecaos-identity-reader}, so the scheduled drift report
 * holds nothing that could change what it is reporting on. The code is the same;
 * the capability is not, and that is the whole point of the split.
 *
 * <p>No Keycloak type escapes this class.
 */
public class KeycloakOrganizationDirectory implements OrganizationDirectory {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String realm;

    public KeycloakOrganizationDirectory(RestClient client, String realm) {
        this.client = client;
        this.realm = realm;
    }

    @Override
    public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = client.get()
                    .uri("/admin/realms/{realm}/organizations/{id}", realm, organizationId)
                    .retrieve()
                    .body(MAP);
            return body == null ? Optional.empty() : Optional.of(snapshotOf(body));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound absent) {
            // The organization is gone. Distinguished from every other failure
            // below, because "not there" is a drift finding and "cannot ask" is
            // not: reporting an unreachable Keycloak as a missing organization
            // would produce a drift report for every tenant at once.
            return Optional.empty();
        }
    }

    @Override
    public List<OrganizationSnapshot> findByAlias(String alias) {
        List<Map<String, Object>> all = client.get()
                .uri("/admin/realms/{realm}/organizations", realm)
                .retrieve()
                .body(LIST);
        if (all == null) {
            return List.of();
        }
        return all.stream()
                .filter(organization -> alias.equals(String.valueOf(organization.get("alias"))))
                .map(KeycloakOrganizationDirectory::snapshotOf)
                .toList();
    }

    private static OrganizationSnapshot snapshotOf(Map<String, Object> body) {
        return new OrganizationSnapshot(
                String.valueOf(body.get("id")),
                String.valueOf(body.get("alias")),
                String.valueOf(body.get("name")),
                !Boolean.FALSE.equals(body.get("enabled")));
    }
}
