package uz.horecaos.platform.customers.web;

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
 * Wave P26, second-pass adversarial review: {@code CustomerImportController}
 * was new this integration with no HTTP-level test — {@code
 * CustomerCsvImportTests} exercises the parser and service directly, never
 * through {@code @RequiresCapability}'s real interceptor stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerImportControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-6000-7000-8000-0000000000b1");

    private static final String OWNER = "customer-import-owner";
    private static final String READ_ONLY = "customer-import-read-only";

    private static final String IMPORTS = "/api/v1/tenants/" + TENANT + "/customers/imports";

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
        // CustomerImportService.submit envelope-encrypts the CSV content (ADR 0029).
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenantAndBrand();
        // TENANT_OWNER carries both CUSTOMER_IMPORT and CUSTOMER_READ.
        grant(OWNER, PlatformRole.TENANT_OWNER);
        // LOCATION_STAFF carries CUSTOMER_READ but never CUSTOMER_IMPORT --
        // a role with real, adjacent authority still refused.
        grant(READ_ONLY, PlatformRole.LOCATION_STAFF);
    }

    @Test
    void readOnlyCannotSubmitAnImport() throws Exception {
        MvcResult refused = mvc.perform(post(IMPORTS)
                        .with(tokenFor(READ_ONLY))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "submit-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CUSTOMER_IMPORT.code());
        assertThat(runCount()).isZero();
    }

    @Test
    void anOwnerCanSubmitAndThenPollStatusAndRows() throws Exception {
        MvcResult submitted = mvc.perform(post(IMPORTS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "submit-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody()))
                .andReturn();

        assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
        assertThat(submitted.getResponse().getContentAsString()).contains("\"rowsTotal\":1");
        UUID runId = runId();

        MvcResult status =
                mvc.perform(get(IMPORTS + "/" + runId).with(tokenFor(OWNER))).andReturn();
        assertThat(status.getResponse().getStatus()).isEqualTo(200);

        MvcResult rows = mvc.perform(get(IMPORTS + "/" + runId + "/rows").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(rows.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void readOnlyCanStillPollAnExistingRun() throws Exception {
        mvc.perform(post(IMPORTS)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "submit-for-poll")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody()));
        UUID runId = runId();

        MvcResult status = mvc.perform(get(IMPORTS + "/" + runId).with(tokenFor(READ_ONLY)))
                .andReturn();

        assertThat(status.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ fixtures

    private static String submitBody() {
        return """
                {"brandId":"%s","fileName":"customers.csv","content":"phone\\n+998901110099\\n"}
                """.formatted(BRAND);
    }

    private UUID runId() {
        return jdbc.sql("SELECT id FROM customer.customer_import_runs WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
    }

    private long runCount() {
        return jdbc.sql("SELECT count(*) FROM customer.customer_import_runs WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'customer-import-endpoint', 'Customer Import', 'Customer Import',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'customer import endpoint test', :validFrom)
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
