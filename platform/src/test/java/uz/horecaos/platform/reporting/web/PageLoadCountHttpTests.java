package uz.horecaos.platform.reporting.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.context.annotation.Primary;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.marketing.api.AttributionClickSeries;
import uz.horecaos.platform.reporting.api.ReportingConfigurationKeys;
import uz.horecaos.platform.reporting.application.FunnelRetentionSweeper;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;

/**
 * The dark page-load count (ADR 0183 D18, gap-map row {@code 7.6c}) over HTTP, against a real database.
 *
 * <p>The properties that matter, in the order a counsel would ask about them: while the flag is off nothing is counted and
 * the funnel still says «not measured»; once it is on, a tab session counts one load into a per-day, per-brand, per-channel
 * row and the funnel's visits stage says its figure is a count of page loads, with the days before the counter started
 * reported as unrecorded and not as zero; a caller cannot grow the table with a brand or a channel that is not the tenant's;
 * a loop is limited; and the count ages out with the rest of the funnel.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageLoadCountHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9100-7000-8000-0000000f8801");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-9100-7000-8000-0000000f8802");
    private static final UUID BRAND = UUID.fromString("018f9b20-9100-7000-8000-0000000f8803");
    private static final UUID OTHER_TENANT_BRAND = UUID.fromString("018f9b20-9100-7000-8000-0000000f8804");

    private static final String MANAGER = "page-load-reporting-manager";
    private static final String BASE = "/api/v1/operations/tenants/" + TENANT + "/reporting";
    private static final String PING = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/page-loads";

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

    @Autowired
    private ConfigurationValueAuthor configurationValues;

    @Autowired
    private JdbcFunnelStore funnelStore;

    @Autowired
    private AttributionClickSeries clickSeries;

    @Autowired
    private ConfigurationResolver configuration;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private org.springframework.cache.CacheManager caches;

    @Autowired
    private MovableClock clock;

    @BeforeEach
    void reset() {
        // The limiter is a token bucket that refills with time, so a loop of pings that takes a minute on a loaded
        // machine is
        // allowed more than its budget. The context's clock is held still for the test, as the content tests hold
        // theirs.
        clock.set(Instant.now());
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE reporting.page_load_counts_day").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "page-load-endpoint");
        insertTenant(OTHER_TENANT, "page-load-endpoint-b");
        insertBrand(TENANT, BRAND);
        insertBrand(OTHER_TENANT, OTHER_TENANT_BRAND);
        insertChannel(TENANT, "WEB");
        insertChannel(OTHER_TENANT, "ONLY_THEIRS");
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        caches.getCacheNames().forEach(name -> {
            var cache = caches.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    // ------------------------------------------------------------------ while dark

    @Test
    @DisplayName("the platform flag is off by default: nothing is counted, and the funnel still says «not measured»")
    void darkByDefault() throws Exception {
        assertThat(JsonPath.<Boolean>read(readFlag(), "$.enabled")).isFalse();

        MvcResult ping = ping(BRAND, "WEB");
        assertThat(ping.getResponse().getStatus())
                .as("the same answer, counted or not")
                .isEqualTo(202);

        assertThat(rows()).isZero();
        String body = funnel(LocalDate.now().minusDays(6), LocalDate.now());
        assertThat(JsonPath.<Boolean>read(body, "$.stages[0].measured")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.stages[0].reason")).isEqualTo("NOT_COLLECTED");
        assertThat(JsonPath.<Object>read(body, "$.stages[0].basis")).isNull();
        assertThat(JsonPath.<Object>read(body, "$.stages[0].count")).isNull();
    }

    // ------------------------------------------------------------------ once it is on

    @Test
    @DisplayName(
            "on, a ping adds one to today's row for the brand and channel, and nothing about the visitor is stored")
    void onItCountsOneLoadPerPing() throws Exception {
        switchOn();
        assertThat(JsonPath.<Boolean>read(readFlag(), "$.enabled")).isTrue();

        for (int i = 0; i < 3; i++) {
            assertThat(ping(BRAND, "WEB").getResponse().getStatus()).isEqualTo(202);
        }

        List<Map<String, Object>> stored = jdbc.sql("""
                        SELECT tenant_id, brand_id, business_date, channel_code, page_loads
                          FROM reporting.page_load_counts_day
                        """)
                .query((row, n) -> Map.<String, Object>of(
                        "tenant", row.getObject("tenant_id", UUID.class),
                        "brand", row.getObject("brand_id", UUID.class),
                        "date", row.getObject("business_date", LocalDate.class),
                        "channel", row.getString("channel_code"),
                        "loads", row.getLong("page_loads")))
                .list();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst())
                .containsEntry("tenant", TENANT)
                .containsEntry("brand", BRAND)
                .containsEntry("channel", "WEB")
                .containsEntry("loads", 3L)
                .containsEntry("date", today());
    }

    @Test
    @DisplayName(
            "the visits stage is a count of page loads, says so, and reports the days before the counter started as unrecorded")
    void theFunnelLabelsTheFigureAndDoesNotInventZeroes() throws Exception {
        switchOn();
        ping(BRAND, "WEB");
        ping(BRAND, "WEB");

        String body = funnel(today().minusDays(6), today());

        assertThat(JsonPath.<String>read(body, "$.stages[0].stage")).isEqualTo("VISITS");
        assertThat(JsonPath.<Boolean>read(body, "$.stages[0].measured")).isTrue();
        assertThat(JsonPath.<Integer>read(body, "$.stages[0].count")).isEqualTo(2);
        assertThat(JsonPath.<String>read(body, "$.stages[0].basis")).isEqualTo("PAGE_LOADS");
        assertThat(JsonPath.<Integer>read(body, "$.stages[0].unrecordedDays"))
                .as("the counter wrote its first row today, so the six days before it were not collecting")
                .isEqualTo(6);
        assertThat(JsonPath.<List<String>>read(body, "$.stages[*].stage"))
                .containsExactly("VISITS", "REGISTERED", "BASKET_STARTED", "CHECKOUT_STARTED", "ORDERED");
    }

    @Test
    @DisplayName("a range with no row at all reports every day as unrecorded rather than zero visits on each")
    void anEmptyCounterIsAllGap() throws Exception {
        switchOn();

        String body = funnel(today().minusDays(2), today());

        assertThat(JsonPath.<Integer>read(body, "$.stages[0].count")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.stages[0].unrecordedDays")).isEqualTo(3);
    }

    @Test
    @DisplayName("a channel filter cuts the page loads to that channel")
    void aChannelFilterCutsTheCount() throws Exception {
        switchOn();
        insertChannel(TENANT, "TELEGRAM");
        ping(BRAND, "WEB");
        ping(BRAND, "WEB");
        ping(BRAND, "TELEGRAM");

        MvcResult result = mvc.perform(get(BASE + "/customer-funnel")
                        .with(token(MANAGER))
                        .queryParam("from", today().toString())
                        .queryParam("to", today().toString())
                        .queryParam("channel", "TELEGRAM"))
                .andReturn();

        assertThat(JsonPath.<Integer>read(result.getResponse().getContentAsString(), "$.stages[0].count"))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ what it will not count

    @Test
    @DisplayName(
            "a brand that is not the tenant's, and a channel the tenant never defined, are answered 202 and counted nowhere")
    void foreignBrandsAndChannelsAreDropped() throws Exception {
        switchOn();

        assertThat(ping(OTHER_TENANT_BRAND, "WEB").getResponse().getStatus()).isEqualTo(202);
        assertThat(ping(BRAND, "ONLY_THEIRS").getResponse().getStatus()).isEqualTo(202);
        assertThat(ping(BRAND, "invented-" + UUID.randomUUID().toString().substring(0, 8))
                        .getResponse()
                        .getStatus())
                .isEqualTo(202);
        assertThat(mvc.perform(post(PING)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("no channel at all")
                .isEqualTo(202);

        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("a visitor is limited, and the count never exceeds the allowance")
    void aLoopIsLimited() throws Exception {
        switchOn();

        int refused = 0;
        for (int i = 0; i < 35; i++) {
            if (ping(BRAND, "WEB").getResponse().getStatus() == 429) {
                refused++;
            }
        }

        assertThat(refused).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(funnel(today(), today()), "$.stages[0].count"))
                .as("thirty a minute per visitor and no more")
                .isEqualTo(30);
    }

    // ------------------------------------------------------------------ retention

    @Test
    @DisplayName(
            "the count ages out with the rest of the funnel: report-only counts it, enforcing deletes only what is old")
    void retentionCoversThePageLoadTable() {
        insertCount(BRAND, today().minusDays(500), "WEB", 12);
        insertCount(BRAND, today().minusDays(3), "WEB", 4);
        FunnelRetentionSweeper reporting = sweeper(FunnelRetentionSweeper.Mode.REPORT_ONLY);
        FunnelRetentionSweeper enforcing = sweeper(FunnelRetentionSweeper.Mode.ENFORCE);

        assertThat(reporting.runOnce()).as("one old row would go").isEqualTo(1);
        assertThat(rows()).as("report-only writes nothing").isEqualTo(2);

        assertThat(enforcing.runOnce()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT page_loads FROM reporting.page_load_counts_day")
                        .query(Long.class)
                        .single())
                .isEqualTo(4L);
    }

    // ------------------------------------------------------------------ helpers

    private FunnelRetentionSweeper sweeper(FunnelRetentionSweeper.Mode mode) {
        return new FunnelRetentionSweeper(funnelStore, clickSeries, configuration, Clock.systemUTC(), meters, mode);
    }

    /** The tenant's own calendar day, which is the business day while no boundary is set. */
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Tashkent"));
    }

    private void switchOn() {
        configurationValues.set(
                ReportingConfigurationKeys.PAGE_LOAD_COUNT_ENABLED,
                ResourceScope.platform(),
                true,
                false,
                null,
                ActorRef.user("page-load-test", null),
                "counsel answered (a test)");
    }

    private MvcResult ping(UUID brand, String channel) throws Exception {
        return mvc.perform(post("/api/v1/storefront/tenants/" + TENANT + "/brands/" + brand + "/page-loads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + channel + "\"}"))
                .andReturn();
    }

    /** The public flag read, answered as JSON text. */
    private String readFlag() throws Exception {
        return mvc.perform(get(PING)).andReturn().getResponse().getContentAsString();
    }

    private String funnel(LocalDate from, LocalDate to) throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/customer-funnel")
                        .with(token(MANAGER))
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private long rows() {
        Long count = jdbc.sql("SELECT count(*) FROM reporting.page_load_counts_day")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private void insertCount(UUID brand, LocalDate date, String channel, long loads) {
        jdbc.sql("""
                INSERT INTO reporting.page_load_counts_day (tenant_id, brand_id, business_date, channel_code, page_loads)
                VALUES (:t, :b, :d, :c, :n)
                """)
                .param("t", TENANT)
                .param("b", brand)
                .param("d", date)
                .param("c", channel)
                .param("n", loads)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenant, UUID id) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("t", tenant)
                .param("slug", "brand-" + id.toString().substring(30))
                .update();
    }

    private void insertChannel(UUID tenant, String code) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :t, :code, 'WEB', 'Channel', 'ACTIVE', 1)
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenant)
                .param("code", code)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :t, :subject, :roleId, true, 'TENANT', :t,
                        'ACTIVE', 'test-fixture', 'page load endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("t", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.<String>of()))));
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

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    /** The clock the whole context runs on; it stands still until a test moves it. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
