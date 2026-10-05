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
 * ADR 0136 / ADR 0043: {@code /reporting/combo-sales} behind its capability, over a real database.
 *
 * <p>The counting is {@code ComboSalesReportingTests}' business; this class owns the HTTP edge: that
 * {@code REPORTING_READ} is required, that the tenant in the path is the tenant read, and that the
 * JSON a console reads carries what the page needs (combos sold, purchases, money, the split).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComboSalesReportControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000f1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000f2");
    private static final UUID BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000f3");
    private static final UUID LOCATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f4");
    private static final UUID LUNCH = UUID.fromString("018f9b20-9000-7000-8000-0000000000f5");
    private static final UUID BURGER = UUID.fromString("018f9b20-9000-7000-8000-0000000000f6");

    private static final String MANAGER = "combo-reporting-manager";
    private static final String DISPATCHER = "combo-reporting-dispatcher";

    private static final String PATH = "/api/v1/tenants/" + TENANT + "/reporting/combo-sales";

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
        jdbc.sql("TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "combo-reporting-endpoint");
        insertTenant(OTHER_TENANT, "combo-reporting-endpoint-b");
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
        insertSoldCombo(TENANT, "mine", 2, 56_000L);
        insertSoldCombo(OTHER_TENANT, "theirs", 9, 777_000L);
    }

    @Test
    @DisplayName("the report refuses a caller without REPORTING_READ")
    void theReportRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(PATH)
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-08-21")
                        .queryParam("to", "2026-08-21"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    @DisplayName("the report returns the tenant's combos, counted per purchase, and not another tenant's")
    void theReportIsTheCallersTenant() throws Exception {
        MvcResult ok = mvc.perform(get(PATH)
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-08-21")
                        .queryParam("to", "2026-08-21"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"comboContainerVariantId\":\"" + LUNCH + "\"")
                .contains("\"comboName\":\"Lunch box\"")
                .contains("\"combosSold\":2")
                .contains("\"purchases\":1")
                .contains("\"orders\":1")
                .contains("\"totalNetSom\":56000")
                .contains("\"deliveryCombos\":2")
                .contains("\"maybeMore\":false")
                .contains("provenance")
                .doesNotContain("777000");
    }

    @Test
    @DisplayName("a manager of one tenant cannot read another tenant's combo sales by naming it in the path")
    void aManagerCannotReadAnotherTenantsCombos() throws Exception {
        MvcResult refused = mvc.perform(get("/api/v1/tenants/" + OTHER_TENANT + "/reporting/combo-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-08-21")
                        .queryParam("to", "2026-08-21"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("777000");
    }

    @Test
    @DisplayName("a non-positive limit is refused, a range with no combo sold answers an empty list")
    void theLimitIsValidatedAndAnEmptyRangeIsAnEmptyList() throws Exception {
        MvcResult badLimit = mvc.perform(get(PATH)
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-08-21")
                        .queryParam("to", "2026-08-21")
                        .queryParam("limit", "0"))
                .andReturn();
        MvcResult empty = mvc.perform(get(PATH)
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-08-01")
                        .queryParam("to", "2026-08-02"))
                .andReturn();

        assertThat(badLimit.getResponse().getStatus()).isEqualTo(400);
        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(empty.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertSoldCombo(UUID tenant, String seed, int combos, long netSom) {
        UUID order = UUID.nameUUIDFromBytes(("combo-order:" + seed).getBytes(UTF_8));
        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (:t, :o, DATE '2026-08-21', 1, TIMESTAMPTZ '2026-08-21T04:00:00Z',
                    :brand, :location, 'TELEGRAM', 'DELIVERY', 'COMPLETED',
                    0, 0, 0, 0, 0, 1, 1, 1, 1)
                """)
                .param("t", tenant)
                .param("o", order)
                .param("brand", BRAND)
                .param("location", LOCATION)
                .update();
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at,
                    combo_selection_id, combo_container_variant_id, combo_quantity, combo_name_snapshot)
                VALUES (:t, DATE '2026-08-21', :o, :line, :location, :variant,
                    'Burger', :combos, :net, 0, :net, TIMESTAMPTZ '2026-08-21T04:00:00Z',
                    :selection, :container, :combos, 'Lunch box')
                """)
                .param("t", tenant)
                .param("o", order)
                .param("line", UUID.nameUUIDFromBytes(("combo-line:" + seed).getBytes(UTF_8)))
                .param("location", LOCATION)
                .param("variant", BURGER)
                .param("combos", combos)
                .param("net", netSom)
                .param("selection", UUID.nameUUIDFromBytes(("combo-selection:" + seed).getBytes(UTF_8)))
                .param("container", LUNCH)
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
                        'ACTIVE', 'test-fixture', 'combo reporting endpoint test', :validFrom)
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
