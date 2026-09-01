package uz.horecaos.platform.configuration;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @ConditionalOnProperty} matches springdoc's own gate on {@code springdoc.api-docs.enabled}
 * (see {@code application.yml}, and {@code HORECAOS_API_DOCS_ENABLED} in the production compose
 * files): with it {@code false}, springdoc's autoconfiguration never creates an {@link
 * ObjectMapperProvider} bean, and this class's {@code modelResolver} bean unconditionally required
 * one — an {@code UnsatisfiedDependencyException} that failed the whole application context, found
 * by the ADR 0061 production deployment wave's own local proof (compose.production.yml sets
 * exactly {@code HORECAOS_API_DOCS_ENABLED=false}, deliberately, to keep the contract off a
 * public host — see that file's own comment). {@code matchIfMissing = true} preserves the current
 * default-enabled behaviour everywhere the property is unset, which is every profile except
 * production.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfiguration {

    static final String BEARER_SECURITY_SCHEME = "bearerAuth";

    /**
     * Replaces springdoc's default {@link ModelResolver} so component schema names come from
     * {@link HorecaosTypeNameResolver} instead of the plain-simple-name default. This bean is
     * itself exactly {@code ModelResolver} — swagger-core's {@code ModelConverters} always
     * registers a default instance of that same class, and springdoc's {@code
     * ModelConverterRegistrar} keys replacement on the converter's class, so registering this
     * bean swaps the default out rather than adding a second, competing converter.
     */
    @Bean
    ModelResolver modelResolver(ObjectMapperProvider objectMapperProvider) {
        return new ModelResolver(objectMapperProvider.jsonMapper(), new HorecaosTypeNameResolver())
                .openapi31(objectMapperProvider.isOpenapi31());
    }

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
