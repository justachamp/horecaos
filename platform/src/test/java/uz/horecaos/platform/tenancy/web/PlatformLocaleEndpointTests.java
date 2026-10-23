package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET /api/v1/{surface}/locales} (ADR 0149, Decision 5): the registry, read over HTTP through
 * the real security chain.
 *
 * <p>What the endpoint promises that a unit test of the registry cannot: the staff surfaces answer
 * any signed-in principal and nobody else, the storefront's answers a visitor with no account, and
 * the answer is the registry's, including the languages that are declared and not live (a client
 * must be able to tell them apart) and the catalog's own code for Uzbek.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformLocaleEndpointTests {

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
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

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("a signed-in staff member with no grant at all reads the registry on both staff surfaces")
    void anyPrincipalReadsTheStaffSurfaces() throws Exception {
        for (String surface : List.of("operations", "control-plane")) {
            MvcResult result = mvc.perform(
                            get("/api/v1/" + surface + "/locales").with(token("nobody-in-particular")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as(surface).isEqualTo(200);
            JsonNode body = json(result);
            assertThat(body.path("fallback").asText()).isEqualTo("ru");
            assertThat(tags(body)).as(surface).containsExactly("ru", "uz-Latn", "en", "kk", "ka");
        }
    }

    @Test
    @DisplayName("the staff surfaces refuse a caller who is not signed in")
    void theStaffSurfacesRefuseAnonymousCallers() throws Exception {
        for (String surface : List.of("operations", "control-plane")) {
            assertThat(mvc.perform(get("/api/v1/" + surface + "/locales"))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(surface)
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("the storefront's twin answers a visitor with no account, because its picker paints first")
    void theStorefrontAnswersAnAnonymousVisitor() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/storefront/locales")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(tags(json(result))).containsExactly("ru", "uz-Latn", "en", "kk", "ka");
    }

    @Test
    @DisplayName("the answer says which languages are live where, so a declared-but-inactive one is not offered")
    void theAnswerSeparatesLiveFromDeclared() throws Exception {
        JsonNode body = json(mvc.perform(get("/api/v1/storefront/locales")).andReturn());

        for (JsonNode locale : body.path("locales")) {
            String tag = locale.path("tag").asText();
            List<String> tiers =
                    values(locale.path("tiers")).map(JsonNode::asText).toList();
            if (List.of("ru", "uz-Latn", "en").contains(tag)) {
                assertThat(tiers).as(tag).containsExactly("CONTENT", "MESSAGES", "STAFF_UI");
            } else {
                assertThat(tiers)
                        .as("%s is declared, not live: no tier lists it, so no client may offer it", tag)
                        .isEmpty();
            }
            assertThat(locale.path("direction").asText()).as(tag).isEqualTo("LTR");
            assertThat(locale.path("names").path(tag).asText())
                    .as("%s names itself", tag)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Uzbek carries the catalog's own code beside its tag, so a client never converts it itself")
    void uzbekCarriesItsCatalogCode() throws Exception {
        JsonNode body = json(mvc.perform(get("/api/v1/storefront/locales")).andReturn());

        JsonNode uzbek = values(body.path("locales"))
                .filter(locale -> "uz-Latn".equals(locale.path("tag").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(uzbek.path("catalogCode").asText()).isEqualTo("uz");
        assertThat(values(uzbek.path("inputAliases")).map(JsonNode::asText).toList())
                .containsExactly("uz");
        assertThat(uzbek.path("script").asText()).isEqualTo("LATN");

        values(body.path("locales"))
                .filter(locale -> !"uz-Latn".equals(locale.path("tag").asText()))
                .forEach(locale -> assertThat(locale.path("catalogCode").asText())
                        .as("every other language's catalog code is its own tag")
                        .isEqualTo(locale.path("tag").asText()));
    }

    // ------------------------------------------------------------ one brand's languages

    @Test
    @DisplayName(
            "a visitor with no account reads the languages one brand serves, its own default first, and may cache the answer")
    void aBrandsServedLanguagesAreReadableBeforeSignIn() throws Exception {
        UUID tenant = seedTenant("served");
        UUID brand = seedBrand(tenant, "SERVED");
        seedBrandLocale(tenant, brand, "ru", false);
        seedBrandLocale(tenant, brand, "uz-Latn", true);

        MvcResult result = mvc.perform(get(brandLocalesPath(tenant, brand))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(values(body.path("locales")).map(JsonNode::asText).toList())
                .as("the brand's own set, its own default first, then the platform's order")
                .containsExactly("uz-Latn", "ru");
        assertThat(body.path("defaultLocale").asText()).isEqualTo("uz-Latn");
        assertThat(body.path("configured").asBoolean()).isTrue();
        assertThat(result.getResponse().getHeader("Cache-Control"))
                .contains("public")
                .contains("max-age=300");
    }

    @Test
    @DisplayName(
            "a language the brand stored but the registry has not made live is not offered, and cannot be the default")
    void aDeclaredButInactiveLanguageIsNotOffered() throws Exception {
        UUID tenant = seedTenant("declared");
        UUID brand = seedBrand(tenant, "DECLARED");
        seedBrandLocale(tenant, brand, "kk", true);
        seedBrandLocale(tenant, brand, "ru", false);

        JsonNode body = json(mvc.perform(get(brandLocalesPath(tenant, brand))).andReturn());

        assertThat(values(body.path("locales")).map(JsonNode::asText).toList())
                .as("kk is declared in the registry and live in no tier: a storefront must not offer it")
                .containsExactly("ru");
        assertThat(body.path("defaultLocale").asText())
                .as("a default that is not live cannot be where a storefront starts")
                .isEqualTo("ru");
    }

    @Test
    @DisplayName(
            "a brand that chose nothing, and a brand id that is not the tenant's, both answer with the platform's content languages")
    void anUnconfiguredOrUnknownBrandAnswersTheSame() throws Exception {
        UUID tenant = seedTenant("plain");
        UUID plain = seedBrand(tenant, "PLAIN");
        UUID otherTenant = seedTenant("other");
        UUID foreign = seedBrand(otherTenant, "FOREIGN");
        seedBrandLocale(otherTenant, foreign, "en", true);

        for (UUID unknown : List.of(plain, foreign, UUID.randomUUID())) {
            JsonNode body =
                    json(mvc.perform(get(brandLocalesPath(tenant, unknown))).andReturn());

            assertThat(values(body.path("locales")).map(JsonNode::asText).toList())
                    .as("brand %s read under the tenant that does not own it", unknown)
                    .containsExactly("ru", "uz-Latn", "en");
            assertThat(body.path("defaultLocale").asText()).isEqualTo("ru");
            assertThat(body.path("configured").asBoolean())
                    .as("nothing is configured under this tenant for this id, and the answer does not say otherwise")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("it is a read: a POST to it is not an endpoint")
    void itIsOnlyARead() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/storefront/locales")).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404, 405);
    }

    // ------------------------------------------------------------------ helpers

    private static String brandLocalesPath(UUID tenant, UUID brand) {
        return "/api/v1/storefront/tenants/" + tenant + "/brands/" + brand + "/locales";
    }

    private UUID seedTenant(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("slug", slug + "-" + id.toString().substring(0, 8))
                .update();
        return id;
    }

    private UUID seedBrand(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenant, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenant", tenant)
                .param("code", code)
                .param("slug", code.toLowerCase() + "-" + id.toString().substring(0, 8))
                .update();
        return id;
    }

    private void seedBrandLocale(UUID tenant, UUID brand, String locale, boolean isDefault) {
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenant, :brand, :locale, :isDefault)
                """)
                .param("tenant", tenant)
                .param("brand", brand)
                .param("locale", locale)
                .param("isDefault", isDefault)
                .update();
    }

    private static List<String> tags(JsonNode body) {
        return values(body.path("locales"))
                .map(locale -> locale.path("tag").asText())
                .toList();
    }

    private static java.util.stream.Stream<JsonNode> values(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JsonMapper.builder().build().readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return value -> Jwt.withTokenValue(value)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
