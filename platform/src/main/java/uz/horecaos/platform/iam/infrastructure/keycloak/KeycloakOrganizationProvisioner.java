package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.organizations.OrganizationDirectory;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;

/**
 * Keycloak Admin API adapter for ADR 0009.
 *
 * <p>Direct HTTPS rather than Camel, and deliberately so: Camel earns its place
 * where mediation, throttling, or independent scaling apply, and this is a small
 * set of authenticated JSON calls on a control path. ADR 0007 records this as
 * the intended counter-example.
 *
 * <p>No Keycloak Admin Client DTO escapes this class. A Keycloak upgrade must
 * not become a tenancy change.
 *
 * <p>Reads go through {@link OrganizationDirectory} rather than being repeated
 * here, so the read path has one implementation and two credentials pointed at
 * it — this one provisioning, the drift report's read-only.
 */
public class KeycloakOrganizationProvisioner implements OrganizationProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOrganizationProvisioner.class);

    private final RestClient client;
    private final OrganizationDirectory directory;
    private final String realm;

    public KeycloakOrganizationProvisioner(RestClient client, OrganizationDirectory directory, String realm) {
        this.client = client;
        this.directory = directory;
        this.realm = realm;
    }

    @Override
    public OrganizationRef ensureOrganization(EnsureOrganization command) {
        // 1. A stored immutable id is the authority. Fetch by it.
        if (command.existingOrganizationId() != null
                && !command.existingOrganizationId().isBlank()) {
            return getOrganization(command.existingOrganizationId())
                    .map(existing -> new OrganizationRef(existing.organizationId(), existing.alias(), false))
                    .orElseThrow(() -> new OrganizationDriftException(
                            // Creating a replacement here would give one tenant two
                            // identities and orphan every membership on the first.
                            "Tenant %s references organization %s, which no longer exists in Keycloak"
                                    .formatted(command.tenantId(), command.existingOrganizationId())));
        }

        // 2. No stored id: reconcile by the deterministic alias before creating.
        List<OrganizationSnapshot> matches = directory.findByAlias(command.alias());
        if (matches.size() > 1) {
            throw new OrganizationDriftException(
                    "Alias %s matches %d organizations; resolve manually".formatted(command.alias(), matches.size()));
        }
        if (matches.size() == 1) {
            OrganizationSnapshot found = matches.getFirst();
            log.info(
                    "Linking existing Keycloak organization {} for tenant {}",
                    found.organizationId(),
                    command.tenantId());
            return new OrganizationRef(found.organizationId(), found.alias(), false);
        }

        client.post()
                .uri("/admin/realms/{realm}/organizations", realm)
                .body(Map.of(
                        "name",
                        command.displayName() == null ? command.alias() : command.displayName(),
                        "alias",
                        command.alias(),
                        "enabled",
                        true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    // A 409 is not automatically success: it may be an unrelated
                    // object with the same alias, so read back rather than assume.
                    throw new OrganizationDriftException("Creating organization %s failed with %s"
                            .formatted(command.alias(), response.getStatusCode()));
                })
                .toBodilessEntity();

        // 3. Read back. The response body is not trusted as proof of what exists.
        OrganizationSnapshot created = directory.findByAlias(command.alias()).stream()
                .findFirst()
                .orElseThrow(() -> new OrganizationDriftException(
                        "Organization %s was created but cannot be read back".formatted(command.alias())));

        return new OrganizationRef(created.organizationId(), created.alias(), true);
    }

    @Override
    public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
        return directory.getOrganization(organizationId);
    }

    @Override
    public MembershipRef ensureMembership(EnsureMembership command) {
        String subjectId = command.existingSubjectId();
        boolean created = false;

        if (subjectId == null || subjectId.isBlank()) {
            subjectId = findUserByEmail(command.email()).orElse(null);
        }
        if (subjectId == null) {
            client.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .body(Map.of(
                            "username",
                            command.email(),
                            "email",
                            command.email(),
                            "enabled",
                            true,
                            "emailVerified",
                            false))
                    .retrieve()
                    .toBodilessEntity();
            subjectId = findUserByEmail(command.email())
                    .orElseThrow(() -> new OrganizationDriftException(
                            "User %s was created but cannot be read back".formatted(command.email())));
            created = true;
        }

        if (isMember(command.organizationId(), subjectId)) {
            return new MembershipRef(command.organizationId(), subjectId, false);
        }

        client.post()
                .uri("/admin/realms/{realm}/organizations/{org}/members", realm, command.organizationId())
                // The content type is not decoration. This endpoint takes the bare
                // subject id as its body, and Spring's RestClient sends a String
                // body without a Content-Type unless one is named — which Keycloak
                // answers with 415, every time. Nothing caught it until this
                // adapter was pointed at a real realm, because a stub of
                // ensureMembership cannot disagree about a header.
                .contentType(MediaType.APPLICATION_JSON)
                .body(subjectId)
                .retrieve()
                .toBodilessEntity();

        return new MembershipRef(command.organizationId(), subjectId, created);
    }

    private boolean isMember(String organizationId, String subjectId) {
        List<Map<String, Object>> members = client.get()
                .uri("/admin/realms/{realm}/organizations/{org}/members", realm, organizationId)
                .retrieve()
                .body(LIST);
        return members != null
                && members.stream().anyMatch(member -> subjectId.equals(String.valueOf(member.get("id"))));
    }

    private Optional<String> findUserByEmail(String email) {
        List<Map<String, Object>> users = client.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("email", email)
                        .queryParam("exact", true)
                        .build(realm))
                .retrieve()
                .body(LIST);
        return users == null || users.isEmpty()
                ? Optional.empty()
                : Optional.of(String.valueOf(users.getFirst().get("id")));
    }

    private static final org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};
}
