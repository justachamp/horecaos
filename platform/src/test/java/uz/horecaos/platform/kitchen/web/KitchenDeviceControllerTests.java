package uz.horecaos.platform.kitchen.web;

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
import org.junit.jupiter.api.DisplayName;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link KitchenDeviceController} exercised through the real HTTP stack —
 * mirroring {@code ReservationControllerLocationIsolationHttpTests}' own
 * style for the analogous gap.
 *
 * <p><b>wave139-integration adversarial review, batch 5, P17/medium.</b> The
 * wave's only new test file, {@code KitchenDeviceServiceTests}, calls {@code
 * KitchenDeviceService} directly — {@code list}/{@code approve}/{@code
 * revoke}'s {@code @RequiresCapability(KITCHEN_STATION_MANAGE, LOCATION)} and
 * {@code revoke}'s own {@code atThisBranch} cross-branch check were never
 * proven at the HTTP layer, only the service-level {@code
 * list(tenantId, locationId)} SQL scoping one layer down. This class proves
 * both: a caller holding only {@code kitchen.ticket.read}/{@code
 * kitchen.ticket.advance} (exactly what an enrolled device itself is granted
 * — see this controller's own class doc) is refused on all three endpoints,
 * and a device that exists only at a sibling branch answers 404, not 403 or
 * 200, through the controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenDeviceControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION_A = UUID.randomUUID();
    private static final UUID LOCATION_B = UUID.randomUUID();

    /** Holds {@code kitchen.ticket.read}/{@code kitchen.ticket.advance} at LOCATION_A only — never {@code kitchen.station.manage}. */
    private static final String READ_ONLY_STAFF = "kitchen-device-http-read-only";
    /** Holds {@code kitchen.station.manage} at LOCATION_A only. */
    private static final String MANAGER = "kitchen-device-http-manager";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the kitchen device HTTP test");
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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        // iam.device_principals/grants and tenant.brands/locations all chain
        // back to tenant.tenants by foreign key, the same TRUNCATE ...
        // CASCADE KitchenDeviceServiceTests and the reservation isolation
        // test use.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(READ_ONLY_STAFF, PlatformRole.LOCATION_STAFF, LOCATION_A);
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, LOCATION_A);
    }

    @Test
    @DisplayName("GET .../kitchen/devices refuses a caller without kitchen.station.manage")
    void listRefusesACallerWithoutStationManage() throws Exception {
        MvcResult attempt = mvc.perform(get(devicesPath(LOCATION_A)).with(tokenFor(READ_ONLY_STAFF)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("READ_ONLY_STAFF holds kitchen.ticket.read/advance only — exactly an enrolled "
                        + "device's own grant, per this controller's class doc")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("POST .../enrolments/{userCode}/approve refuses a caller without kitchen.station.manage")
    void approveRefusesACallerWithoutStationManage() throws Exception {
        MvcResult attempt = mvc.perform(post(devicesPath(LOCATION_A) + "/enrolments/TESTCODE/approve")
                        .with(tokenFor(READ_ONLY_STAFF))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Line 1 KDS\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("POST .../devices/{deviceId}/revoke refuses a caller without kitchen.station.manage")
    void revokeRefusesACallerWithoutStationManage() throws Exception {
        UUID deviceId = seedDevice(LOCATION_A, "own-branch");

        MvcResult attempt = mvc.perform(post(devicesPath(LOCATION_A) + "/" + deviceId + "/revoke")
                        .with(tokenFor(READ_ONLY_STAFF))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Screen replaced\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
        assertThat(deviceStatus(deviceId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("POST .../devices/{deviceId}/revoke answers not-found for a device at a sibling branch, "
            + "exercising atThisBranch rather than just the service's own list(tenantId, locationId) scoping")
    void revokeAnswersNotFoundForADeviceAtASiblingBranch() throws Exception {
        UUID deviceId = seedDevice(LOCATION_B, "sibling-branch");

        MvcResult attempt = mvc.perform(post(devicesPath(LOCATION_A) + "/" + deviceId + "/revoke")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "kitchen-device-revoke-cross-branch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Wrong device id, wrong branch\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("MANAGER holds kitchen.station.manage only at LOCATION_A; the device lives at LOCATION_B")
                .isEqualTo(404);
        assertThat(deviceStatus(deviceId))
                .as("a refused revoke must leave the sibling branch's device exactly as it was")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("GET .../kitchen/devices lists a device at the caller's own branch")
    void listSucceedsForACallersOwnBranch() throws Exception {
        UUID deviceId = seedDevice(LOCATION_A, "own-branch-list");

        MvcResult result = mvc.perform(get(devicesPath(LOCATION_A)).with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains(deviceId.toString());
    }

    // ------------------------------------------------------------------ fixtures

    private String deviceStatus(UUID deviceId) {
        return jdbc.sql("SELECT status FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
    }

    private static String devicesPath(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/kitchen/devices";
    }

    /** A device row written directly, the same way the reservation isolation test seeds a booking. */
    private UUID seedDevice(UUID locationId, String label) {
        UUID deviceId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        String subject = "device-" + label + "-" + deviceId;

        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                             status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                                'ACTIVE', 'test-fixture', 'kitchen device http test', :validFrom)
                        """)
                .param("id", grantId)
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.KITCHEN_DEVICE))
                .param("locationId", locationId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                        INSERT INTO iam.device_principals
                            (id, tenant_id, brand_id, location_id, device_class, display_name,
                             keycloak_client_internal_id, keycloak_client_id, principal_subject,
                             grant_id, status, enrolled_by)
                        VALUES (:id, :tenantId, :brandId, :locationId, 'KITCHEN_KDS', :displayName,
                                :kcInternal, :kcClientId, :subject, :grantId, 'ACTIVE', 'test-fixture-manager')
                        """)
                .param("id", deviceId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("displayName", "KDS " + label)
                .param("kcInternal", "kc-internal-" + deviceId)
                .param("kcClientId", "kc-client-" + deviceId)
                .param("subject", subject)
                .param("grantId", grantId)
                .update();

        return deviceId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'kitchen-device-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_A)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'NORTH', 'north', 'North', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION_B)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'kitchen device http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
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
