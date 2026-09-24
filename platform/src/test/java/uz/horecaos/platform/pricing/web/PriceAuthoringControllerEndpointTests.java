package uz.horecaos.platform.pricing.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Gap map row {@code 4.8a}: before this wave {@code PriceAuthoringController}
 * could only ever {@code PUT} a tax profile — there was no HTTP read at all,
 * so an operator overwrote a jurisdiction's VAT rate without ever seeing what
 * was already in force. Proves the new {@code GET /tax-profiles} (list) and
 * {@code GET /tax-profiles/{jurisdictionCode}} (single) both hold
 * {@code PRICING_READ} through the real capability interceptor, and that a
 * profile set through the existing {@code PUT} reads back correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceAuthoringControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000c2");

    private static final String OWNER = "tax-profile-owner";
    private static final String DISPATCHER = "tax-profile-dispatcher";

    private static final String PRICING = "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/pricing";

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
        jdbc.sql("TRUNCATE TABLE pricing.tax_profiles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenantAndBrand();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, "BRAND", BRAND);
    }

    @Test
    void listingWithoutPricingReadIsRefused() throws Exception {
        MvcResult refused = mvc.perform(get(PRICING + "/tax-profiles").with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.PRICING_READ.code());
    }

    @Test
    void readingOneJurisdictionWithoutPricingReadIsRefused() throws Exception {
        MvcResult refused = mvc.perform(get(PRICING + "/tax-profiles/UZ").with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anUnknownJurisdictionIsNotFound() throws Exception {
        MvcResult missing = mvc.perform(get(PRICING + "/tax-profiles/ZZ").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void listIsEmptyUntilAProfileIsSet() throws Exception {
        MvcResult empty = mvc.perform(get(PRICING + "/tax-profiles").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(empty.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void aProfileSetByPutReadsBackFromBothGets() throws Exception {
        MvcResult put = mvc.perform(put(PRICING + "/tax-profiles/UZ")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "set-uz-vat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"INCLUSIVE","rateBasisPoints":1200}
                                """))
                .andReturn();
        assertThat(put.getResponse().getStatus()).isEqualTo(200);

        MvcResult single = mvc.perform(get(PRICING + "/tax-profiles/UZ").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(single.getResponse().getStatus()).isEqualTo(200);
        assertThat(single.getResponse().getContentAsString())
                .contains("\"jurisdictionCode\":\"UZ\"")
                .contains("\"mode\":\"INCLUSIVE\"")
                .contains("\"rateBasisPoints\":1200");

        MvcResult list = mvc.perform(get(PRICING + "/tax-profiles").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains("\"jurisdictionCode\":\"UZ\"");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tax-profile-endpoint', 'Tax Profile', 'Tax Profile',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'tax profile endpoint test', :validFrom)
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
