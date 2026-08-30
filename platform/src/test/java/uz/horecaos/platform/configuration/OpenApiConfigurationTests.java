package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

class OpenApiConfigurationTests {

    private final OpenApiConfiguration configuration = new OpenApiConfiguration();

    @Test
    void describesThePlatformAndKeycloakBearerAuthentication() {
        OpenAPI openApi = configuration.horecaosOpenApi("v1");

        assertThat(openApi.getInfo().getTitle()).isEqualTo("HorecaOS Platform API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getSecurity())
                .singleElement()
                .satisfies(requirement ->
                        assertThat(requirement).containsKey(OpenApiConfiguration.BEARER_SECURITY_SCHEME));

        SecurityScheme bearerScheme =
                openApi.getComponents().getSecuritySchemes().get(OpenApiConfiguration.BEARER_SECURITY_SCHEME);
        assertThat(bearerScheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearerScheme.getScheme()).isEqualTo("bearer");
        assertThat(bearerScheme.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    void registersOneGroupedDocumentPerSurfaceWithoutOverlappingPathPatterns() {
        List<GroupedOpenApi> groups = List.of(
                configuration.storefrontOpenApi(),
                configuration.controlPlaneOpenApi(),
                configuration.providersOpenApi(),
                configuration.operationsOpenApi());

        assertThat(groups.stream().map(GroupedOpenApi::getGroup))
                .as("group ids are stable identifiers and become /v3/api-docs/<id> URLs")
                .containsExactlyInAnyOrder("storefront", "control-plane", "providers", "operations");

        for (GroupedOpenApi group : groups) {
            assertThat(group.getPathsToMatch())
                    .as("group %s must declare at least one path pattern", group.getGroup())
                    .isNotEmpty();
        }

        Set<String> allPatterns = groups.stream()
                .flatMap(group -> group.getPathsToMatch().stream())
                .collect(Collectors.toSet());
        long totalPatterns = groups.stream()
                .mapToLong(group -> group.getPathsToMatch().size())
                .sum();
        assertThat(allPatterns)
                .as("no path pattern is declared by more than one group")
                .hasSize((int) totalPatterns);
    }
}
