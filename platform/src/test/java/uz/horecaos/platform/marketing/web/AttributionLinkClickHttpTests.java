package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0167 Decision 4/5: the public, tokenised, rate-limited click on an attribution link, over HTTP and
 * against a real database.
 *
 * <p>No bearer token anywhere in this class: a {@code ?ref=} landing has no principal, and a test that
 * quietly sent one would prove the endpoint works for staff. What each case reads back is the table, not the
 * response, because the response is deliberately the same for every token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttributionLinkClickHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-c000-7000-8000-0000000c7601");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-c000-7000-8000-0000000c7602");
    private static final UUID BRAND = UUID.fromString("018f9b20-c000-7000-8000-0000000c7603");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-c000-7000-8000-0000000c7604");
    private static final UUID FOREIGN_BRAND = UUID.fromString("018f9b20-c000-7000-8000-0000000c7605");

    private static final String LIVE = "liveToken01";
    private static final String ARCHIVED = "archivedTk1";
    private static final String EXPIRED = "expiredTk01";
    private static final String NOT_YET = "notYetTk001";
    private static final String OTHER_BRANDS = "otherBrandT1";

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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE marketing.attribution_links CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant(TENANT, "click-endpoint");
        insertTenant(OTHER_TENANT, "click-endpoint-b");
        insertBrand(TENANT, BRAND, "MAIN");
        insertBrand(TENANT, OTHER_BRAND, "SECOND");
        insertBrand(OTHER_TENANT, FOREIGN_BRAND, "MAIN");
        insertLink(TENANT, BRAND, LIVE, "ACTIVE", "now() - interval '1 day'", "NULL");
        insertLink(TENANT, BRAND, ARCHIVED, "ARCHIVED", "now() - interval '1 day'", "NULL");
        insertLink(TENANT, BRAND, EXPIRED, "ACTIVE", "now() - interval '10 days'", "now() - interval '1 day'");
        insertLink(TENANT, BRAND, NOT_YET, "ACTIVE", "now() + interval '1 day'", "NULL");
        insertLink(TENANT, OTHER_BRAND, OTHER_BRANDS, "ACTIVE", "now() - interval '1 day'", "NULL");
    }

    @Test
    @DisplayName("a live token counts one click, with no credential at all, and answers 202 with no body")
    void aLiveTokenIsCounted() throws Exception {
        MvcResult result = click(TENANT, BRAND, LIVE, "10.1.0.1");

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(total(LIVE)).isEqualTo(1);
        assertThat(seriesClicks(LIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("every open is one click: the endpoint is not idempotent, and there is no Idempotency-Key to send")
    void eachOpenIsOneClick() throws Exception {
        click(TENANT, BRAND, LIVE, "10.1.0.2");
        click(TENANT, BRAND, LIVE, "10.1.0.2");

        assertThat(total(LIVE)).isEqualTo(2);
        assertThat(seriesClicks(LIVE))
                .as("the day's series agrees with the running total")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an unknown, archived, expired, not-yet-valid, other-brand or other-tenant token all answer 202"
            + " and count nothing, so the endpoint cannot be used to probe which tokens exist")
    void everyTokenThatIsNotLiveAnswersAlikeAndCountsNothing() throws Exception {
        record Probe(UUID tenant, UUID brand, String token) {}
        List<Probe> probes = List.of(
                new Probe(TENANT, BRAND, "doesNotExist1"),
                new Probe(TENANT, BRAND, ARCHIVED),
                new Probe(TENANT, BRAND, EXPIRED),
                new Probe(TENANT, BRAND, NOT_YET),
                new Probe(TENANT, BRAND, OTHER_BRANDS),
                new Probe(OTHER_TENANT, FOREIGN_BRAND, LIVE));
        int index = 0;
        for (Probe probe : probes) {
            MvcResult result = click(probe.tenant(), probe.brand(), probe.token(), "10.2.0." + (index++));

            assertThat(result.getResponse().getStatus()).as(probe.token()).isEqualTo(202);
            assertThat(result.getResponse().getContentAsString())
                    .as(probe.token())
                    .isEmpty();
        }

        assertThat(jdbc.sql("SELECT coalesce(sum(click_count), 0) FROM marketing.attribution_links")
                        .query(Long.class)
                        .single())
                .as("not one of them moved a counter")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM marketing.attribution_link_clicks_day")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a token that is not the shape of a token is ignored before any state is touched")
    void aMalformedTokenIsIgnored() throws Exception {
        for (String token : List.of("abc", "has space!", "x".repeat(65), "tok$en123")) {
            MvcResult result = click(TENANT, BRAND, token, "10.3.0.1");

            assertThat(result.getResponse().getStatus()).as(token).isEqualTo(202);
        }
        assertThat(total(LIVE)).isZero();
    }

    @Test
    @DisplayName("a visitor is limited per token: the fourth open in a minute is refused with 429 and not counted")
    void aVisitorIsRateLimitedPerToken() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(click(TENANT, BRAND, LIVE, "10.4.0.1").getResponse().getStatus())
                    .isEqualTo(202);
        }

        MvcResult limited = click(TENANT, BRAND, LIVE, "10.4.0.1");

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(total(LIVE)).as("the refused open was not counted").isEqualTo(3);
        assertThat(click(TENANT, BRAND, LIVE, "10.4.0.2").getResponse().getStatus())
                .as("another visitor has an allowance of their own")
                .isEqualTo(202);
        assertThat(total(LIVE)).isEqualTo(4);
    }

    @Test
    @DisplayName(
            "the limit applies before the lookup, so a token that does not exist is limited exactly like one that does")
    void theLimitDoesNotRevealWhichTokensExist() throws Exception {
        for (int i = 0; i < 3; i++) {
            click(TENANT, BRAND, "inventedTok01", "10.5.0.1");
        }

        assertThat(click(TENANT, BRAND, "inventedTok01", "10.5.0.1")
                        .getResponse()
                        .getStatus())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("no address, user agent or cookie is stored: the series row is exactly (tenant, link, day, clicks)")
    void nothingAboutTheVisitorIsStored() throws Exception {
        mvc.perform(post(path(TENANT, BRAND, LIVE))
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.77");
                            return request;
                        })
                        .header("User-Agent", "SecretBrowser/9.9 (distinctive)")
                        .header("Cookie", "visitor=cookie-value-1234"))
                .andReturn();

        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM marketing.attribution_link_clicks_day")
                .query()
                .listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().keySet()).containsExactlyInAnyOrder("tenant_id", "link_id", "day", "clicks");
        String everything = jdbc.sql("SELECT row_to_json(l)::text FROM marketing.attribution_links l WHERE token = :t")
                .param("t", LIVE)
                .query(String.class)
                .single();
        assertThat(everything + rows)
                .doesNotContain("203.0.113.77")
                .doesNotContain("SecretBrowser")
                .doesNotContain("cookie-value-1234");
    }

    @Test
    @DisplayName("the staff click endpoint is gone from the contract: a person cannot bump the counter by hand")
    void theStaffClickEndpointIsRemoved() throws Exception {
        UUID linkId = jdbc.sql("SELECT id FROM marketing.attribution_links WHERE token = :t")
                .param("t", LIVE)
                .query(UUID.class)
                .single();

        MvcResult result = mvc.perform(post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND
                                + "/marketing/attribution-links/" + linkId + "/clicks")
                        .with(staffToken())
                        .header("Idempotency-Key", "staff-click-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(404, 405);
        assertThat(total(LIVE)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult click(UUID tenant, UUID brand, String token, String remoteAddress) throws Exception {
        return mvc.perform(post(path(tenant, brand, token)).with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                }))
                .andReturn();
    }

    private static String path(UUID tenant, UUID brand, String token) {
        return "/api/v1/storefront/tenants/" + tenant + "/brands/" + brand + "/attribution-links/" + token + "/clicks";
    }

    private long total(String token) {
        Long total = jdbc.sql(
                        "SELECT click_count FROM marketing.attribution_links WHERE token = :t AND tenant_id = :tenant")
                .param("t", token)
                .param("tenant", TENANT)
                .query(Long.class)
                .single();
        return total == null ? 0 : total;
    }

    private long seriesClicks(String token) {
        Long clicks = jdbc.sql("""
                SELECT coalesce(sum(c.clicks), 0) FROM marketing.attribution_link_clicks_day c
                  JOIN marketing.attribution_links l ON l.id = c.link_id AND l.tenant_id = c.tenant_id
                 WHERE l.token = :t AND l.tenant_id = :tenant
                """)
                .param("t", token)
                .param("tenant", TENANT)
                .query(Long.class)
                .single();
        return clicks == null ? 0 : clicks;
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Click', 'Click', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

    private void insertLink(UUID tenant, UUID brand, String token, String status, String validFrom, String validUntil) {
        jdbc.sql("""
                INSERT INTO marketing.attribution_links
                    (id, tenant_id, brand_id, label, token, channel, destination_type, status, valid_from,
                     valid_until, created_by)
                VALUES (:id, :t, :b, :label, :token, 'WEB', 'INFLUENCER', :status, %s, %s, :by)
                """.formatted(validFrom, validUntil))
                .param("id", UUID.nameUUIDFromBytes(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("t", tenant)
                .param("b", brand)
                .param("label", "Link " + token)
                .param("token", token)
                .param("status", status)
                .param("by", UUID.randomUUID())
                .update();
    }

    private static RequestPostProcessor staffToken() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
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
