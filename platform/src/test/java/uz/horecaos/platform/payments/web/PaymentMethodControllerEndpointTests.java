package uz.horecaos.platform.payments.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Wave P26/P33, second-pass adversarial review: {@code PaymentMethodController}
 * was new this integration with no HTTP-level test at all — {@code
 * PaymentMethodRegistryServiceTests} calls the service directly with a
 * hand-built actor, bypassing Spring Security and {@code @RequiresCapability}
 * entirely, so the {@code PAYMENT_METHOD_MANAGE} vs {@code PAYMENT_METHOD_READ}
 * split declared on the controller was never proven against a real request.
 * Follows {@code MerchantBindingControllerEndpointTests}' own shape: a real
 * JWT through the real interceptor stack, a role that carries plenty of other
 * tenant authority but not this one still refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentMethodControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000a1");

    private static final String OWNER = "payment-method-owner";
    private static final String FINANCE = "payment-method-finance";

    private static final String METHODS = "/api/v1/operations/tenants/" + TENANT + "/payment-methods";

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        // TENANT_FINANCE carries PAYMENT_METHOD_READ only -- "registering or
        // disabling a method stays with the owner and the administrator" per
        // that role's own doc.
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
    }

    @Test
    void financeCanListButNotCreate() throws Exception {
        MvcResult list = mvc.perform(get(METHODS).with(tokenFor(FINANCE))).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);

        MvcResult refused = mvc.perform(post(METHODS)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("CASH")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.PAYMENT_METHOD_MANAGE.code());
        assertThat(methodCount()).isZero();
    }

    @Test
    void financeCannotUpdateActivateOrDisable() throws Exception {
        UUID methodId = createMethod("CARD");

        MvcResult updateRefused = mvc.perform(put(METHODS + "/" + methodId)
                        .with(tokenFor(FINANCE))
                        .queryParam("expectedVersion", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Hacked","icon":null,"sortOrder":0,
                                 "providerInstallationId":null,"contractReference":null}
                                """))
                .andReturn();
        assertThat(updateRefused.getResponse().getStatus()).isEqualTo(403);

        MvcResult disableRefused = mvc.perform(post(METHODS + "/" + methodId + "/disable")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "disable-refused")
                        .queryParam("expectedVersion", "1"))
                .andReturn();
        assertThat(disableRefused.getResponse().getStatus()).isEqualTo(403);

        MvcResult activateRefused = mvc.perform(post(METHODS + "/" + methodId + "/activate")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-refused")
                        .queryParam("expectedVersion", "1"))
                .andReturn();
        assertThat(activateRefused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anOwnerCanRegisterListAndDisableAMethod() throws Exception {
        MvcResult created = mvc.perform(post(METHODS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("CLICK")))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getContentAsString()).contains("\"code\":\"CLICK\"");

        MvcResult list = mvc.perform(get(METHODS).with(tokenFor(OWNER))).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains("\"code\":\"CLICK\"");

        UUID methodId = methodIdByCode("CLICK");
        MvcResult disabled = mvc.perform(post(METHODS + "/" + methodId + "/disable")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "disable-ok")
                        .queryParam("expectedVersion", "1"))
                .andReturn();
        assertThat(disabled.getResponse().getStatus()).isEqualTo(200);
        assertThat(disabled.getResponse().getContentAsString()).contains("\"status\":\"DISABLED\"");
    }

    // ------------------------------------------------------------------ fixtures

    private UUID createMethod(String code) throws Exception {
        mvc.perform(post(METHODS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-" + code)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(code)));
        return methodIdByCode(code);
    }

    private UUID methodIdByCode(String code) {
        return jdbc.sql("SELECT id FROM payments.payment_methods WHERE tenant_id = :t AND code = :code")
                .param("t", TENANT)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private long methodCount() {
        return jdbc.sql("SELECT count(*) FROM payments.payment_methods WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private static String createBody(String code) {
        return """
                {"code":"%s","displayName":"%s","responsibility":"OPERATOR","icon":null,
                 "sortOrder":0,"providerInstallationId":null,"contractReference":null}
                """.formatted(code, code);
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'payment-method-endpoint', 'Payment Method', 'Payment Method',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'payment method endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
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
