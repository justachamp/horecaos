package uz.horecaos.platform.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    static final String BEARER_SECURITY_SCHEME = "bearerAuth";

    @Bean
    OpenAPI horecaosOpenApi(@Value("${horecaos.api.version}") String apiVersion) {
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Keycloak access token issued for the horecaos-api audience");

        return new OpenAPI()
                .info(new Info()
                        .title("HorecaOS Platform API")
                        .version(apiVersion)
                        .description("Tenant-aware APIs for the HorecaOS SaaS commerce and delivery platform."))
                .components(new Components().addSecuritySchemes(BEARER_SECURITY_SCHEME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SECURITY_SCHEME));
    }

    /**
     * One additional, additive Springdoc document per {@link OpenApiSurface}, published at
     * {@code /v3/api-docs/<id>}. These groups filter the same running document by path; they
     * never change what {@code /v3/api-docs} itself returns, so the full v1 contract and its
     * baseline are unaffected by this configuration.
     */
    @Bean
    GroupedOpenApi storefrontOpenApi() {
        return groupedOpenApi(OpenApiSurface.STOREFRONT);
    }

    @Bean
    GroupedOpenApi controlPlaneOpenApi() {
        return groupedOpenApi(OpenApiSurface.CONTROL_PLANE);
    }

    @Bean
    GroupedOpenApi providersOpenApi() {
        return groupedOpenApi(OpenApiSurface.PROVIDERS);
    }

    @Bean
    GroupedOpenApi operationsOpenApi() {
        return groupedOpenApi(OpenApiSurface.OPERATIONS);
    }

    private static GroupedOpenApi groupedOpenApi(OpenApiSurface surface) {
        return GroupedOpenApi.builder()
                .group(surface.id())
                .pathsToMatch(surface.pathPatterns().toArray(new String[0]))
                .build();
    }
}
