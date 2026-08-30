package uz.horecaos.platform.iam.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtCurrentActorTests {

    private final JwtCurrentActor currentActor = new JwtCurrentActor("horecaos-api");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsMultipleOrganizationsAndKeepsTheirRolesIsolated() {
        Jwt jwt = jwt(Map.of(
                "resource_access", clientRoles("platform-admin"),
                "organization", Map.of(
                        "tenant-a", organization("organization-a", "tenant-owner"),
                        "tenant-b", organization("organization-b", "tenant-viewer"))));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        var actor = currentActor.get();

        assertThat(actor.subject()).isEqualTo("keycloak-user-42");
        assertThat(actor.hasGlobalRole("platform-admin")).isTrue();
        assertThat(actor.belongsToOrganization("organization-a")).isTrue();
        assertThat(actor.belongsToOrganization("organization-b")).isTrue();
        assertThat(actor.hasOrganizationRole("organization-a", "tenant-owner")).isTrue();
        assertThat(actor.hasOrganizationRole("organization-b", "tenant-owner")).isFalse();
    }

    @Test
    void ignoresAliasOnlyOrganizationClaimsWithoutImmutableIds() {
        Jwt jwt = jwt(Map.of("organization", Map.of("tenant-a", Map.of())));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        assertThat(currentActor.get().organizationRoles()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("keycloak-user-42");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Map<String, Object> organization(String id, String... roles) {
        return Map.of(
                "id", id,
                "resource_access", clientRoles(roles));
    }

    private static Map<String, Object> clientRoles(String... roles) {
        return Map.of("horecaos-api", Map.of("roles", List.of(roles)));
    }
}
