package uz.horecaos.platform.tenancy.web;

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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave 9 (gap map row {@code 10.2a}): {@code
 * OperationsBrandController.bulkChangeServiceState}, the branch list's bulk
 * close/open bar over {@link uz.horecaos.platform.tenancy.application.ServiceScheduleService
 * #changeServiceStateBulk}. Proves three things the bar depends on: every
 * named location in the brand is actually closed and each gets its own
 * outcome, a location from a different brand is refused per-item rather than
 * corrupting that location's own brand ownership, the endpoint is refused
 * without {@code LOCATION_SERVICE_STATE_CHANGE} held at {@code BRAND} scope
 * or broader (a location-scoped grant is not enough, the same shape {@code
 * OperationsBrandControllerEndpointTests} already proves for {@code
 * LOCATION_READ}), and a resubmission under the same {@code Idempotency-Key}
 * replays rather than re-applying (ADR 0031).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsBrandControllerBulkServiceStateTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-8000-7000-8000-0000000000e1");
    private static final UUID BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000e2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000e3");
    private static final UUID LOCATION_1 = UUID.fromString("018f9b20-8000-7000-8000-0000000000e4");
    private static final UUID LOCATION_2 = UUID.fromString("018f9b20-8000-7000-8000-0000000000e5");
    private static final UUID OTHER_BRAND_LOCATION = UUID.fromString("018f9b20-8000-7000-8000-0000000000e6");

    private static final String FULL = "bulk-service-state-full";
    private static final String LOCATION_SCOPED = "bulk-service-state-location-scoped";

    private static final String BULK_ENDPOINT =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/service-states";

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        // BRAND_MANAGER carries LOCATION_SERVICE_STATE_CHANGE at BRAND scope --
        // the real role the branch list's bulk bar is built for.
        grant(FULL, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        // LOCATION_MANAGER carries the identical capability, but granted only at
        // its own native LOCATION scope on LOCATION_1 -- narrower than the bulk
        // endpoint's BRAND-scoped requirement, so it must not be enough.
        grant(LOCATION_SCOPED, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION_1);
    }

    @Test
    void closesEveryNamedLocationInTheBrandAndReportsAPerLocationOutcome() throws Exception {
        MvcResult result = mvc.perform(post(BULK_ENDPOINT)
                        .with(tokenFor(FULL))
                        .header("Idempotency-Key", "bulk-close-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationIds":["%s","%s","%s"],"mode":"FORCE_CLOSED","reasonCode":"network_outage"}
                                """.formatted(LOCATION_1, LOCATION_2, OTHER_BRAND_LOCATION)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"requestedCount\":3")
                .contains("\"appliedCount\":2")
                .contains("\"failedCount\":1");
        assertThat(body).contains(LOCATION_1.toString()).contains(LOCATION_2.toString());
        // The location from another brand is named as a per-item failure, not
        // silently dropped and not applied.
        assertThat(body).contains(OTHER_BRAND_LOCATION.toString()).contains("LOCATION_NOT_IN_BRAND");

        assertThat(modeOf(LOCATION_1)).isEqualTo("FORCE_CLOSED");
        assertThat(modeOf(LOCATION_2)).isEqualTo("FORCE_CLOSED");
        // Never touched -- upsertServiceState trusts its brandId parameter, so
        // the per-item brand check above is what keeps this location's own row
        // (if any) from being poisoned with a foreign brand.
        assertThat(hasServiceStateRow(OTHER_BRAND_LOCATION)).isFalse();
    }

    @Test
    void isRefusedWithoutTheCapabilityHeldAtBrandScopeOrBroader() throws Exception {
        MvcResult result = mvc.perform(post(BULK_ENDPOINT)
                        .with(tokenFor(LOCATION_SCOPED))
                        .header("Idempotency-Key", "bulk-close-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationIds":["%s"],"mode":"FORCE_CLOSED","reasonCode":"network_outage"}
                                """.formatted(LOCATION_1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.LOCATION_SERVICE_STATE_CHANGE.code());
        // The location-scoped grant must not have leaked through either.
        assertThat(modeOf(LOCATION_1)).isEqualTo("FOLLOW_SCHEDULE");
    }

    @Test
    void aResubmissionUnderTheSameIdempotencyKeyReplaysRatherThanReapplying() throws Exception {
        String requestBody = """
                {"locationIds":["%s"],"mode":"FORCE_CLOSED","reasonCode":"network_outage"}
                """.formatted(LOCATION_1);

        MvcResult first = mvc.perform(post(BULK_ENDPOINT)
                        .with(tokenFor(FULL))
                        .header("Idempotency-Key", "bulk-close-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isNull();

        MvcResult second = mvc.perform(post(BULK_ENDPOINT)
                        .with(tokenFor(FULL))
                        .header("Idempotency-Key", "bulk-close-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn();

        assertThat(second.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // A replay never reaches the handler a second time, so the row's own
        // version -- bumped by every real write -- stays at its first value.
        assertThat(versionOf(LOCATION_1)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    private String modeOf(UUID locationId) {
        return jdbc.sql("SELECT mode FROM tenant.location_service_state WHERE location_id = :id")
                .param("id", locationId)
                .query(String.class)
                .optional()
                .orElse("FOLLOW_SCHEDULE");
    }

    private boolean hasServiceStateRow(UUID locationId) {
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM tenant.location_service_state WHERE location_id = :id)")
                        .param("id", locationId)
                        .query(Boolean.class)
                        .single());
    }

    private int versionOf(UUID locationId) {
        return jdbc.sql("SELECT version FROM tenant.location_service_state WHERE location_id = :id")
                .param("id", locationId)
                .query(Integer.class)
                .single();
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'bulk-service-state-endpoint', 'Bulk Service State', 'Bulk Service State',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        insertBrand(BRAND, "MAIN");
        insertBrand(OTHER_BRAND, "OTHER");
        insertLocation(LOCATION_1, BRAND, "CENTRE");
        insertLocation(LOCATION_2, BRAND, "SUBURB");
        insertLocation(OTHER_BRAND_LOCATION, OTHER_BRAND, "FOREIGN");
    }

    private void insertBrand(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    private void insertLocation(UUID id, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'bulk service state endpoint test', :validFrom)
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

    /** See {@code OperationsBrandControllerEndpointTests.tokenFor}'s own doc for why {@code platform-admin} is here. */
    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of("platform-admin")))));
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
