package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
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

    @Test
    @DisplayName("it is a read: a POST to it is not an endpoint")
    void itIsOnlyARead() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/storefront/locales")).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404, 405);
    }

    // ------------------------------------------------------------------ helpers

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
