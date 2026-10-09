package uz.horecaos.platform.kitchen.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.application.devices.DeviceClientProvisioner;
import uz.horecaos.platform.iam.application.devices.FakeDeviceClientProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.RoutingLevel;
import uz.horecaos.platform.kitchen.domain.TicketItemStatus;
import uz.horecaos.platform.kitchen.domain.TicketStatus;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketCountsRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.tenancy.application.port.PolicyCurrentCache;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0151, over HTTP with real device principals: a wall display is enrolled by the real handshake
 * (begin, a manager's approval, the one-shot claim), is granted the real role through the real grant
 * path, and then calls the real endpoints with a token whose subject is the device's own.
 *
 * <p>Only ticket <em>production</em> is a stand-in: {@code KitchenTicketService} is mocked because a
 * ticket needs a catalogue, a routing rule and an order line behind it, none of which is under test.
 * What is under test is the wiring a wall depends on: which capability opens which read, which station
 * the server applies, which policy the projection carries, who may read their own record, and what a
 * manager sees of the wall. The Keycloak client behind the device is the in-memory fake the enrolment
 * tests already use, as that adapter is proven against a real realm elsewhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenWallDisplayHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc151-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc151-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc151-6000-7000-8000-0000000000c1");
    private static final UUID SIBLING = UUID.fromString("018fc151-6000-7000-8000-0000000000c2");
    private static final UUID GRILL = UUID.fromString("018fc151-6000-7000-8000-0000000000d1");
    private static final UUID BAR = UUID.fromString("018fc151-6000-7000-8000-0000000000d2");
    private static final UUID SIBLINGS_STATION = UUID.fromString("018fc151-6000-7000-8000-0000000000d3");

    private static final String MANAGER = "wall-http-manager";
    private static final String OTHER_BRANCH_MANAGER = "wall-http-other-branch-manager";
    private static final String COOK = "wall-http-cook";
    private static final String OWNER = "wall-http-owner";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static int nextAddress = 0;

    private static final String BASE =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/kitchen";
    private static final String DEVICES = BASE + "/devices";
    private static final String ENROLMENTS = "/api/v1/control-plane/device-enrolments";

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
    private MeterRegistry meters;

    @Autowired
    @SuppressWarnings("NullAway")
    private PolicyCurrentCache policyCache;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueCache configurationCache;

    @MockitoBean
    @SuppressWarnings("NullAway")
    private KitchenTicketService tickets;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        seedTenancy();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
        grant(OTHER_BRANCH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", SIBLING);
        grant(COOK, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        for (var scope : List.of(
                uz.horecaos.platform.iam.api.ResourceScope.tenant(TENANT),
                uz.horecaos.platform.iam.api.ResourceScope.location(TENANT, BRAND, LOCATION))) {
            policyCache.evict("ordering.lateness", scope);
            configurationCache.evict("ordering.late_colour", scope);
            configurationCache.evict("ordering.at_risk_before_minutes", scope);
        }

        // Two tickets on the pass: one line at each of two stations, so a wall pointed at one of them shows
        // exactly that line and a manager's preview can ask for either.
        UUID orderOne = UUID.randomUUID();
        UUID ticketOne = UUID.randomUUID();
        TicketRow ticket = ticket(ticketOne, orderOne, "A-014");
        when(tickets.board(eq(TENANT), eq(LOCATION), anyList(), anyInt())).thenReturn(List.of(ticket));
        when(tickets.items(eq(TENANT), eq(ticketOne)))
                .thenReturn(List.of(item(ticketOne, GRILL, 2), item(ticketOne, BAR, 1)));
        when(tickets.counts(eq(TENANT), eq(LOCATION), anyList())).thenReturn(new TicketCountsRow(1, 0, 1, 0, 0));
        when(tickets.orderProgressWired()).thenReturn(true);
    }

    // -------------------------------------------------------- the handshake and the class

    @Test
    @DisplayName("a tablet that asked to be a touch display is approved as a wall: it holds exactly the wall's role")
    void aTouchRequestApprovedAsAWallHoldsTheWallsRole() throws Exception {
        Enrolled wall = enrol("KITCHEN_KDS", "KITCHEN_VDU");

        assertThat(wall.response().get("deviceClass").asString()).isEqualTo("KITCHEN_VDU");
        assertThat(wall.response().get("requestedClass").asString()).isEqualTo("KITCHEN_KDS");
        assertThat(rolesOf(wall.subject())).containsExactly("kitchen-vdu-device");
    }

    @Test
    @DisplayName("a wall that asked to be a wall cannot be approved as a touch display, and the code stays usable")
    void aWallRequestCannotBeWidened() throws Exception {
        Begun begun = begin("KITCHEN_VDU");

        MvcResult refused = approve(begun.userCode(), "KITCHEN_KDS");
        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM iam.device_principals")
                        .query(Long.class)
                        .single())
                .as("nothing was provisioned")
                .isZero();

        MvcResult approved = approve(begun.userCode(), "KITCHEN_VDU");
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a client that names no class (from before ADR 0151) is approved as the class the device asked for")
    void anApprovalWithNoClassIsAsAsked() throws Exception {
        for (String asked : List.of("KITCHEN_KDS", "KITCHEN_VDU")) {
            Begun begun = begin(asked);

            MvcResult approved = mvc.perform(post(DEVICES + "/enrolments/" + begun.userCode() + "/approve")
                            .with(tokenFor(MANAGER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"Old client\"}"))
                    .andReturn();

            assertThat(approved.getResponse().getStatus())
                    .as(approved.getResponse().getContentAsString())
                    .isEqualTo(200);
            JsonNode device = JSON.readTree(approved.getResponse().getContentAsString());
            assertThat(device.get("deviceClass").asString()).isEqualTo(asked);
            assertThat(device.get("requestedClass").asString()).isEqualTo(asked);
        }
        MvcResult unknown = mvc.perform(post(DEVICES + "/enrolments/NOPE-0000/approve")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nothing\"}"))
                .andReturn();
        assertThat(unknown.getResponse().getStatus())
                .as("no class and no pending code: 404")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the approver sees the class a pending code claims, and an unknown code is a plain 404")
    void thePendingCodeShowsItsClaim() throws Exception {
        Begun begun = begin("KITCHEN_VDU");

        JsonNode claim = ok(get(DEVICES + "/enrolments/" + begun.userCode()).with(tokenFor(MANAGER)));
        assertThat(claim.get("requestedClass").asString()).isEqualTo("KITCHEN_VDU");

        assertThat(mvc.perform(get(DEVICES + "/enrolments/NOPE-0000").with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(DEVICES + "/enrolments/" + begun.userCode()).with(tokenFor(COOK)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("reading what a code claims is the approver's act, not a cook's")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the audit fact for an enrolment records the class asked for and the class approved")
    void theEnrolmentFactCarriesBothClasses() throws Exception {
        enrol("KITCHEN_KDS", "KITCHEN_VDU");

        assertThat(jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                         WHERE action_code = 'kitchen.device.enrolled'
                        """).query(String.class).single())
                .contains("KITCHEN_VDU")
                .contains("KITCHEN_KDS");
    }

    // ------------------------------------------------------- what a wall may and may not do

    @Test
    @DisplayName("a wall reads the VDU projection and is refused everything else, the capability named")
    void aWallReadsOnlyItsProjection() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        JsonNode projection = ok(get(BASE + "/vdu").with(tokenFor(wall.subject())));
        assertThat(projection.get("tickets")).hasSize(1);

        UUID item = UUID.randomUUID();
        for (String refused : List.of(
                BASE + "/tickets",
                BASE + "/tickets/" + UUID.randomUUID(),
                BASE + "/stations",
                "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                        + "/orders/lateness-policy",
                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                        + "/operations/streams?channels=kitchen_board")) {
            MvcResult result =
                    mvc.perform(get(refused).with(tokenFor(wall.subject()))).andReturn();
            assertThat(result.getResponse().getStatus()).as("GET " + refused).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
        }
        for (String action : List.of("start", "ready", "recall")) {
            MvcResult result = mvc.perform(post(BASE + "/ticket-items/" + item + "/" + action)
                            .with(tokenFor(wall.subject()))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "adv-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as("POST " + action).isEqualTo(403);
        }
        MvcResult board = mvc.perform(get(BASE + "/tickets").with(tokenFor(wall.subject())))
                .andReturn();
        assertThat(board.getResponse().getContentAsString())
                .as("the refusal names the capability the wall lacks")
                .contains("kitchen.ticket.read");
    }

    @Test
    @DisplayName("a touch KDS is unchanged: it reads the board and the projection, and may advance a line")
    void aTouchDisplayIsUnchanged() throws Exception {
        Enrolled touch = enrol("KITCHEN_KDS", "KITCHEN_KDS");
        when(tickets.ticketOfItem(eq(TENANT), any()))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such line"));

        assertThat(ok(get(BASE + "/tickets").with(tokenFor(touch.subject()))).get("tickets"))
                .hasSize(1);
        assertThat(ok(get(BASE + "/vdu").with(tokenFor(touch.subject()))).get("tickets"))
                .hasSize(1);
        MvcResult advance = mvc.perform(post(BASE + "/ticket-items/" + UUID.randomUUID() + "/start")
                        .with(tokenFor(touch.subject()))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "adv-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(advance.getResponse().getStatus())
                .as("not refused for want of a capability: the line simply does not exist")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a staff member who could read the tickets still reads the VDU page")
    void staffKeepTheVduPage() throws Exception {
        assertThat(ok(get(BASE + "/vdu").with(tokenFor(COOK))).get("tickets")).hasSize(1);
        assertThat(ok(get(BASE + "/vdu").with(tokenFor(MANAGER))).get("tickets"))
                .hasSize(1);
    }

    // --------------------------------------------------------------------- the station

    @Test
    @DisplayName("a wall shows the station the server holds for it, whatever station the request names")
    void theWallsStationIsTheServersNotTheUrls() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        assertThat(lines(vdu(wall.subject(), null)))
                .as("no station configured: the whole branch")
                .containsExactlyInAnyOrder("GRILL:2", "BAR:1");

        configure(wall, GRILL, 1).andExpectOk();

        assertThat(lines(vdu(wall.subject(), BAR)))
                .as("the request names the bar; the server applies the grill it holds for this wall")
                .containsExactly("GRILL:2");
        assertThat(lines(vdu(wall.subject(), null))).containsExactly("GRILL:2");
        assertThat(lines(vdu(MANAGER, BAR)))
                .as("a person's request is honoured as it always was: not a boundary against the branch's staff")
                .containsExactly("BAR:1");
        assertThat(lines(vdu(MANAGER, null))).containsExactlyInAnyOrder("GRILL:2", "BAR:1");
    }

    @Test
    @DisplayName("a touch KDS keeps naming its own station: only a wall has one held for it")
    void aTouchDisplayHonoursItsRequestsStation() throws Exception {
        Enrolled touch = enrol("KITCHEN_KDS", "KITCHEN_KDS");

        assertThat(lines(vdu(touch.subject(), BAR))).containsExactly("BAR:1");
    }

    @Test
    @DisplayName("a restarted wall comes back showing the right station: nothing was held in the page")
    void theStationSurvivesARestart() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        configure(wall, BAR, 1).andExpectOk();

        // A restart is a fresh token and a fresh request with no query at all.
        assertThat(lines(vdu(wall.subject(), null))).containsExactly("BAR:1");
        JsonNode me = ok(get("/api/v1/devices/me").with(tokenFor(wall.subject())));
        assertThat(me.get("station").get("code").asString()).isEqualTo("BAR");
        assertThat(me.get("station").get("displayNameRu").asString()).isEqualTo("Бар");
    }

    // ------------------------------------------------------------ configuring a wall

    @Test
    @DisplayName("configuring needs the version the form was opened at, refuses a stale one, and audits the change")
    void configuringIsVersionedAndAudited() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        MvcResult noVersion = mvc.perform(put(DEVICES + "/" + wall.deviceId() + "/display")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationId\":\"" + GRILL + "\"}"))
                .andReturn();
        assertThat(noVersion.getResponse().getStatus()).as("no If-Match").isEqualTo(400);

        configure(wall, GRILL, 1).andExpectOk();
        MvcResult stale = configure(wall, BAR, 1).result();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events WHERE action_code = 'kitchen.device.display_configured'
                        """).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a station of another branch, a touch KDS and a stranger's manager are each refused")
    void configuringRefusesWhatItMust() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        Enrolled touch = enrol("KITCHEN_KDS", "KITCHEN_KDS");

        assertThat(configure(wall, SIBLINGS_STATION, 1).result().getResponse().getStatus())
                .as("a station of another branch")
                .isEqualTo(400);
        assertThat(configure(touch, GRILL, 1).result().getResponse().getStatus())
                .as("a touch KDS has no display configuration")
                .isEqualTo(422);

        MvcResult stranger = mvc.perform(put(DEVICES + "/" + wall.deviceId() + "/display")
                        .with(tokenFor(OTHER_BRANCH_MANAGER))
                        .header("If-Match", "W/\"1\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationId\":null}"))
                .andReturn();
        assertThat(stranger.getResponse().getStatus())
                .as("a manager of another branch holds kitchen.station.manage only there")
                .isEqualTo(403);

        MvcResult cook = mvc.perform(put(DEVICES + "/" + wall.deviceId() + "/display")
                        .with(tokenFor(COOK))
                        .header("If-Match", "W/\"1\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationId\":null}"))
                .andReturn();
        assertThat(cook.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName(
            "Kitchen -> Devices shows the class, the station and when the wall last read, and a wall's read does not make an edit stale")
    void theDeviceListShowsTheWallAndItsReads() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        configure(wall, GRILL, 1).andExpectOk();

        JsonNode before = deviceRow(wall);
        assertThat(before.get("deviceClass").asString()).isEqualTo("KITCHEN_VDU");
        assertThat(before.get("display").get("station").get("code").asString()).isEqualTo("GRILL");
        assertThat(before.get("display").get("lastReadAt").isNull()).isTrue();
        assertThat(before.get("display").get("version").asInt()).isEqualTo(2);

        vdu(wall.subject(), null);
        vdu(wall.subject(), null);
        JsonNode after = deviceRow(wall);
        assertThat(after.get("display").get("lastReadAt").isNull()).isFalse();
        assertThat(after.get("display").get("notSeen").asBoolean()).isFalse();
        assertThat(after.get("display").get("version").asInt())
                .as("two reads later the manager's form is still at the version it opened at")
                .isEqualTo(2);

        // A second read inside the minute leaves the stamp alone; an old stamp is refreshed by the next one.
        String stamp = after.get("display").get("lastReadAt").asString();
        vdu(wall.subject(), null);
        assertThat(deviceRow(wall).get("display").get("lastReadAt").asString()).isEqualTo(stamp);
        jdbc.sql("UPDATE kitchen.device_displays SET last_read_at = :old WHERE device_id = :id")
                .param("old", Instant.now().minus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                .param("id", wall.deviceId())
                .update();
        assertThat(deviceRow(wall).get("display").get("notSeen").asBoolean())
                .as("thirty minutes without a read")
                .isTrue();
        vdu(wall.subject(), null);
        JsonNode refreshed = deviceRow(wall);
        assertThat(refreshed.get("display").get("notSeen").asBoolean()).isFalse();
        assertThat(refreshed.get("display").get("lastReadAt").asString()).isNotEqualTo(stamp);
    }

    @Test
    @DisplayName("saving a wall's station reports whether the wall has been seen, as the list does: a wall gone "
            + "quiet does not read as seen the moment a manager saves it")
    void theConfigureResponseSaysWhetherTheWallIsStillReading() throws Exception {
        Enrolled neverRead = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        Enrolled quiet = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        Enrolled reading = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        // Enrolled half an hour ago and never read: powered off, or never switched on.
        jdbc.sql("UPDATE iam.device_principals SET enrolled_at = :old WHERE id = :id")
                .param("old", Instant.now().minus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                .param("id", neverRead.deviceId())
                .update();
        // Read once, half an hour ago, and has not read since.
        readAt(quiet, Instant.now().minus(Duration.ofMinutes(30)));
        readAt(reading, Instant.now().minus(Duration.ofSeconds(20)));

        for (Enrolled wall : List.of(neverRead, quiet, reading)) {
            boolean expected = !wall.deviceId().equals(reading.deviceId());
            Configured saved = configure(wall, GRILL, 1);
            saved.andExpectOk();
            JsonNode response = JSON.readTree(saved.result().getResponse().getContentAsString());

            assertThat(response.get("notSeen").asBoolean())
                    .as("the PUT response for a wall that %s", expected ? "has gone quiet" : "is reading")
                    .isEqualTo(expected);
            assertThat(response.get("notSeen").asBoolean())
                    .as("and it says what the device list says of the same wall")
                    .isEqualTo(deviceRow(wall).get("display").get("notSeen").asBoolean());
            assertThat(response.get("notSeenAfterMinutes").asLong()).isEqualTo(5L);
        }
    }

    @Test
    @DisplayName("the display read is counted by outcome only: a wall's and a person's, never a branch or a device")
    void theReadsAreCountedByOutcomeAlone() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        double wallBefore = counter("wall_served");
        double staffBefore = counter("staff_served");

        vdu(wall.subject(), null);
        vdu(wall.subject(), null);
        vdu(MANAGER, null);

        assertThat(counter("wall_served") - wallBefore).isEqualTo(2.0);
        assertThat(counter("staff_served") - staffBefore).isEqualTo(1.0);
        assertThat(meters.find("horecaos.kitchen.display.reads")
                        .counters()
                        .iterator()
                        .next()
                        .getId()
                        .getTags())
                .as("the only tag is the outcome")
                .hasSize(1);
    }

    // -------------------------------------------------------------- the tenant's colours

    @Test
    @DisplayName(
            "the wall's colours are the tenant's own: the projection carries the resolved policy, for a wall and a person alike")
    void theProjectionCarriesTheTenantsLatenessPolicy() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        // Not the platform default: at risk 10 minutes and a 2 minute grace for the tenant, a different
        // grace for this branch on top, and the tenant's own late colour.
        author("TENANT", null, 600, 120, 1800, null, "tenant document");
        author("LOCATION", LOCATION, 600, 300, 1800, null, "branch grace");
        setLateColour("#c0392b");

        JsonNode served = ok(get("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                        + "/orders/lateness-policy")
                .with(tokenFor(OWNER)));
        JsonNode forWall = ok(get(BASE + "/vdu").with(tokenFor(wall.subject()))).get("lateness");
        JsonNode forManager = ok(get(BASE + "/vdu").with(tokenFor(MANAGER))).get("lateness");

        for (JsonNode lateness : List.of(forWall, forManager)) {
            assertThat(lateness.get("delivery").get("atRiskBeforeSeconds").asInt())
                    .isEqualTo(600);
            assertThat(lateness.get("delivery").get("lateAfterSeconds").asInt())
                    .as("the branch's own grace beats the tenant's")
                    .isEqualTo(300);
            assertThat(lateness.get("pickup").get("noPromiseFallbackSeconds").asInt())
                    .isEqualTo(1800);
            assertThat(lateness.get("lateColour").asString()).isEqualTo("#c0392b");
            assertThat(lateness.get("isPlatformDefault").asBoolean()).isFalse();
            assertThat(lateness)
                    .as("the projection's policy is the one GET .../lateness-policy serves")
                    .isEqualTo(served);
        }
    }

    @Test
    @DisplayName("with nothing authored the projection carries the platform default and says so")
    void theProjectionCarriesThePlatformDefaultWhenNothingIsAuthored() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        JsonNode lateness =
                ok(get(BASE + "/vdu").with(tokenFor(wall.subject()))).get("lateness");

        assertThat(lateness.get("isPlatformDefault").asBoolean()).isTrue();
        assertThat(lateness.get("delivery").get("atRiskBeforeSeconds").asInt()).isEqualTo(300);
        assertThat(lateness.get("delivery").get("noPromiseFallbackSeconds").asInt())
                .isEqualTo(2700);
        assertThat(lateness.get("lateColour").isNull()).isTrue();
    }

    // ---------------------------------------------------------------------- who am I

    @Test
    @DisplayName("a wall reads its own record: where it is, the branch's zone, no secret of any kind")
    void aWallReadsItsOwnRecord() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        MvcResult result = mvc.perform(get("/api/v1/devices/me").with(tokenFor(wall.subject())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode me = JSON.readTree(result.getResponse().getContentAsString());

        assertThat(me.get("deviceId").asString()).isEqualTo(wall.deviceId().toString());
        assertThat(me.get("deviceClass").asString()).isEqualTo("KITCHEN_VDU");
        assertThat(me.get("tenantId").asString()).isEqualTo(TENANT.toString());
        assertThat(me.get("brandId").asString()).isEqualTo(BRAND.toString());
        assertThat(me.get("locationId").asString()).isEqualTo(LOCATION.toString());
        assertThat(me.get("locationName").asString()).isEqualTo("Chilanzar");
        assertThat(me.get("timezone").asString()).isEqualTo("Asia/Tashkent");
        assertThat(me.get("station").isNull())
                .as("the whole branch until configured")
                .isTrue();
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("secret")
                .doesNotContain("client")
                .doesNotContain("token")
                .doesNotContain(wall.subject());
    }

    @Test
    @DisplayName("a person's token and a revoked device's token are refused by the self-read")
    void onlyAnActiveDeviceReadsItsOwnRecord() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");

        assertThat(mvc.perform(get("/api/v1/devices/me").with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a staff token has no device row")
                .isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/devices/me"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        MvcResult revoke = mvc.perform(post(DEVICES + "/" + wall.deviceId() + "/revoke")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rev-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"TV removed\"}"))
                .andReturn();
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);

        assertThat(mvc.perform(get("/api/v1/devices/me").with(tokenFor(wall.subject())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a token that outlives the revocation still stops here")
                .isEqualTo(403);
        assertThat(mvc.perform(get(BASE + "/vdu").with(tokenFor(wall.subject())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("and the grant is gone, so the projection refuses it too")
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "revoking a wall removes it from nothing a manager needs: it stays listed, revoked, and is no longer 'not seen'")
    void aRevokedWallIsListedRevoked() throws Exception {
        Enrolled wall = enrol("KITCHEN_VDU", "KITCHEN_VDU");
        mvc.perform(post(DEVICES + "/" + wall.deviceId() + "/revoke")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rev-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"TV removed\"}"))
                .andReturn();
        jdbc.sql("UPDATE iam.device_principals SET enrolled_at = now() - interval '2 days' WHERE id = :id")
                .param("id", wall.deviceId())
                .update();

        JsonNode row = deviceRow(wall);

        assertThat(row.get("status").asString()).isEqualTo("REVOKED");
        assertThat(row.get("display").get("notSeen").asBoolean()).isFalse();
    }

    // ----------------------------------------------------------------------- helpers

    private record Begun(String userCode, String deviceCode) {}

    private record Enrolled(UUID deviceId, String subject, JsonNode response) {}

    private record Configured(MvcResult result) {

        void andExpectOk() throws Exception {
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(200);
        }
    }

    private Begun begin(String requestedClass) throws Exception {
        // The begin endpoint is rate limited per caller address (six a minute), and MockMvc gives every
        // request the same one: each device here comes from its own, like each TV on a real network.
        String address = "10.20." + (nextAddress++ / 250) + "." + (nextAddress % 250 + 1);
        MvcResult result = mvc.perform(post(ENROLMENTS)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceClass\":\"" + requestedClass + "\",\"label\":\"the grill TV\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return new Begun(body.get("userCode").asString(), body.get("deviceCode").asString());
    }

    private MvcResult approve(String userCode, String approvedClass) throws Exception {
        return mvc.perform(post(DEVICES + "/enrolments/" + userCode + "/approve")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Grill TV\",\"deviceClass\":\"" + approvedClass + "\"}"))
                .andReturn();
    }

    private Enrolled enrol(String requestedClass, String approvedClass) throws Exception {
        Begun begun = begin(requestedClass);
        MvcResult approved = approve(begun.userCode(), approvedClass);
        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode response = JSON.readTree(approved.getResponse().getContentAsString());
        UUID deviceId = UUID.fromString(response.get("deviceId").asString());

        MvcResult polled = mvc.perform(post(ENROLMENTS + "/" + begun.deviceCode() + "/poll"))
                .andReturn();
        assertThat(JSON.readTree(polled.getResponse().getContentAsString())
                        .get("status")
                        .asString())
                .isEqualTo("APPROVED");

        String subject = jdbc.sql("SELECT principal_subject FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(String.class)
                .single();
        return new Enrolled(deviceId, subject, response);
    }

    private List<String> rolesOf(String subject) {
        return jdbc.sql("""
                        SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject AND g.status = 'ACTIVE'
                        """).param("subject", subject).query(String.class).list();
    }

    private Configured configure(Enrolled wall, @Nullable UUID station, int version) throws Exception {
        MvcResult result = mvc.perform(put(DEVICES + "/" + wall.deviceId() + "/display")
                        .with(tokenFor(MANAGER))
                        .header("If-Match", "W/\"" + version + "\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cfg-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationId\":" + (station == null ? "null" : "\"" + station + "\"") + "}"))
                .andReturn();
        return new Configured(result);
    }

    private JsonNode vdu(String subject, @Nullable UUID station) throws Exception {
        var request = get(BASE + "/vdu").with(tokenFor(subject));
        if (station != null) {
            request = request.param("station", station.toString());
        }
        return ok(request);
    }

    /** {@code CODE:quantity} for every line the projection shows, by the station's own code. */
    private List<String> lines(JsonNode projection) {
        List<String> lines = new java.util.ArrayList<>();
        projection
                .get("tickets")
                .forEach(ticket -> ticket.get("items").forEach(item -> {
                    UUID station = UUID.fromString(item.get("stationId").asString());
                    String code = station.equals(GRILL) ? "GRILL" : station.equals(BAR) ? "BAR" : station.toString();
                    lines.add(code + ":" + item.get("quantity").asString());
                }));
        return lines;
    }

    private void readAt(Enrolled wall, Instant at) {
        jdbc.sql("UPDATE kitchen.device_displays SET last_read_at = :at WHERE device_id = :id")
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("id", wall.deviceId())
                .update();
    }

    private JsonNode deviceRow(Enrolled wall) throws Exception {
        for (JsonNode row : ok(get(DEVICES).with(tokenFor(MANAGER)))) {
            if (row.get("deviceId").asString().equals(wall.deviceId().toString())) {
                return row;
            }
        }
        throw new AssertionError("device not listed");
    }

    private double counter(String outcome) {
        var found = meters.find("horecaos.kitchen.display.reads")
                .tag("outcome", outcome)
                .counter();
        return found == null ? 0.0 : found.count();
    }

    private void author(
            String scopeType,
            @Nullable UUID locationId,
            int atRisk,
            int lateAfter,
            int fallback,
            @Nullable Integer expectedVersion,
            String reason)
            throws Exception {
        String mode = "{\"atRiskBeforeSeconds\":" + atRisk + ",\"lateAfterSeconds\":" + lateAfter
                + ",\"noPromiseFallbackSeconds\":" + fallback + "}";
        String body = "{\"scopeType\":\"" + scopeType + "\","
                + "\"brandId\":" + (locationId == null ? "null" : "\"" + BRAND + "\"") + ","
                + "\"locationId\":" + (locationId == null ? "null" : "\"" + locationId + "\"") + ","
                + "\"delivery\":" + mode + ",\"pickup\":" + mode + ",\"dineIn\":" + mode + ","
                + "\"expectedVersion\":" + expectedVersion + ",\"reason\":\"" + reason + "\"}";
        MvcResult result = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/order-lateness-policy")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lat-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void setLateColour(String colour) throws Exception {
        MvcResult result = mvc.perform(
                        post("/api/v1/operations/tenants/" + TENANT + "/configuration/keys/ordering.late_colour/values")
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "col-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"scopeType\":\"TENANT\",\"stringValue\":\"" + colour
                                        + "\",\"explicitNull\":false,\"reason\":\"our alarm colour\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static TicketRow ticket(UUID id, UUID orderId, String label) {
        Instant now = Instant.now();
        return new TicketRow(
                id,
                TENANT,
                BRAND,
                LOCATION,
                orderId,
                label,
                "PICKUP",
                "TELEGRAM",
                TicketStatus.FIRED,
                ReleaseMode.AUTO_ON_CONFIRM,
                null,
                now,
                null,
                now.plusSeconds(600),
                null,
                null,
                null,
                1,
                1,
                now);
    }

    private static TicketItemRow item(UUID ticketId, UUID station, int quantity) {
        return new TicketItemRow(
                UUID.randomUUID(),
                TENANT,
                ticketId,
                LOCATION,
                UUID.randomUUID(),
                station,
                BigDecimal.valueOf(quantity),
                RoutingLevel.BRAND_ROLE,
                TicketItemStatus.QUEUED,
                null,
                null,
                null,
                1,
                Instant.now());
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'wall-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (Object[] location :
                new Object[][] {{LOCATION, "CHI", "chilanzar", "Chilanzar"}, {SIBLING, "YUN", "yunusobod", "Yunusobod"}
                }) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location[0])
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", location[1])
                    .param("slug", location[2])
                    .param("name", location[3])
                    .update();
        }
        station(GRILL, LOCATION, "GRILL", "GRILL", "Гриль", "Gril", "Grill");
        station(BAR, LOCATION, "BAR", "BAR", "Бар", "Bar", "Bar");
        station(SIBLINGS_STATION, SIBLING, "GRILL", "GRILL", "Гриль", "Gril", "Grill");
    }

    private void station(UUID id, UUID location, String code, String role, String ru, String uz, String en) {
        jdbc.sql("""
                INSERT INTO kitchen.stations
                    (id, tenant_id, brand_id, location_id, code, role, display_name_ru, display_name_uz,
                     display_name_en, sort_order, is_fallback, status, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :code, :role, :ru, :uz, :en, 0, false, 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("code", code)
                .param("role", role)
                .param("ru", ru)
                .param("uz", uz)
                .param("en", en)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'kitchen wall display endpoint test', :validFrom)
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
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", Set.of()))));
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

        /** The Keycloak half of enrolment is proven against a real realm elsewhere; this is its in-memory twin. */
        @Bean
        @Primary
        DeviceClientProvisioner fakeDeviceClientProvisioner() {
            return new FakeDeviceClientProvisioner();
        }
    }
}
