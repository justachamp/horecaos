package uz.horecaos.platform.reporting.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

/**
 * ADR 0140 / ADR 0043 (report 7.9): the two {@code /reporting/promotions} reads behind
 * their capability, over a real database.
 *
 * <p>The numbers behind the summary are {@code PromotionRedemptionFactTests}' business;
 * this class owns the HTTP edge: that {@code REPORTING_READ} is required, that the
 * tenant in the path is the tenant read, and that a redemption row carries the
 * pseudonym and no account id.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromotionReportControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000e1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000e2");
    private static final UUID BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000e3");
    private static final UUID PROMOTION = UUID.fromString("018f9b20-9000-7000-8000-0000000000e4");
    private static final UUID ORDER = UUID.fromString("018f9b20-9000-7000-8000-0000000000e5");

    private static final String MANAGER = "promo-reporting-manager";
    private static final String DISPATCHER = "promo-reporting-dispatcher";

    private static final String BASE = "/api/v1/tenants/" + TENANT + "/reporting/promotions";

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
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE reporting.fact_promotion_redemption").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "promo-reporting-endpoint");
        insertTenant(OTHER_TENANT, "promo-reporting-endpoint-b");
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
        insertRedemption(TENANT, "pseudonym-of-the-customer", 12_000);
        insertRedemption(OTHER_TENANT, "someone-elses-customer", 777_000);
    }

    @Test
    @DisplayName("the summary and the log both refuse a caller without REPORTING_READ")
    void bothReadsRefuseWithoutReportingRead() throws Exception {
        for (String path : List.of("/summary", "/redemptions")) {
            MvcResult refused = mvc.perform(get(BASE + path)
                            .with(tokenFor(DISPATCHER))
                            .queryParam("from", "2026-09-01")
                            .queryParam("to", "2026-09-07"))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("INSUFFICIENT_CAPABILITY")
                    .contains(Capability.REPORTING_READ.code());
        }
    }

    @Test
    @DisplayName("the log returns the tenant's redemption with a pseudonymous customer and not another tenant's")
    void theLogIsTheCallersTenantAndPseudonymous() throws Exception {
        MvcResult ok = mvc.perform(get(BASE + "/redemptions")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"customerSubject\":\"pseudonym-of-the-customer\"")
                .contains("\"discountSom\":12000")
                .contains("\"promotionCode\":\"LUNCH10\"")
                .doesNotContain("someone-elses-customer")
                .doesNotContain("777000");
    }

    @Test
    @DisplayName("a manager of one tenant cannot read another tenant's promotion log by naming it in the path")
    void aManagerCannotReadAnotherTenantsLog() throws Exception {
        MvcResult refused = mvc.perform(get("/api/v1/tenants/" + OTHER_TENANT + "/reporting/promotions/redemptions")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("777000");
    }

    @Test
    @DisplayName("the summary answers 200 with provenance for a range that has no completed orders")
    void theSummaryAnswersForAnEmptyRange() throws Exception {
        MvcResult ok = mvc.perform(get(BASE + "/summary")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString())
                .contains("\"rows\":[]")
                .contains("provenance");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertRedemption(UUID tenant, String subjectHash, long discountMinor) {
        jdbc.sql("""
                INSERT INTO reporting.fact_promotion_redemption (
                    tenant_id, redemption_id, business_date, boundary_version, metric_calculation_version,
                    brand_id, promotion_id, promotion_code, definition_version, source_kind, coupon_id,
                    order_id, customer_subject_hash, discount_minor, markup_minor, currency, redeemed_at)
                VALUES (:t, :id, DATE '2026-09-03', 1, 1,
                    :brand, :promotion, 'LUNCH10', 2, 'AUTOMATIC', NULL,
                    :order, :hash, :discount, 0, 'UZS', TIMESTAMPTZ '2026-09-03T08:00:00Z')
                """)
                .param("t", tenant)
                .param("id", UUID.nameUUIDFromBytes(("redemption:" + tenant).getBytes(UTF_8)))
                .param("brand", BRAND)
                .param("promotion", PROMOTION)
                .param("order", ORDER)
                .param("hash", subjectHash)
                .param("discount", discountMinor)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Reporting', 'Reporting', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'promotion reporting endpoint test', :validFrom)
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
