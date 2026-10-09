package uz.horecaos.platform.reporting.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * Row 7.6c / ADR 0167: {@code GET .../reporting/customer-funnel} and {@code .../acquisition-sources} over HTTP,
 * behind {@code reporting.read}, against a real database.
 *
 * <p>The counts behind them are {@code CustomerFunnelCaptureTests}' business. This class owns the wire:
 * that the visits stage is "not measured" and carries no number, that a gap is {@code null} and is reported
 * rather than summed as zero, that another tenant's brand answers 404, and that the acquisition read says on
 * the wire that it attributes no customer to a link.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerFunnelControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000f7601");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000f7602");
    private static final UUID BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000f7603");
    private static final UUID OTHER_TENANT_BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000f7604");
    private static final UUID LINK = UUID.fromString("018f9b20-9000-7000-8000-0000000f7605");
    private static final UUID OTHER_TENANT_LINK = UUID.fromString("018f9b20-9000-7000-8000-0000000f7606");

    private static final String MANAGER = "funnel-reporting-manager";
    private static final String DISPATCHER = "funnel-reporting-dispatcher";

    private static final String BASE = "/api/v1/operations/tenants/" + TENANT + "/reporting";

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
        jdbc.sql("TRUNCATE TABLE reporting.fact_funnel_day, reporting.fact_registration_day")
                .update();
        jdbc.sql("TRUNCATE TABLE marketing.attribution_links CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "funnel-endpoint");
        insertTenant(OTHER_TENANT, "funnel-endpoint-b");
        insertBrand(TENANT, BRAND, "MAIN");
        insertBrand(OTHER_TENANT, OTHER_TENANT_BRAND, "MAIN");
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);

        // Two days of this tenant's funnel; the second day's baskets and checkouts were never recorded.
        insertFunnel(TENANT, BRAND, "2026-09-01", "WEB", 10, 6, 4);
        insertFunnel(TENANT, BRAND, "2026-09-01", "TELEGRAM", 5, 2, 1);
        insertFunnel(TENANT, BRAND, "2026-09-02", "WEB", null, null, 3);
        insertRegistration(TENANT, BRAND, "2026-09-01", "SELF_SERVICE", 7);
        insertRegistration(TENANT, BRAND, "2026-09-02", "OPERATOR", 2);
        // Another tenant's numbers, which must never leak into this tenant's answer.
        insertFunnel(OTHER_TENANT, OTHER_TENANT_BRAND, "2026-09-01", "WEB", 777, 777, 777);
        insertRegistration(OTHER_TENANT, OTHER_TENANT_BRAND, "2026-09-01", "SELF_SERVICE", 888);

        insertLink(TENANT, BRAND, LINK, "Instagram: Aziza", "tokAziza01");
        insertClicks(TENANT, LINK, "2026-09-01", 40);
        insertClicks(TENANT, LINK, "2026-09-02", 2);
        insertLink(OTHER_TENANT, OTHER_TENANT_BRAND, OTHER_TENANT_LINK, "Someone else's link", "tokOther01");
        insertClicks(OTHER_TENANT, OTHER_TENANT_LINK, "2026-09-01", 999);
    }

    @Test
    @DisplayName("both reads refuse a caller without REPORTING_READ")
    void bothReadsRefuseWithoutReportingRead() throws Exception {
        for (String path : List.of("/customer-funnel", "/acquisition-sources")) {
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
    @DisplayName("visits come back as 'not measured' with a reason and no number, never as zero")
    void visitsAreNotMeasuredAndCarryNoCount() throws Exception {
        String body = funnel("2026-09-01", "2026-09-07", null, null);

        assertThat(JsonPath.<String>read(body, "$.stages[0].stage")).isEqualTo("VISITS");
        assertThat(JsonPath.<Boolean>read(body, "$.stages[0].measured")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.stages[0].reason")).isEqualTo("NOT_COLLECTED");
        assertThat(JsonPath.<Object>read(body, "$.stages[0].count"))
                .as("a zero here would be believed")
                .isNull();
        assertThat(JsonPath.<List<String>>read(body, "$.stages[*].stage"))
                .containsExactly("VISITS", "REGISTERED", "BASKET_STARTED", "CHECKOUT_STARTED", "ORDERED");
    }

    @Test
    @DisplayName(
            "the stages are this tenant's daily counts; a day 'not recorded' is reported as a gap, not summed as zero")
    void stagesSumWhatWasRecordedAndSayHowManyDaysAreMissing() throws Exception {
        String body = funnel("2026-09-01", "2026-09-07", null, null);

        assertThat(count(body, "REGISTERED"))
                .as("7 + 2, and not the other tenant's 888")
                .isEqualTo(9);
        assertThat(count(body, "BASKET_STARTED"))
                .as("10 + 5 recorded on day one; day two is not recorded")
                .isEqualTo(15);
        assertThat(unrecorded(body, "BASKET_STARTED")).isEqualTo(1);
        assertThat(count(body, "CHECKOUT_STARTED")).isEqualTo(8);
        assertThat(unrecorded(body, "CHECKOUT_STARTED")).isEqualTo(1);
        assertThat(count(body, "ORDERED")).isEqualTo(8);
        assertThat(unrecorded(body, "ORDERED")).isZero();

        List<Map<String, Object>> days = JsonPath.read(body, "$.days");
        assertThat(days).hasSize(3);
        Map<String, Object> unrecordedDay = days.stream()
                .filter(day -> "2026-09-02".equals(day.get("businessDate")))
                .findFirst()
                .orElseThrow();
        assertThat(unrecordedDay).containsEntry("basketStarted", null).containsEntry("ordered", 3);
        assertThat(body).doesNotContain("777").doesNotContain("888");
    }

    @Test
    @DisplayName(
            "a channel filter cuts the cart stages and leaves registrations out, because an account has no channel")
    void aChannelFilterDoesNotPretendToCutRegistrations() throws Exception {
        String body = funnel("2026-09-01", "2026-09-07", null, "TELEGRAM");

        assertThat(count(body, "BASKET_STARTED")).isEqualTo(5);
        assertThat(JsonPath.<Boolean>read(body, "$.stages[1].measured")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.stages[1].reason")).isEqualTo("NOT_BY_CHANNEL");
    }

    @Test
    @DisplayName("another tenant's brand, and a brand that does not exist, both answer 404")
    void aForeignBrandIsNotFound() throws Exception {
        for (UUID brand : List.of(OTHER_TENANT_BRAND, UUID.randomUUID())) {
            for (String path : List.of("/customer-funnel", "/acquisition-sources")) {
                MvcResult result = mvc.perform(get(BASE + path)
                                .with(tokenFor(MANAGER))
                                .queryParam("from", "2026-09-01")
                                .queryParam("to", "2026-09-07")
                                .queryParam("brandId", brand.toString()))
                        .andReturn();

                assertThat(result.getResponse().getStatus())
                        .as(path + " " + brand)
                        .isEqualTo(404);
                assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
            }
        }
    }

    @Test
    @DisplayName("a manager of one tenant cannot read another tenant's funnel by naming it in the path")
    void aManagerCannotReadAnotherTenantsFunnel() throws Exception {
        MvcResult refused = mvc.perform(get("/api/v1/operations/tenants/" + OTHER_TENANT + "/reporting/customer-funnel")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("777");
    }

    @Test
    @DisplayName("acquisition: registrations by origin, clicks by link label and orders by channel sit side by side,"
            + " and the response says it attributes no customer to a link")
    void acquisitionShowsThreeCountsAndAttributesNobody() throws Exception {
        MvcResult ok = mvc.perform(get(BASE + "/acquisition-sources")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(body, "$.attributesCustomerToLink")).isFalse();
        assertThat(JsonPath.<List<String>>read(body, "$.registrationsByOrigin[*].origin"))
                .containsExactlyInAnyOrder("SELF_SERVICE", "OPERATOR");
        assertThat(JsonPath.<List<String>>read(body, "$.clicksByLink[*].label")).containsExactly("Instagram: Aziza");
        assertThat(JsonPath.<Integer>read(body, "$.clicksByLink[0].clicks"))
                .as("40 + 2 over the range, and not the other tenant's 999")
                .isEqualTo(42);
        assertThat(JsonPath.<List<String>>read(body, "$.ordersByChannel[*].channelCode"))
                .containsExactlyInAnyOrder("WEB", "TELEGRAM");
        assertThat(body)
                .as("the response never carries a link's token or another tenant's link")
                .doesNotContain("tokAziza01")
                .doesNotContain("Someone else's link")
                .doesNotContain("999");
    }

    @Test
    @DisplayName("a range past the day limit is refused as a validation failure, not answered")
    void aRangeTooWideIsRefused() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/customer-funnel")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2020-01-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("/queries refuses the funnel metric and names the endpoint that answers it")
    void queriesPointsAtTheFunnelEndpoint() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/reporting/queries")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07")
                        .queryParam("metric", "funnel.stage.v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("NON_SCALAR_METRIC")
                .contains("customer-funnel");
    }

    // ------------------------------------------------------------------ helpers

    private String funnel(String from, String to, @Nullable UUID brandId, @Nullable String channel) throws Exception {
        var request = get(BASE + "/customer-funnel")
                .with(tokenFor(MANAGER))
                .queryParam("from", from)
                .queryParam("to", to);
        if (brandId != null) {
            request.queryParam("brandId", brandId.toString());
        }
        if (channel != null) {
            request.queryParam("channel", channel);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private static int count(String body, String stage) {
        List<Integer> counts = JsonPath.read(body, "$.stages[?(@.stage=='" + stage + "')].count");
        return counts.getFirst();
    }

    private static int unrecorded(String body, String stage) {
        List<Integer> days = JsonPath.read(body, "$.stages[?(@.stage=='" + stage + "')].unrecordedDays");
        return days.getFirst();
    }

    private void insertFunnel(
            UUID tenant,
            UUID brand,
            String date,
            String channel,
            @Nullable Integer baskets,
            @Nullable Integer checkouts,
            @Nullable Integer ordered) {
        jdbc.sql("""
                INSERT INTO reporting.fact_funnel_day
                    (tenant_id, brand_id, business_date, channel_code, basket_started, checkout_started, ordered,
                     boundary_version, computed_at)
                VALUES (:t, :b, CAST(:d AS date), :c, :bs, :cs, :o, 1, now())
                """)
                .param("t", tenant)
                .param("b", brand)
                .param("d", date)
                .param("c", channel)
                .param("bs", baskets)
                .param("cs", checkouts)
                .param("o", ordered)
                .update();
    }

    private void insertFunnel(
            UUID tenant, UUID brand, String date, String channel, int baskets, int checkouts, int ordered) {
        insertFunnel(
                tenant,
                brand,
                date,
                channel,
                Integer.valueOf(baskets),
                Integer.valueOf(checkouts),
                Integer.valueOf(ordered));
    }

    private void insertRegistration(UUID tenant, UUID brand, String date, String origin, int registered) {
        jdbc.sql("""
                INSERT INTO reporting.fact_registration_day
                    (tenant_id, brand_id, business_date, origin, registered, boundary_version, computed_at)
                VALUES (:t, :b, CAST(:d AS date), :o, :r, 1, now())
                """)
                .param("t", tenant)
                .param("b", brand)
                .param("d", date)
                .param("o", origin)
                .param("r", registered)
                .update();
    }

    private void insertLink(UUID tenant, UUID brand, UUID id, String label, String token) {
        jdbc.sql("""
                INSERT INTO marketing.attribution_links
                    (id, tenant_id, brand_id, label, token, channel, destination_type, status, valid_from,
                     created_by)
                VALUES (:id, :t, :b, :label, :token, 'WEB', 'INFLUENCER', 'ACTIVE', now() - interval '30 days', :by)
                """)
                .param("id", id)
                .param("t", tenant)
                .param("b", brand)
                .param("label", label)
                .param("token", token)
                .param("by", UUID.randomUUID())
                .update();
    }

    private void insertClicks(UUID tenant, UUID link, String day, int clicks) {
        jdbc.sql("""
                INSERT INTO marketing.attribution_link_clicks_day (tenant_id, link_id, day, clicks)
                VALUES (:t, :l, CAST(:d AS date), :c)
                """)
                .param("t", tenant)
                .param("l", link)
                .param("d", day)
                .param("c", clicks)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Reporting', 'Reporting', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenant, UUID brand, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("t", tenant)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'customer funnel endpoint test', :validFrom)
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
