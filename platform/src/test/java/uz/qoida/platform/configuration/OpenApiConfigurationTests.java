package uz.qoida.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

class OpenApiConfigurationTests {

    private final OpenApiConfiguration configuration = new OpenApiConfiguration();

    @Test
    void describesThePlatformAndKeycloakBearerAuthentication() {
        OpenAPI openApi = configuration.qoidaOpenApi("v1");

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Qoida Platform API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getSecurity())
                .singleElement()
                .satisfies(requirement -> assertThat(requirement)
                        .containsKey(OpenApiConfiguration.BEARER_SECURITY_SCHEME));

        SecurityScheme bearerScheme = openApi.getComponents()
                .getSecuritySchemes()
                .get(OpenApiConfiguration.BEARER_SECURITY_SCHEME);
        assertThat(bearerScheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearerScheme.getScheme()).isEqualTo("bearer");
        assertThat(bearerScheme.getBearerFormat()).isEqualTo("JWT");
    }
}
