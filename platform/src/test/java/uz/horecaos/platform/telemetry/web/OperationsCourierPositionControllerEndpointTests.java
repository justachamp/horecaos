package uz.horecaos.platform.telemetry.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.DutySessionStatus;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.LivePositionRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.TrackWindowRow;
import uz.horecaos.platform.telemetry.infrastructure.persistence.TrackRetentionSweeper;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The dispatcher's live map and the audited track reveal, through the real HTTP stack (row 3.2,
 * ADR 0045).
 *
 * <p>Until this class nothing reached {@link OperationsCourierPositionController}: the telemetry
 * tests call the services, and the role tests only mention the two capabilities. That is how the
 * console came to call a path the controller was never mapped on, and how nobody knew that the
 * {@code withoutPin} band, the location scope or the "platform.admin does not imply the reveal"
 * rule were claims about the services rather than about the endpoint.
 *
 * <p>Fixtures write the rows the way production leaves them (a duty session, the live row, an
 * encrypted track window); the clock is the real one, so every instant is stated relative to now
 * with margins of minutes rather than seconds.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsCourierPositionControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fa100-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa100-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fa100-4000-7000-8000-0000000000c1");
    private static final UUID SIBLING = UUID.fromString("018fa100-4000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fa100-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fa100-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fa100-4000-7000-8000-0000000000c3");

    private static final UUID PIN_COURIER = UUID.fromString("018fa100-5000-7000-8000-000000000001");
    private static final UUID COARSE_COURIER = UUID.fromString("018fa100-5000-7000-8000-000000000002");
    private static final UUID STALE_COURIER = UUID.fromString("018fa100-5000-7000-8000-000000000003");
    private static final UUID EDGE_COURIER = UUID.fromString("018fa100-5000-7000-8000-000000000004");
    private static final UUID SIBLING_COURIER = UUID.fromString("018fa100-5000-7000-8000-000000000005");
    private static final UUID OTHER_TENANT_COURIER = UUID.fromString("018fa100-5000-7000-8000-000000000006");

    private static final UUID REVEALER_ROLE = UUID.fromString("018fa100-6000-7000-8000-0000000000a1");

    private static final String DISPATCHER = "positions-http-dispatcher";
    private static final String BRANCH_MANAGER = "positions-http-branch-manager";
    private static final String NO_GRANT = "positions-http-nobody";
    private static final String REVEALER = "positions-http-revealer";
    private static final String PLATFORM_ADMIN = "positions-http-platform-admin";
    private static final String OTHER_TENANT_DISPATCHER = "positions-http-other-tenant-dispatcher";

    private static final String PURPOSE =
            "A customer says the order never arrived and the courier says it was handed over";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier position endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // ADR 0029: the stored track is envelope-encrypted, so the reveal needs a real key to open.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
    private JdbcTelemetryStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private FieldProtection protection;

    @Autowired
    @SuppressWarnings("NullAway")
    private TrackRetentionSweeper partitions;

    private static String fleetPath(UUID location) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                + "/operations/couriers/positions";
    }

    private static String revealPath(UUID courier) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/operations/couriers/"
                + courier + "/track-reveals";
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_track_summaries, fulfillment.courier_location_tracks, "
                        + "fulfillment.courier_positions_live, fulfillment.courier_duty_sessions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        // The partitions are DDL and survive the truncate; making sure today's and yesterday's exist
        // keeps the track fixtures independent of the hour the suite happens to run in.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        partitions.ensurePartition(today.minusDays(1));
        partitions.ensurePartitions();

        insertTenant(TENANT, "positions-http");
        insertBrand(TENANT, BRAND);
        insertLocation(TENANT, BRAND, LOCATION, "CENTRE");
        insertLocation(TENANT, BRAND, SIBLING, "NORTH");
        insertTenant(OTHER_TENANT, "positions-http-other");
        insertBrand(OTHER_TENANT, OTHER_BRAND);
        insertLocation(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "OTHER");

        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, ScopeType.BRAND, TENANT, BRAND);
        grant(BRANCH_MANAGER, PlatformRole.LOCATION_MANAGER, ScopeType.LOCATION, TENANT, LOCATION);
        grant(OTHER_TENANT_DISPATCHER, PlatformRole.COURIER_DISPATCHER, ScopeType.BRAND, OTHER_TENANT, OTHER_BRAND);
        // courier.track.reveal is in no default bundle (PlatformRoleTests), so the only way a person
        // holds it is a role someone wrote for them: the same shape a tenant's own role takes.
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, 'track-revealer', 'Track revealer', 'TENANT', 'ACTIVE', false)
                """).param("id", REVEALER_ROLE).param("tenantId", TENANT).update();
        jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:roleId, :capability)")
                .param("roleId", REVEALER_ROLE)
                .param("capability", Capability.COURIER_TRACK_REVEAL.code())
                .update();
        grantCustomRole(REVEALER, REVEALER_ROLE, TENANT);
        grantPlatformAdmin(PLATFORM_ADMIN);
    }

    // ------------------------------------------------------------------------------ fleet read

    @Test
    @DisplayName(
            "the fleet read answers drawable couriers as pins and the rest, without a coordinate, as the withoutPin band")
    void theFleetSplitsPinsFromTheBandWithoutACoordinate() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        onDuty(PIN_COURIER, LOCATION, 41.311081, 69.240562, 12.0, now.minus(Duration.ofMinutes(1)), 2);
        // Exactly the floor is still drawn: 100 m is the boundary the rule states ("worse than 100").
        onDuty(EDGE_COURIER, LOCATION, 41.32, 69.25, 100.0, now.minus(Duration.ofMinutes(2)), 0);
        // On duty and precise, but the last fix is older than ten minutes: not a place any more.
        onDuty(STALE_COURIER, LOCATION, 41.33, 69.26, 8.0, now.minus(Duration.ofMinutes(25)), 1);
        // Fresh, and so imprecise that a pin would send somebody to the wrong street.
        onDuty(COARSE_COURIER, LOCATION, 41.34, 69.27, 150.0, now.minus(Duration.ofMinutes(1)), 0);

        MvcResult read =
                mvc.perform(get(fleetPath(LOCATION)).with(tokenFor(DISPATCHER))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(read.getResponse().getContentAsString(UTF_8));
        assertThat(ids(body.get("pins"), "courierId"))
                .as("drawable and fresh: the precise courier and the one exactly on the floor")
                .containsExactlyInAnyOrder(PIN_COURIER.toString(), EDGE_COURIER.toString());
        JsonNode pin = byCourier(body.get("pins"), PIN_COURIER);
        assertThat(pin.get("latitude").asDouble()).isEqualTo(41.311081);
        assertThat(pin.get("longitude").asDouble()).isEqualTo(69.240562);
        assertThat(pin.get("accuracyMeters").asDouble()).isEqualTo(12.0);
        assertThat(pin.get("activeAssignmentCount").asInt()).isEqualTo(2);

        assertThat(ids(body.get("withoutPin"), "courierId"))
                .containsExactlyInAnyOrder(STALE_COURIER.toString(), COARSE_COURIER.toString());
        assertThat(byCourier(body.get("withoutPin"), STALE_COURIER)
                        .get("reason")
                        .asString())
                .isEqualTo("LAST_FIX_TOO_OLD");
        assertThat(byCourier(body.get("withoutPin"), COARSE_COURIER)
                        .get("reason")
                        .asString())
                .isEqualTo("ACCURACY_BELOW_MAP_FLOOR");
        for (JsonNode coarse : body.get("withoutPin")) {
            assertThat(coarse.has("latitude") || coarse.has("longitude"))
                    .as("a courier who cannot honestly be drawn is returned with no coordinate at all")
                    .isFalse();
            assertThat(coarse.get("activeAssignmentCount")).isNotNull();
        }
    }

    @Test
    @DisplayName("an empty branch answers two empty lists, not an error")
    void aBranchWithNobodyOnDutyIsAnEmptyFleet() throws Exception {
        MvcResult read =
                mvc.perform(get(fleetPath(LOCATION)).with(tokenFor(DISPATCHER))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(read.getResponse().getContentAsString(UTF_8));
        assertThat(body.get("pins")).isEmpty();
        assertThat(body.get("withoutPin")).isEmpty();
    }

    @Test
    @DisplayName(
            "the fleet read needs courier.position.read: a caller with no grant is refused and told which capability")
    void theFleetReadIsRefusedWithoutTheCapability() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        onDuty(PIN_COURIER, LOCATION, 41.311081, 69.240562, 12.0, now.minus(Duration.ofMinutes(1)), 0);

        MvcResult refused =
                mvc.perform(get(fleetPath(LOCATION)).with(tokenFor(NO_GRANT))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString(UTF_8))
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_POSITION_READ.code())
                .as("a refusal says nothing about where anybody is")
                .doesNotContain(PIN_COURIER.toString())
                .doesNotContain("41.311081");
    }

    @Test
    @DisplayName(
            "the fleet read is scoped to one branch: a branch manager sees her own couriers and is refused the sibling's")
    void theFleetReadStaysInsideTheLocation() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        onDuty(PIN_COURIER, LOCATION, 41.311081, 69.240562, 12.0, now.minus(Duration.ofMinutes(1)), 0);
        onDuty(SIBLING_COURIER, SIBLING, 41.40, 69.30, 9.0, now.minus(Duration.ofMinutes(1)), 0);

        MvcResult own = mvc.perform(get(fleetPath(LOCATION)).with(tokenFor(BRANCH_MANAGER)))
                .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        JsonNode ownBody = JSON.readTree(own.getResponse().getContentAsString(UTF_8));
        assertThat(ids(ownBody.get("pins"), "courierId"))
                .as("her own branch, and not the sibling's courier")
                .containsExactly(PIN_COURIER.toString());

        MvcResult sibling = mvc.perform(get(fleetPath(SIBLING)).with(tokenFor(BRANCH_MANAGER)))
                .andReturn();
        assertThat(sibling.getResponse().getStatus())
                .as("a LOCATION grant covers that location and nothing beside it")
                .isEqualTo(403);
        assertThat(sibling.getResponse().getContentAsString(UTF_8)).doesNotContain(SIBLING_COURIER.toString());

        // The brand-level dispatcher reads either branch, one at a time and never merged.
        MvcResult dispatcherSibling =
                mvc.perform(get(fleetPath(SIBLING)).with(tokenFor(DISPATCHER))).andReturn();
        assertThat(dispatcherSibling.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(
                        JSON.readTree(dispatcherSibling.getResponse().getContentAsString(UTF_8))
                                .get("pins"),
                        "courierId"))
                .containsExactly(SIBLING_COURIER.toString());
    }

    @Test
    @DisplayName("another tenant's dispatcher cannot read this tenant's branch, whichever path they name")
    void theFleetReadDoesNotCrossTenants() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        onDuty(PIN_COURIER, LOCATION, 41.311081, 69.240562, 12.0, now.minus(Duration.ofMinutes(1)), 0);
        onDuty(
                OTHER_TENANT,
                OTHER_BRAND,
                OTHER_TENANT_COURIER,
                OTHER_LOCATION,
                39.65,
                66.96,
                7.0,
                now.minus(Duration.ofMinutes(1)),
                0);

        MvcResult direct = mvc.perform(get(fleetPath(LOCATION)).with(tokenFor(OTHER_TENANT_DISPATCHER)))
                .andReturn();
        assertThat(direct.getResponse().getStatus()).isEqualTo(403);
        assertThat(direct.getResponse().getContentAsString(UTF_8)).doesNotContain(PIN_COURIER.toString());

        // Their own tenant in the path but this tenant's branch id: the branch is not theirs, and the
        // answer must not be the other tenant's fleet nor a distinguishable "it exists elsewhere".
        MvcResult smuggled = mvc.perform(get("/api/v1/tenants/" + OTHER_TENANT + "/brands/" + OTHER_BRAND
                                + "/locations/" + LOCATION + "/operations/couriers/positions")
                        .with(tokenFor(OTHER_TENANT_DISPATCHER)))
                .andReturn();
        assertThat(smuggled.getResponse().getStatus()).isIn(403, 404);
        assertThat(smuggled.getResponse().getContentAsString(UTF_8)).doesNotContain(PIN_COURIER.toString());
    }

    // ----------------------------------------------------------------------------- track reveal

    @Test
    @DisplayName("courier.track.reveal is held by nobody by default: platform.admin and a dispatcher are both refused")
    void theRevealIsNotImpliedByPlatformAdminOrDispatch() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seedTrackWindow(PIN_COURIER, LOCATION, now.minus(Duration.ofMinutes(30)), 41.311, 69.24);

        for (String subject : List.of(PLATFORM_ADMIN, DISPATCHER, BRANCH_MANAGER)) {
            MvcResult refused = mvc.perform(reveal(
                            subject, PIN_COURIER, now.minus(Duration.ofHours(1)), now, PURPOSE, "refused-" + subject))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).as(subject).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString(UTF_8))
                    .contains("INSUFFICIENT_CAPABILITY")
                    .contains(Capability.COURIER_TRACK_REVEAL.code())
                    .doesNotContain("41.311");
        }
        assertThat(revealAuditCount())
                .as("a refusal leaves no reveal on the record")
                .isZero();
    }

    @Test
    @DisplayName(
            "a reveal needs a purpose of a sentence, a window that ends after it starts and fits the cap, and an idempotency key")
    void theRevealRefusesAnUnboundedOrUnexplainedRequest() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seedTrackWindow(PIN_COURIER, LOCATION, now.minus(Duration.ofMinutes(30)), 41.311, 69.24);

        MvcResult shortPurpose = mvc.perform(
                        reveal(REVEALER, PIN_COURIER, now.minus(Duration.ofHours(1)), now, "check", "short-purpose"))
                .andReturn();
        assertThat(shortPurpose.getResponse().getStatus())
                .as("a one-word purpose")
                .isEqualTo(400);

        MvcResult blankPurpose = mvc.perform(reveal(
                        REVEALER, PIN_COURIER, now.minus(Duration.ofHours(1)), now, "              ", "blank-purpose"))
                .andReturn();
        assertThat(blankPurpose.getResponse().getStatus())
                .as("twelve spaces are not a sentence")
                .isEqualTo(400);

        MvcResult backwards = mvc.perform(
                        reveal(REVEALER, PIN_COURIER, now, now.minus(Duration.ofHours(1)), PURPOSE, "backwards"))
                .andReturn();
        assertThat(backwards.getResponse().getStatus()).isEqualTo(400);

        MvcResult tooWide = mvc.perform(
                        reveal(REVEALER, PIN_COURIER, now.minus(Duration.ofDays(40)), now, PURPOSE, "too-wide"))
                .andReturn();
        assertThat(tooWide.getResponse().getStatus())
                .as("'this courier's track' is not a request anybody can make")
                .isEqualTo(400);

        MvcResult noKey = mvc.perform(post(revealPath(PIN_COURIER))
                        .with(tokenFor(REVEALER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(now.minus(Duration.ofHours(1)), now, PURPOSE)))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString(UTF_8)).contains("IDEMPOTENCY_KEY_REQUIRED");

        assertThat(revealAuditCount())
                .as("nothing was decrypted by any of these, so nothing is on the record")
                .isZero();
    }

    @Test
    @DisplayName(
            "a reveal decrypts only the requested window and writes one audit fact naming the person, the courier, the window and the purpose")
    void aRevealOpensTheBoundedWindowAndLeavesOneAuditFact() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant inside = now.minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES);
        seedTrackWindow(PIN_COURIER, LOCATION, inside, 41.311, 69.24);
        seedTrackWindow(
                PIN_COURIER, LOCATION, now.minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.MINUTES), 41.5, 69.5);

        MvcResult revealed = mvc.perform(
                        reveal(REVEALER, PIN_COURIER, now.minus(Duration.ofHours(1)), now, PURPOSE, "reveal-1"))
                .andReturn();

        assertThat(revealed.getResponse().getStatus())
                .as(revealed.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        JsonNode body = JSON.readTree(revealed.getResponse().getContentAsString(UTF_8));
        assertThat(body.get("courierId").asString()).isEqualTo(PIN_COURIER.toString());
        assertThat(body.get("purpose").asString()).isEqualTo(PURPOSE);
        assertThat(body.get("windows"))
                .as("the window five hours back is outside the one asked for and is not decrypted")
                .hasSize(1);
        JsonNode observation = body.get("windows").get(0).get("observations").get(0);
        assertThat(observation.get("lat").asDouble()).isEqualTo(41.311);
        assertThat(observation.get("lon").asDouble()).isEqualTo(69.24);

        Map<String, Object> fact = jdbc.sql("""
                SELECT action_code, audit_class, actor_subject, scope_type, scope_id, target_type, target_id,
                       reason, capability_used, outcome, change_document::text AS change_document
                  FROM audit.audit_events WHERE action_code = 'telemetry.courier_track.revealed'
                """).query().singleRow();
        assertThat(revealAuditCount()).isEqualTo(1);
        assertThat(fact)
                .containsEntry("audit_class", "SECURITY")
                .containsEntry("actor_subject", REVEALER)
                .containsEntry("scope_type", "LOCATION")
                .containsEntry("scope_id", LOCATION)
                .containsEntry("target_type", "CourierTrack")
                .containsEntry("target_id", PIN_COURIER)
                .containsEntry("reason", PURPOSE)
                .containsEntry("capability_used", "courier.track.reveal");
        assertThat((String) fact.get("change_document"))
                .as("the fact records that a window was opened and how many, never a coordinate")
                .contains("windowsRevealed")
                .doesNotContain("41.311")
                .doesNotContain("69.24");
    }

    @Test
    @DisplayName(
            "the same idempotency key replays the first answer and leaves one audit fact; the same key for another window is refused")
    void aReplayedRevealIsNotAuditedTwice() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seedTrackWindow(
                PIN_COURIER,
                LOCATION,
                now.minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES),
                41.311,
                69.24);
        Instant from = now.minus(Duration.ofHours(1));

        MvcResult first = mvc.perform(reveal(REVEALER, PIN_COURIER, from, now, PURPOSE, "reveal-replay"))
                .andReturn();
        MvcResult again = mvc.perform(reveal(REVEALER, PIN_COURIER, from, now, PURPOSE, "reveal-replay"))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(again.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER))
                .isEqualTo("true");
        assertThat(again.getResponse().getContentAsString(UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(UTF_8));
        assertThat(revealAuditCount())
                .as("a retry is the same act, not a second look")
                .isEqualTo(1);

        MvcResult reused = mvc.perform(
                        reveal(REVEALER, PIN_COURIER, from.minus(Duration.ofHours(2)), now, PURPOSE, "reveal-replay"))
                .andReturn();
        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(reused.getResponse().getContentAsString(UTF_8)).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(revealAuditCount()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a window that cannot be decrypted fails the request and takes the audit fact with it: no reveal without a record, no record of a reveal that did not happen")
    void theAuditFactAndTheDecryptionShareATransaction() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant start = now.minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES);
        store.upsertTrackWindow(new TrackWindowRow(
                UUID.randomUUID(),
                TENANT,
                PIN_COURIER,
                dutySessionFor(PIN_COURIER, LOCATION),
                start,
                start.plusSeconds(50),
                "tn7wq",
                "tn7wq",
                1,
                0,
                "not-a-protected-value",
                now));

        Throwable failure = null;
        MvcResult result = null;
        try {
            result = mvc.perform(reveal(
                            REVEALER, PIN_COURIER, now.minus(Duration.ofHours(1)), now, PURPOSE, "reveal-broken"))
                    .andReturn();
        } catch (Exception thrown) {
            failure = thrown;
        }

        assertThat(failure != null || (result != null && result.getResponse().getStatus() >= 400))
                .as("the corrupt window is not answered as a success")
                .isTrue();
        assertThat(revealAuditCount())
                .as(
                        "the audit fact is written in the same transaction as the decryption, so a failed reveal leaves none")
                .isZero();
    }

    @Test
    @DisplayName("a reveal naming another tenant's courier decrypts nothing of theirs")
    void aRevealCannotReachAnotherTenantsTrack() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seedTrackWindow(
                OTHER_TENANT_COURIER,
                OTHER_LOCATION,
                OTHER_TENANT,
                OTHER_BRAND,
                now.minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES),
                39.65,
                66.96);

        MvcResult attempt = mvc.perform(reveal(
                        REVEALER, OTHER_TENANT_COURIER, now.minus(Duration.ofHours(1)), now, PURPOSE, "reveal-foreign"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(attempt.getResponse().getContentAsString(UTF_8));
        assertThat(body.get("windows"))
                .as("the read is filtered by the caller's tenant, so a foreign courier id opens nothing")
                .isEmpty();
        assertThat(attempt.getResponse().getContentAsString(UTF_8)).doesNotContain("39.65");
    }

    // ------------------------------------------------------------------------------- fixtures

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reveal(
            String subject, UUID courier, Instant from, Instant to, String purpose, String key) {
        return post(revealPath(courier))
                .with(tokenFor(subject))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(from, to, purpose));
    }

    private static String body(Instant from, Instant to, String purpose) {
        return "{\"from\":\"%s\",\"to\":\"%s\",\"purpose\":\"%s\"}".formatted(from, to, purpose);
    }

    private long revealAuditCount() {
        return jdbc.sql(
                        "SELECT count(*) FROM audit.audit_events WHERE action_code = 'telemetry.courier_track.revealed'")
                .query(Long.class)
                .single();
    }

    private static List<String> ids(JsonNode array, String field) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.get(field).asString())
                .toList();
    }

    private static JsonNode byCourier(JsonNode array, UUID courier) {
        for (JsonNode node : array) {
            if (courier.toString().equals(node.get("courierId").asString())) {
                return node;
            }
        }
        throw new AssertionError("no entry for courier " + courier + " in " + array);
    }

    /** A courier with an open duty session at {@code location} and a live row stating where they last were. */
    private void onDuty(
            UUID courier,
            UUID location,
            double latitude,
            double longitude,
            double accuracy,
            Instant capturedAt,
            int carrying) {
        onDuty(TENANT, BRAND, courier, location, latitude, longitude, accuracy, capturedAt, carrying);
    }

    private void onDuty(
            UUID tenant,
            UUID brand,
            UUID courier,
            UUID location,
            double latitude,
            double longitude,
            double accuracy,
            Instant capturedAt,
            int carrying) {
        UUID session = dutySessionFor(courier, location, tenant, brand);
        store.upsertLivePosition(new LivePositionRow(
                tenant,
                courier,
                session,
                brand,
                location,
                latitude,
                longitude,
                accuracy,
                null,
                null,
                null,
                null,
                carrying,
                capturedAt,
                capturedAt));
    }

    private UUID dutySessionFor(UUID courier, UUID location) {
        return dutySessionFor(courier, location, TENANT, BRAND);
    }

    private UUID dutySessionFor(UUID courier, UUID location, UUID tenant, UUID brand) {
        return store.findOpenSession(tenant, courier).map(DutySessionRow::id).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            store.insertDutySession(new DutySessionRow(
                    id,
                    tenant,
                    brand,
                    location,
                    courier,
                    UUID.randomUUID(),
                    "device-" + courier,
                    DutySessionStatus.OPEN,
                    CollectionGate.ON_DUTY,
                    Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MICROS),
                    LocalDate.now(ZoneOffset.UTC).plusYears(1),
                    "fixture",
                    Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MICROS),
                    null,
                    null,
                    null,
                    1));
            return id;
        });
    }

    private void seedTrackWindow(UUID courier, UUID location, Instant windowStart, double latitude, double longitude) {
        seedTrackWindow(courier, location, TENANT, BRAND, windowStart, latitude, longitude);
    }

    /** One stored minute, protected exactly as ingest protects it (ADR 0029), so the reveal has something real to open. */
    private void seedTrackWindow(
            UUID courier,
            UUID location,
            UUID tenant,
            UUID brand,
            Instant windowStart,
            double latitude,
            double longitude) {
        UUID windowId = UUID.randomUUID();
        String plaintext = "[{\"t\":\"%s\",\"lat\":%s,\"lon\":%s,\"acc\":9.0}]"
                .formatted(windowStart.plusSeconds(10), latitude, longitude);
        String protectedTrack = protection
                .protect(
                        tenant,
                        DataClass.PERSONAL_SENSITIVE,
                        new FieldProtection.RecordRef(
                                "fulfillment.courier_location_tracks", "protected_track", windowId),
                        plaintext)
                .serialize();
        store.upsertTrackWindow(new TrackWindowRow(
                windowId,
                tenant,
                courier,
                dutySessionFor(courier, location, tenant, brand),
                windowStart,
                windowStart.plusSeconds(50),
                "tn7wq",
                "tn7wq",
                1,
                0,
                protectedTrack,
                windowStart.plusSeconds(60)));
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void insertLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    private void grant(String subject, PlatformRole role, ScopeType scopeType, UUID tenantId, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'courier position endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType.name())
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantCustomRole(String subject, UUID roleId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'track reveal, granted per person', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + roleId + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantPlatformAdmin(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'courier position endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + PlatformRole.PLATFORM_ADMIN.code()).getBytes(UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
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
