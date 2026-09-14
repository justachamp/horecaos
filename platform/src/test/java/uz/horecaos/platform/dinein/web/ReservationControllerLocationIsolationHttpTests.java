package uz.horecaos.platform.dinein.web;

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
 * The host stand's single-booking endpoints, exercised through the real HTTP
 * stack — mirroring {@code OperationsOrderControllerActionCapabilitiesHttpTests}'
 * own style for the analogous order-side gap.
 *
 * <p><b>Wave W01 adversarial review, findings critical/1 and high/2.</b>
 * {@code find}, {@code stateAction} and {@code amend} resolved a booking by
 * {@code tenantId}/{@code reservationId} alone — {@code
 * CapabilityEnforcementInterceptor} checks {@code RESERVATION_READ}/{@code
 * RESERVATION_MANAGE} only against the path's own {@code locationId}, never
 * against where the booking actually lives — so a {@code location-staff}
 * token scoped to one branch could read, reveal the guest PII of, confirm,
 * cancel or amend (guest name/phone/note included) a booking that belongs to
 * a different branch of the same tenant, exactly the shape {@code
 * OperationsOrderController.requireOrderAtLocation} already guards against
 * for orders. {@link ReservationService#find}/{@code revealGuest}/{@code
 * move}/{@code amend} now resolve through {@code
 * JdbcDineInStore#findReservationAtLocation}, scoped to the path's own {@code
 * locationId} as well as {@code tenantId}, and refuse with a stable
 * not-found rather than serving or mutating another branch's booking.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationControllerLocationIsolationHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc400-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc400-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fc400-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fc400-4000-7000-8000-0000000000c2");

    /** Holds {@code RESERVATION_READ}/{@code RESERVATION_MANAGE} at {@code LOCATION_A} only. */
    private static final String STAFF = "reservation-isolation-staff";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the reservation location isolation HTTP test");
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

    private UUID channelId;

    @BeforeEach
    void reset() {
        // dinein.reservations/reservation_tables/tables/sections/location_settings and
        // tenant.brands/locations/sales_channels and iam.grants all chain back to
        // tenant.tenants by foreign key, so TRUNCATE ... CASCADE from the root clears
        // every one of them in a single statement, the same way DineInTests does.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(STAFF, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION_A);
    }

    @Test
    @DisplayName("GET .../reservations/{id} refuses a booking belonging to a different branch")
    void findRefusesAReservationBelongingToAnotherBranch() throws Exception {
        UUID reservationId = seedReservation(LOCATION_B, "find");

        MvcResult attempt = mvc.perform(
                        get(reservationsPath(LOCATION_A) + "/" + reservationId).with(tokenFor(STAFF)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("STAFF holds RESERVATION_READ only at LOCATION_A; the booking lives at LOCATION_B")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("GET .../reservations/{id}?purpose=... refuses a booking belonging to a different branch, "
            + "before any guest detail is decrypted")
    void findWithPurposeRefusesAReservationBelongingToAnotherBranch() throws Exception {
        UUID reservationId = seedReservation(LOCATION_B, "reveal");

        MvcResult attempt = mvc.perform(get(reservationsPath(LOCATION_A) + "/" + reservationId)
                        .param("purpose", "front desk match")
                        .with(tokenFor(STAFF)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("STAFF holds RESERVATION_READ only at LOCATION_A; the booking, and its guest PII, "
                        + "live at LOCATION_B")
                .isEqualTo(404);
        assertThat(attempt.getResponse().getContentAsString())
                .as("a refused reveal must never decrypt or return the other branch's guest PII")
                .doesNotContain("guestName", "guestPhone", "unused-encrypted-reveal");
    }

    @Test
    @DisplayName("POST .../reservations/{id}/state-actions refuses a booking belonging to a different branch")
    void stateActionRefusesAReservationBelongingToAnotherBranch() throws Exception {
        UUID reservationId = seedReservation(LOCATION_B, "state-action");

        MvcResult attempt = mvc.perform(post(reservationsPath(LOCATION_A) + "/" + reservationId + "/state-actions")
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", "reservation-cross-branch-state-action-1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CANCELLED\",\"reason\":\"Guest rang back\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("STAFF holds RESERVATION_MANAGE only at LOCATION_A; the booking lives at LOCATION_B")
                .isEqualTo(404);
        assertThat(reservationStatus(reservationId))
                .as("a refused state action must leave LOCATION_B's booking exactly as it was")
                .isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("POST .../reservations/{id}/amendments refuses a booking belonging to a different branch, "
            + "including an attempted guest PII correction")
    void amendRefusesAReservationBelongingToAnotherBranch() throws Exception {
        UUID reservationId = seedReservation(LOCATION_B, "amend");

        MvcResult attempt = mvc.perform(post(reservationsPath(LOCATION_A) + "/" + reservationId + "/amendments")
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", "reservation-cross-branch-amend-1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":6,\"requestedFrom\":\"2026-09-14T18:00:00Z\","
                                + "\"requestedTo\":\"2026-09-14T20:00:00Z\",\"tableIds\":[\""
                                + UUID.randomUUID() + "\"],\"guestName\":\"Overwritten Guest\","
                                + "\"guestPhone\":\"998900000000\",\"reason\":\"Correction\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("STAFF holds RESERVATION_MANAGE only at LOCATION_A; the booking, and its guest PII, "
                        + "live at LOCATION_B")
                .isEqualTo(404);
        assertThat(guestNameEncrypted(reservationId))
                .as("a refused amendment must never overwrite another branch's guest PII")
                .isEqualTo("unused-encrypted-amend");
    }

    @Test
    @DisplayName("GET .../reservations/{id} still succeeds for a booking at the operator's own branch")
    void findSucceedsForAReservationAtTheOperatorsOwnBranch() throws Exception {
        UUID reservationId = seedReservation(LOCATION_A, "own-branch");

        MvcResult result = mvc.perform(
                        get(reservationsPath(LOCATION_A) + "/" + reservationId).with(tokenFor(STAFF)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("STAFF holds RESERVATION_READ at exactly the branch this booking lives at")
                .isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains(reservationId.toString());
    }

    // ------------------------------------------------------------------ fixtures

    private String reservationStatus(UUID reservationId) {
        return jdbc.sql("SELECT status FROM dinein.reservations WHERE id = :id")
                .param("id", reservationId)
                .query(String.class)
                .single();
    }

    private String guestNameEncrypted(UUID reservationId) {
        return jdbc.sql("SELECT guest_name_encrypted FROM dinein.reservations WHERE id = :id")
                .param("id", reservationId)
                .query(String.class)
                .single();
    }

    private static String reservationsPath(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/reservations";
    }

    /**
     * A booking row written directly, the same way {@code
     * OperationsOrderControllerActionCapabilitiesHttpTests} seeds an order —
     * the guest columns hold placeholder text rather than a real {@code
     * ProtectedValue}, since every test here is refused before any of them
     * would be decrypted.
     */
    private UUID seedReservation(UUID locationId, String label) {
        UUID reservationId = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-14T18:00:00Z");

        jdbc.sql("""
                INSERT INTO dinein.reservations (
                    id, tenant_id, brand_id, location_id, guest_name_encrypted,
                    guest_phone_encrypted, guest_phone_lookup_hash, note_encrypted,
                    party_size, requested_from, requested_to, turnaround_minutes_snapshot,
                    status, source_channel_id, created_by, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :name, :phone, :phoneHash, :note,
                    4, :from, :to, 15, 'REQUESTED', :channelId, 'test-fixture', 1)
                """)
                .param("id", reservationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("name", "unused-encrypted-" + label)
                .param("phone", "unused-encrypted-" + label)
                .param("phoneHash", "unused-hash-" + label)
                .param("note", "unused-note-" + label)
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", from.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("channelId", channelId)
                .update();

        return reservationId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'reservation-isolation', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :t, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'reservation location isolation http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
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
    }
}
