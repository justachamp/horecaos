package uz.horecaos.platform.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

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
}
