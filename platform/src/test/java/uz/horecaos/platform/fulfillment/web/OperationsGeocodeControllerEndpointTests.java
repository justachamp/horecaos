package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
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
import org.springframework.cache.CacheManager;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.camel.geo.fake.FakeGeocoderAdapter;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The operations lookup surface end to end (ADR 0145): the real route, gateway, service,
 * cache, meter and capability interceptor, answering from the controlled fake.
 *
 * <p>What this proves that the port-level contract cannot: the capability is enforced at the two
 * scopes the record needs and no wider; a tenant's region is the only region it can name; a
 * lookup is metered once and a cached repeat is not; an outage is a {@code 200} that says so; a
 * person who types too fast is told to slow down; and the console's own minimal JSON, with every
 * optional field omitted, is accepted (Jackson 3 refuses a missing primitive, so the optional
 * fields are boxed and this is where that is held).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsGeocodeControllerEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018f9b10-4000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b10-4000-7000-8000-0000000000c2");
    private static final UUID LOCATION = UUID.fromString("018f9b10-4000-7000-8000-0000000000c3");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b10-4000-7000-8000-0000000000d1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b10-4000-7000-8000-0000000000d2");

    private static final UUID TASHKENT = UUID.fromString("018f9b10-4000-7000-8000-0000000000e1");
    private static final UUID SECOND_REGION = UUID.fromString("018f9b10-4000-7000-8000-0000000000e2");
    private static final UUID OTHER_REGION = UUID.fromString("018f9b10-4000-7000-8000-0000000000e3");

    // Subjects are minted per test, so one test's lookups never spend another's rate limit.
    private final String owner = UUID.randomUUID().toString();
    private final String brandManager = UUID.randomUUID().toString();
    private final String locationStaff = UUID.randomUUID().toString();
    private final String finance = UUID.randomUUID().toString();
    private final String kitchen = UUID.randomUUID().toString();
    private final String otherOwner = UUID.randomUUID().toString();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the geocode endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.geo.provider", () -> "fake");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private CacheManager caches;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.regions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.usage_aggregates").update();
        roleRegistry.synchronize();
        // A cached answer from another test would make this one's metering a hit.
        var cache = caches.getCache("geo.responses");
        if (cache != null) {
            cache.clear();
        }

        insertTenant(TENANT);
        insertBrand(TENANT, BRAND);
        insertLocation(TENANT, BRAND, LOCATION);
        insertTenant(OTHER_TENANT);
        insertBrand(OTHER_TENANT, OTHER_BRAND);

        insertRegion(TENANT, TASHKENT, "TASHKENT", "ACTIVE");
        insertRegion(OTHER_TENANT, OTHER_REGION, "OTHER", "ACTIVE");

        grant(owner, PlatformRole.TENANT_OWNER, TENANT, "TENANT", TENANT);
        grant(brandManager, PlatformRole.BRAND_MANAGER, TENANT, "BRAND", BRAND);
        grant(locationStaff, PlatformRole.LOCATION_STAFF, TENANT, "LOCATION", LOCATION);
        grant(finance, PlatformRole.TENANT_FINANCE, TENANT, "TENANT", TENANT);
        grant(kitchen, PlatformRole.KITCHEN_DEVICE, TENANT, "LOCATION", LOCATION);
        grant(otherOwner, PlatformRole.TENANT_OWNER, OTHER_TENANT, "TENANT", OTHER_TENANT);
    }

    // ------------------------------------------------------------------ map-config

    @Test
    void anyStaffPrincipalReadsTheMapConfigAndItNamesNoServerKey() throws Exception {
        // Even a kitchen display holds no map capability and may still be told what the map is.
        MvcResult read =
                mvc.perform(get(base() + "/map-config").with(tokenFor(kitchen))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(read.getResponse().getContentAsString());
        assertThat(body.get("provider").asString()).isEqualTo("FAKE");
        assertThat(body.get("configured").asBoolean()).isTrue();
        assertThat(body.get("browserKey").isNull())
                .as("the fake has no tiles and no key")
                .isTrue();
        assertThat(body.get("features").toString())
                .as("no TILES: a screen must say there is no map here rather than draw an empty one")
                .isEqualTo("[\"SUGGEST\",\"GEOCODE\",\"REVERSE\"]");
        assertThat(read.getResponse().getContentAsString())
                .doesNotContain("secret")
                .doesNotContain("horecaos:");
    }

    // ----------------------------------------------------------------- the lookups

    @Test
    void anOwnerGetsSuggestionsFromTheConsolesMinimalJson() throws Exception {
        // Only the one required field: regionId, near and locale all omitted, as the console
        // sends it. The tenant's single active region is used.
        MvcResult suggested = lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"amir temur\"}");

        assertThat(suggested.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(suggested.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(body.get("reason").isNull()).isTrue();
        assertThat(body.get("suggestions").size()).isGreaterThan(0);
        assertThat(body.get("suggestions").get(0).get("fullText").asString()).isNotBlank();
    }

    @Test
    void aResolutionFlagsTheResultInAnotherCountryAndKeepsTheOnesInRegion() throws Exception {
        MvcResult resolved = lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"amir temur ko'chasi\"}");

        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        JsonNode results =
                JSON.readTree(resolved.getResponse().getContentAsString()).get("results");
        boolean sawBishkek = false;
        boolean sawTashkentHigh = false;
        for (JsonNode result : results) {
            boolean inBox = result.get("latitude").asDouble() >= 41.15
                    && result.get("latitude").asDouble() <= 41.47
                    && result.get("longitude").asDouble() >= 69.04
                    && result.get("longitude").asDouble() <= 69.46;
            if (!inBox) {
                sawBishkek = true;
                assertThat(result.get("confidence").asString())
                        .as(
                                "the fake returns the Bishkek street as HIGH, as a naive provider would; the platform flags it")
                        .isEqualTo("LOW_CONFIDENCE");
            } else if (result.get("confidence").asString().equals("HIGH")) {
                sawTashkentHigh = true;
            }
        }
        assertThat(sawBishkek)
                .as("the scenario must contain the foreign street")
                .isTrue();
        assertThat(sawTashkentHigh).isTrue();
    }

    @Test
    void aReverseLookupNamesWhatIsAtAPoint() throws Exception {
        MvcResult reversed = lookup(
                brandPath("/geocode/reverse-resolutions"),
                owner,
                "{\"point\":{\"latitude\":41.3078,\"longitude\":69.2702}}");

        assertThat(reversed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(reversed.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(body.get("result").get("components").get("street").asString())
                .isEqualTo("Navoi ko'chasi");

        MvcResult nothing = lookup(
                brandPath("/geocode/reverse-resolutions"), owner, "{\"point\":{\"latitude\":41.2,\"longitude\":69.1}}");
        JsonNode empty = JSON.readTree(nothing.getResponse().getContentAsString());
        assertThat(empty.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(empty.get("result").isNull())
                .as("a point the provider knows nothing about is an answer")
                .isTrue();
    }

    @Test
    void anOutageAndARefusedKeyAreAnswersNotErrors() throws Exception {
        MvcResult down = lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"fake-scenario-unavailable\"}");
        assertThat(down.getResponse().getStatus()).isEqualTo(200);
        JsonNode downBody = JSON.readTree(down.getResponse().getContentAsString());
        assertThat(downBody.get("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(downBody.get("reason").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(downBody.get("results").size()).isZero();

        MvcResult refused = lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"fake-scenario-refused\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(refused.getResponse().getContentAsString())
                        .get("reason")
                        .asString())
                .isEqualTo("PROVIDER_REFUSED");
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void theCapabilityIsHeldByTheRolesThatDoTheTasksAndNoOthers() throws Exception {
        String body = "{\"text\":\"navoi\"}";

        assertThat(lookup(brandPath("/geocode/suggestions"), owner, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(lookup(brandPath("/geocode/suggestions"), brandManager, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        // Finance reads money, not maps; a kitchen display reads tickets.
        MvcResult financeRefused = lookup(brandPath("/geocode/suggestions"), finance, body);
        assertThat(financeRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(financeRefused.getResponse().getContentAsString()).contains("geo.lookup");
        assertThat(lookup(brandPath("/geocode/suggestions"), kitchen, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aBranchGrantUsesTheLocationPathAndCannotReachTheBrandPath() throws Exception {
        String body = "{\"text\":\"navoi\"}";

        // New order's address pane is used by staff whose grant is at the branch. A grant at
        // LOCATION never covers a BRAND requirement, which is why the location path exists.
        assertThat(lookup(locationPath("/geocode/suggestions"), locationStaff, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(lookup(locationPath("/geocode/resolutions"), locationStaff, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(lookup(
                                locationPath("/geocode/reverse-resolutions"),
                                locationStaff,
                                "{\"point\":{\"latitude\":41.3078,\"longitude\":69.2702}}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(lookup(brandPath("/geocode/suggestions"), locationStaff, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        // And a brand grant covers the location path beneath it.
        assertThat(lookup(locationPath("/geocode/suggestions"), brandManager, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        // Another tenant's owner reaches neither.
        assertThat(lookup(locationPath("/geocode/suggestions"), otherOwner, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aRegionOfAnotherTenantCannotBeNamedAndLooksLikeNoRegionAtAll() throws Exception {
        MvcResult foreign = lookup(
                brandPath("/geocode/suggestions"), owner, "{\"text\":\"navoi\",\"regionId\":\"" + OTHER_REGION + "\"}");

        // Not-found, not forbidden: the endpoint is not a way to learn which regions exist.
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(foreign.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");

        MvcResult own = lookup(
                brandPath("/geocode/suggestions"), owner, "{\"text\":\"navoi\",\"regionId\":\"" + TASHKENT + "\"}");
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
    }

    // --------------------------------------------------------------------- regions

    @Test
    void withNoRegionOrSeveralTheRequestIsRefusedWithAReason() throws Exception {
        insertRegion(TENANT, SECOND_REGION, "SAMARKAND", "ACTIVE");
        MvcResult ambiguous = lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"navoi\"}");
        assertThat(ambiguous.getResponse().getStatus()).isEqualTo(400);
        assertThat(ambiguous.getResponse().getContentAsString()).contains("regionId is required");

        jdbc.sql("UPDATE fulfillment.regions SET status = 'ARCHIVED' WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .update();
        MvcResult none = lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"navoi\"}");
        assertThat(none.getResponse().getStatus()).isEqualTo(422);
        assertThat(none.getResponse().getContentAsString()).contains("REGION_REQUIRED");

        MvcResult archived = lookup(
                brandPath("/geocode/suggestions"), owner, "{\"text\":\"navoi\",\"regionId\":\"" + TASHKENT + "\"}");
        assertThat(archived.getResponse().getStatus())
                .as("an archived region is not a region a lookup may be made in")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------- metering and cache

    @Test
    void aLookupIsMeteredOnceAndACachedRepeatIsNot() throws Exception {
        String body = "{\"text\":\"navoi ko'chasi 28\"}";

        lookup(brandPath("/geocode/resolutions"), owner, body);
        assertThat(meteredCalls(TENANT)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT dimensions->>'operation' FROM commercial.usage_events
                         WHERE tenant_id = :tenantId AND entitlement_key = 'geocode.requests'
                        """).param("tenantId", TENANT).query(String.class).single())
                .isEqualTo("geocode");

        // The same question, differently spaced and cased: one question, answered from the cache.
        MvcResult repeat = lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"  Navoi  ko'chasi 28 \"}");
        assertThat(repeat.getResponse().getStatus()).isEqualTo(200);
        assertThat(meteredCalls(TENANT))
                .as("a cache hit cost the provider nothing")
                .isEqualTo(1);

        // Another tenant asking the same question is a different cache entry and a different meter.
        lookup(otherBrandPath("/geocode/resolutions"), otherOwner, body);
        assertThat(meteredCalls(OTHER_TENANT)).isEqualTo(1);
        assertThat(meteredCalls(TENANT)).isEqualTo(1);
    }

    @Test
    void anOutageIsNotMeteredAndNothingFoundIsNotCached() throws Exception {
        lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"fake-scenario-unavailable\"}");
        assertThat(meteredCalls(TENANT))
                .as("a call that was not delivered is not billed")
                .isZero();

        lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"fake-scenario-empty\"}");
        lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"fake-scenario-empty\"}");
        assertThat(meteredCalls(TENANT))
                .as("'nothing found' is the answer most likely to be wrong tomorrow, so it is asked again")
                .isEqualTo(2);
    }

    @Test
    void aPersonWhoTypesTooFastIsToldToSlowDown() throws Exception {
        String body = "{\"text\":\"navoi ko'chasi 28\"}";
        for (int i = 0; i < 30; i++) {
            assertThat(lookup(brandPath("/geocode/resolutions"), owner, body)
                            .getResponse()
                            .getStatus())
                    .isEqualTo(200);
        }

        MvcResult limited = lookup(brandPath("/geocode/resolutions"), owner, body);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString())
                .contains("RATE_LIMIT_EXCEEDED")
                .contains("retryAfterSeconds");
        // The limit is per person: a colleague at the same tenant is unaffected.
        assertThat(lookup(brandPath("/geocode/resolutions"), brandManager, body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ validation

    @Test
    void badInputIsRefusedAsValidationNotAsAServerFault() throws Exception {
        assertThat(lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"a\"}")
                        .getResponse()
                        .getStatus())
                .as("one character is not a question")
                .isEqualTo(400);
        assertThat(lookup(brandPath("/geocode/suggestions"), owner, "{\"text\":\"navoi\",\"locale\":\"de\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        // A half point is garbage, named as a validation failure rather than a malformed body.
        MvcResult halfPoint =
                lookup(brandPath("/geocode/reverse-resolutions"), owner, "{\"point\":{\"latitude\":41.3}}");
        assertThat(halfPoint.getResponse().getStatus()).isEqualTo(400);
        assertThat(halfPoint.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        assertThat(lookup(
                                brandPath("/geocode/reverse-resolutions"),
                                owner,
                                "{\"point\":{\"latitude\":141.3,\"longitude\":69.2}}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        // An optional near, present and complete, is accepted.
        assertThat(lookup(
                                brandPath("/geocode/suggestions"),
                                owner,
                                "{\"text\":\"navoi\",\"near\":{\"latitude\":41.3,\"longitude\":69.2},\"locale\":\"uz-Latn\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aLookupNeedsNoIdempotencyKeyBecauseItWritesNothing() throws Exception {
        // post(...) above sends none. The point of stating it: ADR 0031's rule applies to effects,
        // and the only effect here is a meter row whose id is minted per call.
        MvcResult resolved = lookup(brandPath("/geocode/resolutions"), owner, "{\"text\":\"navoi\"}");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.regions WHERE tenant_id = :tenantId")
                        .param("tenantId", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM customer.addresses")
                        .query(Long.class)
                        .single())
                .as("a geocode answer is a suggestion; nothing is stored as an address")
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
                        .with(tokenFor(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String base() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private static String brandPath(String suffix) {
        return base() + suffix;
    }

    private static String locationPath(String suffix) {
        return base() + "/locations/" + LOCATION + suffix;
    }

    private static String otherBrandPath(String suffix) {
        return "/api/v1/operations/tenants/" + OTHER_TENANT + "/brands/" + OTHER_BRAND + suffix;
    }

    private void insertTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "geocode-endpoint-" + tenantId)
                .update();
    }

    private void insertBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void insertLocation(UUID tenantId, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent',
                    'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    private void insertRegion(UUID tenantId, UUID regionId, String code, String status) {
        jdbc.sql("""
                INSERT INTO fulfillment.regions (
                    id, tenant_id, code, display_name_ru, display_name_uz, display_name_en,
                    centre_lat, centre_lon, bbox_sw_lat, bbox_sw_lon, bbox_ne_lat, bbox_ne_lon,
                    status, version)
                VALUES (:id, :tenantId, :code, :code, :code, :code,
                    41.31, 69.24, 41.15, 69.04, 41.47, 69.46, :status, 1)
                """)
                .param("id", regionId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("status", status)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'geocode endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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

        /**
         * The fake is registered by a {@code local}-profile-only configuration in the running
         * application; a test has no such profile, so it supplies the bean itself, and
         * {@code horecaos.geo.provider=fake} above is what makes it the active adapter.
         */
        @Bean
        FakeGeocoderAdapter fakeGeocoderAdapter(Clock clock) {
            return new FakeGeocoderAdapter(clock);
        }
    }
}
