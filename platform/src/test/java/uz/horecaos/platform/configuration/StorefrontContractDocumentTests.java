package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppHeaders;
import uz.horecaos.platform.storefrontapps.api.StorefrontContractPolicy;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What the {@code storefront} group document promises, read from the running server (ADR 0070).
 *
 * <p>The group is a product surface a vendor builds against, so three things have to be true
 * of the document itself and not of any prose elsewhere: every path in it has been <em>decided</em>
 * as published or internal; every operation says which; and the public/confidential distinction
 * and the deprecation policy are in it. The decisions are a list a person edits
 * ({@link StorefrontPublishedSurface}), and the first test is what stops that list drifting from
 * the controllers: a new storefront path nobody classified, or a classified one that no longer
 * exists, fails the build here instead of being published by default or promised by accident.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontContractDocumentTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method runs.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront contract document test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("every storefront path is decided as published or internal, and every decision names a real path")
    void everyStorefrontPathIsClassifiedExactlyOnce() throws Exception {
        Set<String> documented = pathNames(document("/v3/api-docs/storefront"));

        assertThat(StorefrontPublishedSurface.PUBLISHED)
                .as("a path cannot be both promised and internal")
                .doesNotContainAnyElementsOf(StorefrontPublishedSurface.INTERNAL.keySet());
        Set<String> decided = new HashSet<>(StorefrontPublishedSurface.classified());

        Set<String> undecided = new HashSet<>(documented);
        undecided.removeAll(decided);
        assertThat(undecided).as("""
                        These storefront paths are in the contract and nobody has decided whether they are \
                        promised. Name each in StorefrontPublishedSurface.PUBLISHED, or in INTERNAL with the \
                        reason it is not part of what a vendor may build on (ADR 0070).""").isEmpty();

        Set<String> stale = new HashSet<>(decided);
        stale.removeAll(documented);
        assertThat(stale)
                .as("these decisions name a path that is no longer in the storefront document")
                .isEmpty();
    }

    @Test
    void everyOperationCarriesItsSurfaceMarkerAndItAgreesWithTheDecision() throws Exception {
        JsonNode paths = document("/v3/api-docs/storefront").path("paths");
        List<String> wrong = new ArrayList<>();
        int operations = 0;
        for (Iterator<Map.Entry<String, JsonNode>> path = paths.properties().iterator(); path.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = path.next();
            String expected = StorefrontPublishedSurface.isPublished(entry.getKey())
                    ? StorefrontContractPolicy.SURFACE_PUBLISHED
                    : StorefrontContractPolicy.SURFACE_INTERNAL;
            for (Map.Entry<String, JsonNode> method : entry.getValue().properties()) {
                if (!Set.of("get", "put", "post", "delete", "patch").contains(method.getKey())) {
                    continue;
                }
                operations++;
                String actual = method.getValue()
                        .path(StorefrontContractPolicy.SURFACE_EXTENSION)
                        .asString("");
                if (!expected.equals(actual)) {
                    wrong.add(method.getKey().toUpperCase() + " " + entry.getKey() + " is '" + actual + "', expected '"
                            + expected + "'");
                }
            }
        }
        assertThat(operations).as("the scan found the storefront operations").isGreaterThan(60);
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName(
            "the telegram and dine-in QR routes ADR 0070 recommends keeping internal are internal, the commerce is promised")
    void theRecommendedInternalSetIsInternalAndTheCommerceIsPromised() {
        assertThat(StorefrontPublishedSurface.INTERNAL.keySet())
                .allMatch(path -> path.contains("/telegram/") || path.contains("/dine-in/") || path.endsWith("/table"));
        assertThat(StorefrontPublishedSurface.isPublished(
                        "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/checkouts"))
                .isTrue();
        assertThat(StorefrontPublishedSurface.isPublished(
                        "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/menu"))
                .isTrue();
        assertThat(StorefrontPublishedSurface.isPublished("/api/v1/storefront/dine-in/qr/token-exchanges"))
                .isFalse();
        assertThat(StorefrontPublishedSurface.isPublished(
                        "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/telegram/mini-app-link"))
                .isFalse();
        assertThat(StorefrontPublishedSurface.isPublished("/api/v1/storefront/a-path-nobody-decided"))
                .as("an undecided path is not promised")
                .isFalse();
    }

    @Test
    @DisplayName("the document states the public/confidential distinction, the app headers and the deprecation policy")
    void theDocumentCarriesItsOwnContract() throws Exception {
        JsonNode document = document("/v3/api-docs/storefront");
        String description = document.path("info").path("description").asString("");

        assertThat(description)
                .contains(StorefrontAppHeaders.APP_ID)
                .contains(StorefrontAppHeaders.APP_SECRET)
                .contains("public client")
                .contains("confidential client")
                .contains("not authenticated")
                .contains("never removed")
                .contains("Deprecation policy")
                .contains(StorefrontContractPolicy.DEPRECATION_WINDOW_MONTHS + " months")
                .contains("x-horecaos-surface");

        assertThat(document.path("info")
                        .path("x-horecaos-contract")
                        .path("deprecationWindowMonths")
                        .asInt())
                .isEqualTo(StorefrontContractPolicy.DEPRECATION_WINDOW_MONTHS);

        JsonNode schemes = document.path("components").path("securitySchemes");
        assertThat(schemes.path("storefrontAppId").path("in").asString()).isEqualTo("header");
        assertThat(schemes.path("storefrontAppId").path("name").asString()).isEqualTo(StorefrontAppHeaders.APP_ID);
        assertThat(schemes.path("storefrontAppSecret").path("name").asString())
                .isEqualTo(StorefrontAppHeaders.APP_SECRET);
    }

    @Test
    void onlyTheStorefrontGroupCarriesTheOverlay() throws Exception {
        for (String url : List.of(
                "/v3/api-docs", "/v3/api-docs/operations", "/v3/api-docs/control-plane", "/v3/api-docs/providers")) {
            String body = mvc.perform(MockMvcRequestBuilders.get(url))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body)
                    .as("%s is the unchanged v1 view of the same API", url)
                    .doesNotContain(StorefrontContractPolicy.SURFACE_EXTENSION)
                    .doesNotContain("x-horecaos-contract");
        }
    }

    @Test
    void theTenantAndPlatformScreensAreInTheirOwnGroups() throws Exception {
        assertThat(pathNames(document("/v3/api-docs/control-plane")))
                .contains("/api/v1/control-plane/storefront-apps", "/api/v1/control-plane/storefront-apps/{appId}");
        assertThat(pathNames(document("/v3/api-docs/operations")))
                .contains("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/storefront-apps");
        assertThat(pathNames(document("/v3/api-docs/storefront")))
                .as("registering and authorising apps is not part of the storefront contract a vendor builds against")
                .noneMatch(path -> path.contains("storefront-apps"));
    }

    private JsonNode document(String url) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get(url))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode document = JSON.readTree(body);
        assertThat(document.path("openapi").asString()).startsWith("3.");
        return document;
    }

    private static Set<String> pathNames(JsonNode document) {
        Set<String> names = new HashSet<>();
        document.path("paths").propertyNames().forEach(names::add);
        return names;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
