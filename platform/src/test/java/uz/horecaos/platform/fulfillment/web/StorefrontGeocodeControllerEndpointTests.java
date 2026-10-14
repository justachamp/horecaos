package uz.horecaos.platform.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.application.GeocodingService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.camel.geo.fake.FakeGeocoderAdapter;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The storefront's address lookups, through the real stack (ADR 0145 decisions 2 and 4; row
 * {@code X.4}): the customer's own session in, the platform's geocoder behind, no vendor call from
 * the browser.
 *
 * <p>The geocoder is the controlled fake ({@code horecaos.geo.provider=fake}), so everything under
 * test is the platform's: ownership, the region the customer cannot choose, the region box applied
 * on the way back, the limits (one per customer, one for the tenant), the thirty-day cache and the
 * meter that says which surface spent the quota.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontGeocodeControllerEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.fromString("018fa200-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa200-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fa200-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fa200-4000-7000-8000-0000000000b2");

    private static final UUID TASHKENT = UUID.fromString("018fa200-4000-7000-8000-0000000000e1");
    private static final UUID OTHER_REGION = UUID.fromString("018fa200-4000-7000-8000-0000000000e3");

    // Minted per test, so one test's lookups never spend another's rate limit.
    private final String customer = "sf-geo-customer-" + UUID.randomUUID();
    private final String secondCustomer = "sf-geo-second-" + UUID.randomUUID();
    private final String guest = "sf-geo-guest-" + UUID.randomUUID();
    private final String otherTenantCustomer = "sf-geo-other-" + UUID.randomUUID();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront geocode endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("horecaos.geo.provider", () -> "fake");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    private CacheManager caches;

    @Autowired
    @SuppressWarnings("NullAway")
    private RecordingRateLimiter limiter;

    @Autowired
    @SuppressWarnings("NullAway")
    private GeocodingService geocoding;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.regions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.usage_aggregates").update();
        roleRegistry.synchronize();
        var cache = caches.getCache("geo.responses");
        if (cache != null) {
            cache.clear();
        }
        limiter.reset();

        tenant(TENANT, "sf-geo");
        brand(TENANT, BRAND);
        tenant(OTHER_TENANT, "sf-geo-other");
        brand(OTHER_TENANT, OTHER_BRAND);
        insertRegion(TENANT, TASHKENT, "TASHKENT", 41.15, 69.04, 41.47, 69.46);
        // A region of the other tenant, deliberately somewhere else: if a customer could name it, the
        // Bishkek street the fake returns would stop being flagged.
        insertRegion(OTHER_TENANT, OTHER_REGION, "BISHKEK", 42.7, 74.4, 43.0, 74.8);

        account(TENANT, customer);
        account(TENANT, secondCustomer);
        account(OTHER_TENANT, otherTenantCustomer);
    }

    // ------------------------------------------------------------------ map-config

    @Test
    @DisplayName("a signed-in customer reads what the map is, and the answer holds no server key")
    void theMapConfigNamesTheProviderAndNoSecret() throws Exception {
        MvcResult read =
                mvc.perform(get(base() + "/map-config").with(token(customer))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(read.getResponse().getContentAsString());
        assertThat(body.get("provider").asString()).isEqualTo("FAKE");
        assertThat(body.get("configured").asBoolean()).isTrue();
        assertThat(body.get("features").toString())
                .as("the fake has no tiles, so the screen is told there is no map rather than shown an empty one")
                .isEqualTo("[\"SUGGEST\",\"GEOCODE\",\"REVERSE\"]");
        assertThat(read.getResponse().getContentAsString())
                .doesNotContain("secret")
                .doesNotContain("horecaos:");
        assertThat(read.getResponse().getHeader("Cache-Control"))
                .as("per customer, so never a shared cache")
                .contains("private");
    }

    // ----------------------------------------------------------------- the lookups

    @Test
    @DisplayName("suggestions answer the storefront's own minimal JSON, with no region and no locale")
    void suggestionsFromTheStorefrontsMinimalJson() throws Exception {
        MvcResult suggested = lookup(base() + "/geocode/suggestions", customer, "{\"text\":\"amir temur\"}");

        assertThat(suggested.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(suggested.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(body.get("suggestions").size()).isGreaterThan(0);
        assertThat(body.get("suggestions").get(0).get("fullText").asString()).isNotBlank();
    }

    @Test
    @DisplayName(
            "a result outside the tenant's region box is LOW_CONFIDENCE, and a customer cannot choose another region to avoid that")
    void theRegionBoxIsAppliedAndTheCustomerCannotNameARegion() throws Exception {
        // A body naming the other tenant's region is a body naming a field this endpoint does not
        // have; it must change nothing.
        MvcResult resolved = lookup(
                base() + "/geocode/resolutions",
                customer,
                "{\"text\":\"amir temur ko'chasi\",\"regionId\":\"" + OTHER_REGION + "\"}");

        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        JsonNode results =
                JSON.readTree(resolved.getResponse().getContentAsString()).get("results");
        boolean sawForeign = false;
        boolean sawHighInRegion = false;
        for (JsonNode result : results) {
            double latitude = result.get("latitude").asDouble();
            double longitude = result.get("longitude").asDouble();
            boolean inBox = latitude >= 41.15 && latitude <= 41.47 && longitude >= 69.04 && longitude <= 69.46;
            if (!inBox) {
                sawForeign = true;
                assertThat(result.get("confidence").asString())
                        .as("the fake answers the Bishkek street as HIGH; the platform flags it against Tashkent's box")
                        .isEqualTo("LOW_CONFIDENCE");
            } else if (result.get("confidence").asString().equals("HIGH")) {
                sawHighInRegion = true;
            }
        }
        assertThat(sawForeign)
                .as("the scenario must contain the foreign street")
                .isTrue();
        assertThat(sawHighInRegion).isTrue();
    }

    @Test
    @DisplayName("a reverse lookup names what is at a pin, and says nothing found as an answer")
    void aReverseLookupNamesWhatIsAtAPoint() throws Exception {
        MvcResult reversed = lookup(
                base() + "/geocode/reverse-resolutions",
                customer,
                "{\"point\":{\"latitude\":41.3078,\"longitude\":69.2702}}");

        assertThat(reversed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(reversed.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(body.get("result").get("components").get("street").asString())
                .isEqualTo("Navoi ko'chasi");

        MvcResult nothing = lookup(
                base() + "/geocode/reverse-resolutions",
                customer,
                "{\"point\":{\"latitude\":41.2,\"longitude\":69.1}}");
        JsonNode empty = JSON.readTree(nothing.getResponse().getContentAsString());
        assertThat(empty.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(empty.get("result").isNull()).isTrue();
    }

    @Test
    @DisplayName("an outage is an answer the address screen degrades on, not an error")
    void anOutageIsAnAnswerNotAnError() throws Exception {
        MvcResult down = lookup(base() + "/geocode/resolutions", customer, "{\"text\":\"fake-scenario-unavailable\"}");

        assertThat(down.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(down.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("reason").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(body.get("results").size()).isZero();
    }

    @Test
    @DisplayName("a tenant with no active region answers a refusal that says so, not a lookup in nowhere")
    void aTenantWithNoRegionIsToldSo() throws Exception {
        jdbc.sql("UPDATE fulfillment.regions SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", TASHKENT)
                .update();

        MvcResult refused = lookup(base() + "/geocode/suggestions", customer, "{\"text\":\"navoi\"}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("REGION_REQUIRED");
    }

    @Test
    @DisplayName(
            "the region a customer's lookups are made in is the tenant's own, else the platform's, and a tenant with neither is told so")
    void theStorefrontRegionIsTheTenantsOwnElseThePlatforms() {
        UUID platformRegion = UUID.fromString("018fa200-4000-7000-8000-0000000000e9");
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon,
                    status, version)
                VALUES (:id, NULL, 'PLATFORM-TASHKENT', 'P', 'P', 'P',
                    41.3, 69.2, 41.0, 69.0, 41.6, 69.6, 'ACTIVE', 1)
                """).param("id", platformRegion).update();

        assertThat(geocoding.primaryRegionId(TENANT))
                .as("the tenant's own region wins although the platform's is listed first")
                .isEqualTo(TASHKENT);

        jdbc.sql("UPDATE fulfillment.regions SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", TASHKENT)
                .update();
        assertThat(geocoding.primaryRegionId(TENANT))
                .as("a tenant with no region of its own is served by the platform's")
                .isEqualTo(platformRegion);

        jdbc.sql("UPDATE fulfillment.regions SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", platformRegion)
                .update();
        assertThatThrownBy(() -> geocoding.primaryRegionId(TENANT)).hasMessageContaining("no active region");
    }

    // ---------------------------------------------------------------- authorization

    @Test
    @DisplayName(
            "no session at all is refused before any handler, and a token without an account at this brand is not found")
    void onlyACustomerOfThisBrandGetsThrough() throws Exception {
        MvcResult anonymous = mvc.perform(post(base() + "/geocode/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"navoi\"}"))
                .andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(base() + "/map-config"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        for (String path : List.of("/geocode/suggestions", "/geocode/resolutions", "/geocode/reverse-resolutions")) {
            String body = path.endsWith("reverse-resolutions")
                    ? "{\"point\":{\"latitude\":41.3,\"longitude\":69.2}}"
                    : "{\"text\":\"navoi\"}";
            assertThat(lookup(base() + path, guest, body).getResponse().getStatus())
                    .as("a guest: %s", path)
                    .isEqualTo(404);
            // A customer of another tenant naming this tenant's path is a stranger, told the same thing.
            assertThat(lookup(base() + path, otherTenantCustomer, body)
                            .getResponse()
                            .getStatus())
                    .as("another tenant's customer: %s", path)
                    .isEqualTo(404);
        }
        assertThat(mvc.perform(get(base() + "/map-config").with(token(guest)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(meteredCalls(TENANT)).as("a refusal spends nothing").isZero();
    }

    // -------------------------------------------------------------- limits and meter

    @Test
    @DisplayName("a customer who types too fast is told to slow down, and another customer is unaffected")
    void theLimitIsPerCustomer() throws Exception {
        String body = "{\"text\":\"navoi ko'chasi 28\"}";
        for (int i = 0; i < 20; i++) {
            assertThat(lookup(base() + "/geocode/resolutions", customer, body)
                            .getResponse()
                            .getStatus())
                    .isEqualTo(200);
        }

        MvcResult limited = lookup(base() + "/geocode/resolutions", customer, body);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString())
                .contains("RATE_LIMIT_EXCEEDED")
                .contains("retryAfterSeconds");
        assertThat(lookup(base() + "/geocode/resolutions", secondCustomer, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("the tenant has a ceiling for all its customers together, checked after the personal one")
    void theTenantCeilingStopsManyCustomersEachUnderTheirOwnLimit() throws Exception {
        limiter.denyTenantCeiling.set(true);

        MvcResult limited = lookup(base() + "/geocode/suggestions", customer, "{\"text\":\"navoi\"}");

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limiter.operations())
                .as("the personal key is asked first, then the tenant's own")
                .containsSubsequence("geo.suggest", "geo.storefront.suggest");
        assertThat(meteredCalls(TENANT))
                .as("a limited call never reached the provider")
                .isZero();
    }

    @Test
    @DisplayName("a lookup is metered once as a customer-surface call, and a repeat answered from the cache is not")
    void aLookupIsMeteredOnceWithItsSurface() throws Exception {
        lookup(base() + "/geocode/resolutions", customer, "{\"text\":\"navoi ko'chasi 28\"}");

        assertThat(meteredCalls(TENANT)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT dimensions->>'surface' || '/' || (dimensions->>'operation') FROM commercial.usage_events
                         WHERE tenant_id = :tenantId AND entitlement_key = 'geocode.requests'
                        """).param("tenantId", TENANT).query(String.class).single())
                .isEqualTo("customer/geocode");

        MvcResult repeat =
                lookup(base() + "/geocode/resolutions", secondCustomer, "{\"text\":\"  Navoi  ko'chasi 28 \"}");
        assertThat(repeat.getResponse().getStatus()).isEqualTo(200);
        assertThat(meteredCalls(TENANT))
                .as("a cache hit cost the provider nothing")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("bad input is a validation failure, and the answer is never stored as an address")
    void badInputIsRefusedAndNothingIsStored() throws Exception {
        assertThat(lookup(base() + "/geocode/suggestions", customer, "{\"text\":\"a\"}")
                        .getResponse()
                        .getStatus())
                .as("one character is not a question")
                .isEqualTo(400);
        assertThat(lookup(base() + "/geocode/suggestions", customer, "{\"text\":\"navoi\",\"locale\":\"de\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        MvcResult halfPoint =
                lookup(base() + "/geocode/reverse-resolutions", customer, "{\"point\":{\"latitude\":41.3}}");
        assertThat(halfPoint.getResponse().getStatus()).isEqualTo(400);
        assertThat(halfPoint.getResponse().getContentAsString()).contains("VALIDATION_FAILED");

        lookup(base() + "/geocode/resolutions", customer, "{\"text\":\"navoi\"}");
        assertThat(jdbc.sql("SELECT count(*) FROM customer.addresses")
                        .query(Long.class)
                        .single())
                .as(
                        "a geocode answer is a suggestion the customer confirms; the platform stores none (GEOCODER stays refused)")
                .isZero();
    }

    // ---------------------------------------------------------------------- helpers

    private long meteredCalls(UUID tenantId) {
        return jdbc.sql("""
                        SELECT count(*) FROM commercial.usage_events
                         WHERE tenant_id = :tenantId AND entitlement_key = 'geocode.requests'
                        """).param("tenantId", tenantId).query(Long.class).single();
    }

    private MvcResult lookup(String path, String subject, String body) throws Exception {
        return mvc.perform(post(path)
                        .with(token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String base() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    private void tenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from)
                VALUES (:id, :tenantId, 1, 'TENANT_SHARED', TIMESTAMPTZ '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes(tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("tenantId", tenantId)
                .update();
    }

    private void brand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void insertRegion(
            UUID tenantId, UUID regionId, String code, double swLat, double swLon, double neLat, double neLon) {
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon,
                    status, version)
                VALUES (:id, :tenantId, :code, :code, :code, :code,
                    :centreLat, :centreLon, :swLat, :swLon, :neLat, :neLon, 'ACTIVE', 1)
                """)
                .param("id", regionId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("centreLat", (swLat + neLat) / 2)
                .param("centreLon", (swLon + neLon) / 2)
                .param("swLat", swLat)
                .param("swLon", swLon)
                .param("neLat", neLat)
                .param("neLon", neLon)
                .update();
    }

    /** A customer account and the link from the token's (issuer, subject) to it. */
    private void account(UUID tenantId, String subject) {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id,
                    identity_partition_brand_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, NULL, 'ACTIVE', :now, :now)
                """)
                .param("id", accountId)
                .param("tenantId", tenantId)
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id,
                    identity_partition_brand_id, customer_account_id, issuer, subject, status,
                    linked_at)
                VALUES (:id, :tenantId, NULL, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("issuer", ISSUER)
                .param("subject", subject)
                .param("now", now)
                .update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        /**
         * The fake is registered by a {@code local}-profile-only configuration in the running
         * application; a test has no such profile, so it supplies the bean itself, and
         * {@code horecaos.geo.provider=fake} above is what makes it the active adapter.
         */
        @Bean
        FakeGeocoderAdapter fakeGeocoderAdapter(java.time.Clock clock) {
            return new FakeGeocoderAdapter(clock);
        }

        /** The real in-process limiter, recording what it was asked and able to deny the tenant ceiling. */
        @Bean
        @Primary
        RecordingRateLimiter recordingRateLimiter() {
            return new RecordingRateLimiter(new InProcessRateLimiter(java.time.Clock.systemUTC()));
        }
    }

    static final class RecordingRateLimiter implements RateLimiter {

        private final RateLimiter delegate;
        private final List<String> asked = new CopyOnWriteArrayList<>();
        final AtomicBoolean denyTenantCeiling = new AtomicBoolean();

        RecordingRateLimiter(RateLimiter delegate) {
            this.delegate = delegate;
        }

        @Override
        public Decision check(Key key, Policy policy) {
            asked.add(key.operation());
            if (denyTenantCeiling.get() && key.operation().startsWith("geo.storefront.")) {
                return Decision.denied(java.time.Duration.ofSeconds(30));
            }
            return delegate.check(key, policy);
        }

        List<String> operations() {
            return new ArrayList<>(asked);
        }

        void reset() {
            asked.clear();
            denyTenantCeiling.set(false);
        }
    }
}
