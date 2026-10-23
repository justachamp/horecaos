package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Row 10.12 (ADR 0149, Decisions 4 and 5): a reason's customer wording is authored in the languages
 * its tenant serves, and the console asks the backend which they are.
 *
 * <p>{@code OrderOutcomeReasonService} refuses a reason that leaves out a wording in any language of
 * the tenant's union, so a form that could send only {@code ru}, {@code uz-Latn} and {@code en}
 * would be refused for the first tenant that serves a fourth. {@code GET .../locale-set} is the same
 * question the write side answers, asked before the form is drawn. These tests prove the two agree
 * over real HTTP: the set is the union with the first brand's default first, it needs {@code
 * order.read} at tenant scope, and a create that carries a wording for exactly that set is accepted
 * while one that omits the fourth language is refused with the language named.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderOutcomeReasonLocaleSetEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000f1");
    private static final UUID FIRST_BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000f2");
    private static final UUID SECOND_BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000f3");

    private static final String OWNER = "reason-locales-owner";
    private static final String NOBODY = "reason-locales-nobody";

    private static final String REASONS = "/api/v1/operations/tenants/" + TENANT + "/order-outcome-reasons";

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reason_texts").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reasons CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'reason-locales', 'Reason Locales', 'Reason Locales',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        brand(FIRST_BRAND, "AAA");
        brand(SECOND_BRAND, "BBB");
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
    }

    @Test
    @DisplayName(
            "a tenant whose brands chose nothing is offered the registry's content languages, and is told it chose none")
    void anUnconfiguredTenantIsOfferedTheContentLanguages() throws Exception {
        JsonNode body = localeSet(OWNER);

        assertThat(strings(body.path("locales"))).containsExactly("ru", "uz-Latn", "en");
        assertThat(body.path("defaultLocale").asText()).isEqualTo("ru");
        assertThat(body.path("configured").asBoolean()).isFalse();
    }

    @Test
    @DisplayName(
            "the set is the union of the brands' languages with the first brand's default first, and a create must cover exactly it")
    void theSetIsTheUnionAndTheWriteSideRequiresExactlyIt() throws Exception {
        // The first brand (display name AAA) serves Uzbek with Russian; the second serves Kazakh.
        brandLocale(FIRST_BRAND, "uz-Latn", true);
        brandLocale(FIRST_BRAND, "ru", false);
        brandLocale(SECOND_BRAND, "kk", true);

        JsonNode body = localeSet(OWNER);

        assertThat(strings(body.path("locales")))
                .as("default first, then the platform's order, then any other")
                .containsExactly("uz-Latn", "ru", "kk");
        assertThat(body.path("defaultLocale").asText()).isEqualTo("uz-Latn");
        assertThat(body.path("configured").asBoolean()).isTrue();

        MvcResult missingTheFourth = create("""
                {"kind":"CANCELLATION","internalName":"Closed early","systemCategory":"RESTAURANT_REFUSED",
                 "stockDisposition":"RETURN_TO_STOCK","liabilityParty":"TENANT","customerRefund":"FULL",
                 "customerTexts":{"uz-Latn":"Yopildi","ru":"Закрыто"}}""");
        assertThat(missingTheFourth.getResponse().getStatus()).isEqualTo(400);
        assertThat(missingTheFourth.getResponse().getContentAsString(UTF_8))
                .as("the refusal names the language the form did not carry")
                .contains("kk");

        MvcResult covering = create("""
                {"kind":"CANCELLATION","internalName":"Closed early","systemCategory":"RESTAURANT_REFUSED",
                 "stockDisposition":"RETURN_TO_STOCK","liabilityParty":"TENANT","customerRefund":"FULL",
                 "customerTexts":{"uz-Latn":"Yopildi","ru":"Закрыто","kk":"Жабылды"}}""");
        assertThat(covering.getResponse().getStatus())
                .as("a body carrying a wording for exactly the set the endpoint named is accepted")
                .isIn(200, 201);
    }

    @Test
    @DisplayName("a principal with no order.read at the tenant is refused with the capability it lacked")
    void aPrincipalWithoutOrderReadIsRefused() throws Exception {
        MvcResult result =
                mvc.perform(get(REASONS + "/locale-set").with(tokenFor(NOBODY))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString(UTF_8)).contains(Capability.ORDER_READ.code());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode localeSet(String subject) throws Exception {
        MvcResult result = mvc.perform(get(REASONS + "/locale-set").with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private MvcResult create(String body) throws Exception {
        return mvc.perform(post(REASONS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static List<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private void brand(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenant, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenant", TENANT)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    private void brandLocale(UUID brand, String locale, boolean isDefault) {
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenant, :brand, :locale, :isDefault)
                """)
                .param("tenant", TENANT)
                .param("brand", brand)
                .param("locale", locale)
                .param("isDefault", isDefault)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'reason locale-set endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
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
