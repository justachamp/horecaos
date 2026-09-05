package uz.horecaos.platform.payments.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0065's door for a merchant binding's credential (ADR 0013, ADR 0026, ADR
 * 0028). Unlike {@code ProviderInstallationSecretValueRotationEndpointTests}'
 * Telegram case, neither Click's nor Payme's Merchant API gives HorecaOS a
 * harmless outbound call, so this endpoint cannot verify before it flips the
 * reference -- that absence is exactly what this suite asserts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MerchantBindingSecretRotationEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-7000-7000-8000-0000000000b1");
    private static final UUID LEGAL_ENTITY = UUID.fromString("018f9b20-7000-7000-8000-0000000000c1");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000d1");
    private static final UUID INTEGRATION_BINDING = UUID.fromString("018f9b20-7000-7000-8000-0000000000e1");

    private static final String OWNER = "merchant-rotation-owner";

    private static final String ORIGINAL_SECRET_REFERENCE = "horecaos:test:provider_payment:tenant:click-original";

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

    private UUID bindingId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedProviderEnvironment();
        roleRegistry.synchronize();
        insertTenantAndBrand();
        insertLegalEntity();
        insertInstallationAndBinding();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        bindingId = registerBinding();
    }

    @Test
    void anOwnerRotatesTheCredentialThroughTheDoor() throws Exception {
        MvcResult result = mvc.perform(post(rotatePath(bindingId))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "mb-rotate-1")
                        .queryParam("expectedVersion", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest("a-brand-new-click-secret-key", "Click rotated their key")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("only the new reference ever leaves this endpoint")
                .doesNotContain("a-brand-new-click-secret-key");

        String newReference = jdbc.sql("SELECT secret_reference FROM payments.merchant_bindings WHERE id = :id")
                .param("id", bindingId)
                .query(String.class)
                .single();
        assertThat(newReference)
                .as("the door mints a fresh reference; it is never the old one restated")
                .isNotEqualTo(ORIGINAL_SECRET_REFERENCE)
                .startsWith("horecaos:")
                .contains("provider_payment");

        assertThat(jdbc.sql("SELECT last_secret_rotated_at FROM payments.merchant_bindings WHERE id = :id")
                        .param("id", bindingId)
                        .query(java.time.OffsetDateTime.class)
                        .single())
                .isNotNull();
        assertThat(jdbc.sql("SELECT version FROM payments.merchant_bindings WHERE id = :id")
                        .param("id", bindingId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);

        List<Map<String, Object>> auditRows =
                jdbc.sql("""
                SELECT change_document FROM audit.audit_events
                WHERE action_code = 'payment.merchant_binding_secret_rotated' AND actor_subject = :subject
                """).param("subject", OWNER).query().listOfRows();
        assertThat(auditRows).hasSize(1);
        assertThat(String.valueOf(auditRows.getFirst().get("change_document")))
                .contains(newReference)
                .doesNotContain("a-brand-new-click-secret-key");
    }

    @Test
    void aStaleVersionIsRefusedAndWritesNothing() throws Exception {
        MvcResult result = mvc.perform(post(rotatePath(bindingId))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "mb-rotate-stale")
                        .queryParam("expectedVersion", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest("a-key-that-should-not-land", "Wrong version")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(jdbc.sql("SELECT secret_reference FROM payments.merchant_bindings WHERE id = :id")
                        .param("id", bindingId)
                        .query(String.class)
                        .single())
                .isEqualTo(ORIGINAL_SECRET_REFERENCE);
    }

    // ------------------------------------------------------------------ fixtures

    private static String rotatePath(UUID bindingId) {
        return "/api/v1/operations/tenants/" + TENANT + "/merchant-bindings/" + bindingId + "/secret-rotations";
    }

    private static String rotateRequest(String value, String reason) {
        return "{\"value\":\"" + value + "\",\"reason\":\"" + reason + "\"}";
    }

    private UUID registerBinding() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/merchant-bindings")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "mb-rotate-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalEntityId":"%s","providerType":"CLICK","installationId":"%s",
                                 "integrationBindingId":"%s","merchantAccountReference":"svc-rotation",
                                 "merchantUserReference":null,"merchantIdReference":null,
                                 "secretReference":"%s","callbackPathSegment":"seg-rotation1",
                                 "supportsReversal":true,"supportsPartnerFiscalization":true,
                                 "effectiveFrom":"2026-01-01"}
                                """.formatted(
                                        LEGAL_ENTITY, INSTALLATION, INTEGRATION_BINDING, ORIGINAL_SECRET_REFERENCE)))
                .andReturn();
        return jdbc.sql("""
                SELECT id FROM payments.merchant_bindings
                WHERE tenant_id = :tenantId AND merchant_account_reference = 'svc-rotation'
                """).param("tenantId", TENANT).query(UUID.class).single();
    }

    private void seedProviderEnvironment() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('click-sandbox-mb-rotation', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant',
                    false, 'api.click.uz')
                ON CONFLICT DO NOTHING
                """).update();
    }

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'merchant-binding-rotation', 'Merchant Rotation', 'Merchant Rotation',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void insertLegalEntity() {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :tenantId, 'MB-ROT', 'MB Rotation MCHJ', '333333333', 'ACTIVE')
                """).param("id", LEGAL_ENTITY).param("tenantId", TENANT).update();
    }

    private void insertInstallationAndBinding() {
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'click-sandbox-mb-rotation', 'Click', 'ACTIVE',
                    :secretReference)
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", ORIGINAL_SECRET_REFERENCE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'merchant binding rotation endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // The column defaults to the database's own now(), which under
                // heavy concurrent fork load can momentarily disagree with this
                // JVM's clock (ADR pattern established by
                // JdbcAuthorizationServiceTests/GrantManagementServiceTests): a
                // grant read back through JdbcAuthorizationService.grantsFor
                // compares valid_from against Clock.systemUTC(), so backdating on
                // that same clock keeps the comparison honest regardless of any
                // skew against the container's own wall clock.
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
