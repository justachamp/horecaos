package uz.horecaos.platform.dinein.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.infrastructure.security.PresetVerificationCodeSource;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.FloorPlanService.WalkInChange;
import uz.horecaos.platform.dinein.application.ReservationService;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A guest seats themselves at a free table, through the real HTTP stack (ADR 0143).
 *
 * <p>{@code WalkInSeatingTests} proves the rules against the services and a real
 * PostgreSQL. What only HTTP can prove is what this class is for: the two credentials
 * the route takes and refuses without, the one answer every "no" gives, the table never
 * being an input, the staff surface's declared capability and its branch in the path,
 * and the settings write's {@code If-Match}.
 *
 * <p>The guest side signs in through the real OTP journey rather than a {@code jwt()}
 * shortcut, because the route's second credential is the customer's own session and a
 * shortcut would step over the resolver that reads it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class WalkInSeatingHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT_A = UUID.fromString("018fd900-4000-7000-8000-0000000000e1");
    private static final UUID BRAND_A = UUID.fromString("018fd900-4000-7000-8000-0000000000f1");
    private static final UUID LOCATION_A1 = UUID.fromString("018fd900-4000-7000-8000-0000000000d1");
    private static final UUID LOCATION_A2 = UUID.fromString("018fd900-4000-7000-8000-0000000000d2");

    private static final UUID TENANT_B = UUID.fromString("018fd900-4000-7000-8000-0000000000e2");
    private static final UUID BRAND_B = UUID.fromString("018fd900-4000-7000-8000-0000000000f2");
    private static final UUID LOCATION_B1 = UUID.fromString("018fd900-4000-7000-8000-0000000000d3");

    /** Holds the dine-in session and floor-plan capabilities at branch A1 only. */
    private static final String MANAGER_A1 = "walkin-http-manager-a1";

    /** The waiter: seats and closes tables at A1, but is not trusted with the floor plan's settings. */
    private static final String STAFF_A1 = "walkin-http-staff-a1";

    private static final String MANAGER_A2 = "walkin-http-manager-a2";

    /** A brand manager: sees orders across the brand, holds no dine-in capability at all. */
    private static final String BRAND_MANAGER_A = "walkin-http-brand-manager-a";

    private static final String MANAGER_B1 = "walkin-http-manager-b1";

    private static final String PRESET_PHONE = "+998000000000";
    private static final String PRESET_CODE = "000000";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the walk-in HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        // The sweeper is exercised against the services in WalkInSeatingTests; here it would
        // only race the assertions about a claim that has not lapsed yet.
        registry.add("horecaos.dinein.claim-sweeper.enabled", () -> "false");
        registry.add(PresetVerificationCodeSource.PHONE_PROPERTY, () -> PRESET_PHONE);
        registry.add(PresetVerificationCodeSource.CODE_PROPERTY, () -> PRESET_CODE);
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
    private FloorPlanService floorPlan;

    @Autowired
    @SuppressWarnings("NullAway")
    private ReservationService reservations;

    private UUID channelA = UUID.randomUUID();
    private UUID channelB = UUID.randomUUID();
    private UUID sectionA1 = UUID.randomUUID();
    private UUID sectionA2 = UUID.randomUUID();
    private UUID sectionB1 = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE dinein.session_orders, dinein.session_tables, "
                        + "dinein.table_sessions, dinein.reservation_tables, dinein.reservations, "
                        + "dinein.qr_guest_sessions, dinein.tables, dinein.sections, "
                        + "dinein.location_settings CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        channelA = UUID.randomUUID();
        channelB = UUID.randomUUID();
        seedTenant(TENANT_A, BRAND_A, "walkin-a", channelA);
        seedTenant(TENANT_B, BRAND_B, "walkin-b", channelB);
        seedLocation(TENANT_A, BRAND_A, LOCATION_A1, "CENTRE");
        seedLocation(TENANT_A, BRAND_A, LOCATION_A2, "NORTH");
        seedLocation(TENANT_B, BRAND_B, LOCATION_B1, "CENTRE");
        sectionA1 = section(TENANT_A, BRAND_A, LOCATION_A1);
        sectionA2 = section(TENANT_A, BRAND_A, LOCATION_A2);
        sectionB1 = section(TENANT_B, BRAND_B, LOCATION_B1);

        grant(MANAGER_A1, PlatformRole.LOCATION_MANAGER, "LOCATION", TENANT_A, LOCATION_A1);
        grant(STAFF_A1, PlatformRole.LOCATION_STAFF, "LOCATION", TENANT_A, LOCATION_A1);
        grant(MANAGER_A2, PlatformRole.LOCATION_MANAGER, "LOCATION", TENANT_A, LOCATION_A2);
        grant(BRAND_MANAGER_A, PlatformRole.BRAND_MANAGER, "BRAND", TENANT_A, BRAND_A);
        grant(MANAGER_B1, PlatformRole.LOCATION_MANAGER, "LOCATION", TENANT_B, LOCATION_B1);
    }

    // ------------------------------------------------------------------ the guest route

    @Test
    @DisplayName("a signed-in guest at a free table sits down: a claim, with its window, that occupies the table")
    void aGuestSitsDown() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();

        MvcResult seated = seat(table.guestToken, customer.token(), 2);

        assertThat(seated.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(seated);
        assertThat(body.path("created").asBoolean()).isTrue();
        assertThat(body.path("origin").asText()).isEqualTo("GUEST_QR");
        assertThat(body.path("status").asText()).isEqualTo("OPEN");
        assertThat(body.path("confirmed").asBoolean()).isFalse();
        assertThat(body.path("claimExpiresAt").isNull()).isFalse();
        assertThat(body.path("totalMinor").asLong()).isZero();
        assertThat(body.path("roundCount").asInt()).isZero();

        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE origin = 'GUEST_QR' "
                        + "AND opened_by_account_id = '" + customer.accountId() + "'"))
                .as("the claim names the signed-in customer, from their session and no one's word")
                .isEqualTo(1);
        assertThat(exchange(table.printed).path("openSessionId").asText())
                .as("a second scan of the table now finds the claim")
                .isEqualTo(body.path("sessionId").asText());
    }

    @Test
    @DisplayName("a second guest at the same table is handed the live session back, not a second one")
    void aSecondGuestGetsTheSessionBack() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        String sessionId = json(seat(table.guestToken, customer.token(), 2))
                .path("sessionId")
                .asText();

        JsonNode again = json(seat(table.otherGuestToken(), customer.token(), 3));

        assertThat(again.path("created").asBoolean()).isFalse();
        assertThat(again.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(count("SELECT count(*) FROM dinein.table_sessions")).isEqualTo(1);
    }

    @Test
    @DisplayName("without the customer's own session the route is refused, however good the table's token")
    void theCustomerSessionIsRequired() throws Exception {
        Fixture table = freeTable("T1", 4);

        MvcResult withoutOne = mvc.perform(post(sessionsPath())
                        .header("X-Dine-In-Token", table.guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":2}"))
                .andReturn();

        assertThat(withoutOne.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(withoutOne).path("reason").asText())
                .as("a client can tell 'sign in' from 'scan again'")
                .isEqualTo("CUSTOMER_SESSION_REQUIRED");
        assertThat(count("SELECT count(*) FROM dinein.table_sessions")).isZero();
    }

    @Test
    @DisplayName("without the table's guest token the route is refused; a dead token is a 401, a menu-only code a 404")
    void theGuestTokenIsRequired() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();

        MvcResult missing = mvc.perform(post(sessionsPath())
                        .with(session(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":2}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);

        MvcResult dead = seat("not-a-token", customer.token(), 2);
        assertThat(dead.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(dead).path("reason").asText()).isEmpty();

        // Rotating the printed code revokes every guest token minted from it.
        rotate(table.table);
        MvcResult rotated = seat(table.guestToken, customer.token(), 2);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM dinein.table_sessions")).isZero();
    }

    @Test
    @DisplayName("a VIEW_ONLY branch refuses the route the way it refuses every ordering route")
    void viewOnlyRefuses() throws Exception {
        TableRow table = createTable(TENANT_A, BRAND_A, LOCATION_A1, sectionA1, "T1", 4);
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT_A, BRAND_A, LOCATION_A1, "VIEW_ONLY", null, null, null),
                "manager",
                "Menu only");
        String guest = exchange(rotate(table)).path("guestToken").asText();
        SignedIn customer = signIn();

        assertThat(seat(guest, customer.token(), 2).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("every reason a table cannot be taken is the same 409, with no reason in it")
    void everyRefusalIsTheSameAnswer() throws Exception {
        SignedIn customer = signIn();

        // Switched off.
        Fixture off = tableWith("T1", 4, new WalkInChange(false, null, null, null, null, null, null));
        MvcResult switchedOff = seat(off.guestToken, customer.token(), 2);

        // Held by a confirmed booking inside the horizon.
        Fixture held = tableWith("T2", 4, new WalkInChange(true, null, null, null, null, null, null));
        confirmBooking(held.table, Instant.now().plus(Duration.ofMinutes(30)));
        MvcResult heldByBooking = seat(held.guestToken, customer.token(), 2);

        // The branch cap is zero.
        Fixture capped = tableWith("T3", 4, new WalkInChange(true, null, null, 0, null, null, null));
        MvcResult atTheCap = seat(capped.guestToken, customer.token(), 2);

        for (MvcResult refusal : List.of(switchedOff, heldByBooking, atTheCap)) {
            assertThat(refusal.getResponse().getStatus()).isEqualTo(409);
            JsonNode body = json(refusal);
            assertThat(body.path("conflict").asText()).isEqualTo("TABLE_NOT_AVAILABLE");
            assertThat(body.toString().toLowerCase())
                    .as("no reason, no time, no guest, no booking")
                    .doesNotContain("booking", "cap", "blacklist", "horizon", "switched");
        }
        assertThat(json(switchedOff).path("detail").asText())
                .isEqualTo(json(heldByBooking).path("detail").asText())
                .isEqualTo(json(atTheCap).path("detail").asText());
        assertThat(count("SELECT count(*) FROM dinein.table_sessions")).isZero();
    }

    @Test
    @DisplayName("a party larger than the table is a 400 that names the seats; a missing or zero party is refused too")
    void capacityAndPartyValidation() throws Exception {
        Fixture table = tableWith("T1", 4, new WalkInChange(true, null, null, null, null, null, null));
        SignedIn customer = signIn();

        MvcResult tooMany = seat(table.guestToken, customer.token(), 5);
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(tooMany).path("seats").asInt()).isEqualTo(4);

        for (String body : List.of("{}", "{\"partySize\":0}", "{\"partySize\":-1}", "{\"partySize\":201}")) {
            MvcResult refused = mvc.perform(post(sessionsPath())
                            .header("X-Dine-In-Token", table.guestToken)
                            .with(session(customer.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as(body).isEqualTo(400);
        }
        assertThat(count("SELECT count(*) FROM dinein.table_sessions")).isZero();

        assertThat(seat(table.guestToken, customer.token(), 4).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("the table, the location and the tenant are never inputs: naming another table's changes nothing")
    void theTableIsNeverAnInput() throws Exception {
        Fixture mine = tableWith("T1", 4, new WalkInChange(true, null, null, null, null, null, null));
        TableRow neighbour = createTable(TENANT_A, BRAND_A, LOCATION_A1, sectionA1, "T2", 4);
        TableRow foreign = createTable(TENANT_B, BRAND_B, LOCATION_B1, sectionB1, "N1", 4);
        SignedIn customer = signIn();

        MvcResult seated = mvc.perform(post(sessionsPath())
                        .header("X-Dine-In-Token", mine.guestToken)
                        .with(session(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":2,\"tableId\":\"" + neighbour.id() + "\",\"tableIds\":[\""
                                + foreign.id() + "\"],\"locationId\":\"" + LOCATION_A2 + "\",\"tenantId\":\""
                                + TENANT_B + "\"}"))
                .andReturn();

        assertThat(seated.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM dinein.session_tables WHERE table_id = '" + mine.table.id() + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dinein.session_tables WHERE table_id IN ('" + neighbour.id() + "', '"
                        + foreign.id() + "')"))
                .as("the guest's own table is the only table the claim sits at")
                .isZero();
    }

    @Test
    @DisplayName("the per-token limit answers a sixth open inside a minute with a 429")
    void theRouteIsRateLimitedPerToken() throws Exception {
        Fixture off = tableWith("T1", 4, new WalkInChange(false, null, null, null, null, null, null));
        SignedIn customer = signIn();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(seat(off.guestToken, customer.token(), 2).getResponse().getStatus())
                    .isEqualTo(409);
        }
        MvcResult limited = seat(off.guestToken, customer.token(), 2);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(seat(off.otherGuestToken(), customer.token(), 2)
                        .getResponse()
                        .getStatus())
                .as("a different token is not affected")
                .isEqualTo(409);
    }

    @Test
    @DisplayName("the exchange tells a token holder whether they could sit down, and the answer follows the room")
    void theExchangeReportsWalkInAvailable() throws Exception {
        TableRow table = createTable(TENANT_A, BRAND_A, LOCATION_A1, sectionA1, "T1", 4);
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT_A, BRAND_A, LOCATION_A1, "ORDER_AND_PAY", null, null, null),
                "manager",
                "QR ordering on");
        String printed = rotate(table);
        assertThat(exchange(printed).path("walkInAvailable").asBoolean())
                .as("the switch is off")
                .isFalse();

        configure(new WalkInChange(true, null, null, null, null, null, null));
        assertThat(exchange(printed).path("walkInAvailable").asBoolean()).isTrue();

        SignedIn customer = signIn();
        seat(exchange(printed).path("guestToken").asText(), customer.token(), 2);
        JsonNode occupied = exchange(printed);
        assertThat(occupied.path("walkInAvailable").asBoolean()).isFalse();
        assertThat(occupied.path("openSessionId").isNull()).isFalse();
    }

    // ----------------------------------------------- the claim, as the guest then meets it

    @Test
    @DisplayName("a guest cannot ask for the bill of a claim nothing has confirmed; once staff keep it, they can")
    void noBillForAnUnconfirmedClaim() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        JsonNode seated = json(seat(table.guestToken, customer.token(), 2));
        String sessionId = seated.path("sessionId").asText();

        MvcResult refused = mvc.perform(post(billRequestPath(sessionId)).header("X-Dine-In-Token", table.guestToken))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(refused).path("conflict").asText()).isEqualTo("CLAIM_UNCONFIRMED");
        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE status = 'BILL_REQUESTED'"))
                .isZero();

        MvcResult bill = mvc.perform(get(billPath(sessionId)).header("X-Dine-In-Token", table.guestToken))
                .andReturn();
        assertThat(json(bill).path("origin").asText()).isEqualTo("GUEST_QR");
        assertThat(json(bill).path("confirmed").asBoolean()).isFalse();
        assertThat(json(bill).path("claimExpiresAt").isNull()).isFalse();

        MvcResult confirmed = confirmClaim(MANAGER_A1, sessionId, 1, "Guest is at the bar");
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);

        MvcResult asked = mvc.perform(post(billRequestPath(sessionId)).header("X-Dine-In-Token", table.guestToken))
                .andReturn();
        assertThat(asked.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(asked).path("status").asText()).isEqualTo("BILL_REQUESTED");
        assertThat(json(asked).path("confirmed").asBoolean()).isTrue();
        assertThat(json(asked).path("claimExpiresAt").isNull()).isTrue();
    }

    // -------------------------------------------------------------- the staff surface

    @Test
    @DisplayName("the live list shows a self-seated table's origin and window, and never who sat down")
    void theLiveListShowsClaims() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        seat(table.guestToken, customer.token(), 2);
        openStaffSession(
                STAFF_A1,
                createTable(TENANT_A, BRAND_A, LOCATION_A1, sectionA1, "T2", 4).id());

        MvcResult live = mvc.perform(get(staffSessionsPath(LOCATION_A1)).with(tokenFor(MANAGER_A1)))
                .andReturn();

        assertThat(live.getResponse().getStatus()).isEqualTo(200);
        JsonNode rows = json(live);
        assertThat(rows).hasSize(2);
        JsonNode claim = rowWithOrigin(rows, "GUEST_QR");
        JsonNode staffRow = rowWithOrigin(rows, "STAFF");
        assertThat(claim.path("claimExpiresAt").isNull()).isFalse();
        assertThat(claim.path("confirmedAt").isNull()).isTrue();
        assertThat(staffRow.path("origin").asText()).isEqualTo("STAFF");
        assertThat(staffRow.path("claimExpiresAt").isNull()).isTrue();
        assertThat(live.getResponse().getContentAsString())
                .doesNotContain(customer.accountId().toString())
                .doesNotContain("guest:");
    }

    @Test
    @DisplayName(
            "keeping a claim needs the session capability at that branch, an If-Match, and answers a second time with a conflict")
    void confirmingAClaim() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        String sessionId = json(seat(table.guestToken, customer.token(), 2))
                .path("sessionId")
                .asText();

        assertThat(confirmClaim(MANAGER_A2, sessionId, 1, "Not my branch")
                        .getResponse()
                        .getStatus())
                .as("a manager of another branch")
                .isEqualTo(403);
        assertThat(confirmClaim(MANAGER_B1, sessionId, 1, "Not my tenant")
                        .getResponse()
                        .getStatus())
                .as("a manager of another tenant")
                .isEqualTo(403);
        assertThat(confirmClaim(BRAND_MANAGER_A, sessionId, 1, "No dine-in grant")
                        .getResponse()
                        .getStatus())
                .as("a brand manager holds no dine-in capability")
                .isEqualTo(403);

        MvcResult noPrecondition = mvc.perform(
                        post(staffSessionsPath(LOCATION_A1) + "/" + sessionId + "/claim-confirmations")
                                .with(tokenFor(MANAGER_A1))
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"No version\"}"))
                .andReturn();
        assertThat(noPrecondition.getResponse().getStatus()).isEqualTo(400);

        assertThat(confirmClaim(MANAGER_A1, sessionId, 7, "Stale").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE confirmed_at IS NOT NULL"))
                .isZero();

        MvcResult confirmed = confirmClaim(STAFF_A1, sessionId, 1, "Guest is at the bar");
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(confirmed).path("confirmedAt").isNull()).isFalse();
        assertThat(json(confirmed).path("version").asInt()).isEqualTo(2);

        MvcResult again = confirmClaim(STAFF_A1, sessionId, 2, "Again");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).path("conflict").asText()).isEqualTo("NOT_AN_UNCONFIRMED_CLAIM");
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'dinein.session.claim-confirmed' "
                        + "AND target_id = '" + sessionId + "'"))
                .as("one audit fact for the one confirmation")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("closing a claim through the state-action releases the table and ends the guest's token")
    void closingAClaimReleasesTheTable() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        String sessionId = json(seat(table.guestToken, customer.token(), 2))
                .path("sessionId")
                .asText();

        MvcResult closed = mvc.perform(post(staffSessionsPath(LOCATION_A1) + "/" + sessionId + "/state-actions")
                        .with(tokenFor(STAFF_A1))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CLOSED\",\"reason\":\"Nobody came\"}"))
                .andReturn();

        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(closed).path("status").asText()).isEqualTo("CLOSED");
        assertThat(json(closed).path("confirmedAt").isNull()).isTrue();
        assertThat(exchange(table.printed).path("openSessionId").isNull())
                .as("the table is back in the room")
                .isTrue();
        assertThat(seat(table.guestToken, customer.token(), 2).getResponse().getStatus())
                .as("closing the claim ended the guest token minted at its table")
                .isEqualTo(401);
    }

    @Test
    @DisplayName(
            "staff move a claim to the bill: someone has taken charge, so it is confirmed and the guest may ask too")
    void aStaffMovePastOpenConfirms() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        String sessionId = json(seat(table.guestToken, customer.token(), 2))
                .path("sessionId")
                .asText();

        MvcResult moved = mvc.perform(post(staffSessionsPath(LOCATION_A1) + "/" + sessionId + "/state-actions")
                        .with(tokenFor(STAFF_A1))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"BILL_REQUESTED\",\"reason\":\"Table asked at the till\"}"))
                .andReturn();

        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(moved).path("confirmedAt").isNull()).isFalse();
        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE confirmed_by = '" + STAFF_A1 + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "confirming a booking over a table somebody sits at reports tableOccupiedNow; reading bookings does not")
    void confirmingOverAClaimReportsOccupancy() throws Exception {
        Fixture table = freeTable("T1", 4);
        SignedIn customer = signIn();
        seat(table.guestToken, customer.token(), 2);
        UUID booking = reservations
                .request(new ReservationService.NewReservation(
                        TENANT_A,
                        BRAND_A,
                        LOCATION_A1,
                        null,
                        "Dilnoza",
                        "998901234567",
                        null,
                        null,
                        4,
                        Instant.now().plus(Duration.ofMinutes(30)),
                        Instant.now().plus(Duration.ofHours(2)),
                        List.of(table.table.id()),
                        channelA,
                        "host"))
                .id();

        MvcResult confirmed = mvc.perform(post(reservationsPath(LOCATION_A1) + "/" + booking + "/state-actions")
                        .with(tokenFor(MANAGER_A1))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CONFIRMED\",\"reason\":\"Table available\"}"))
                .andReturn();

        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(confirmed).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(json(confirmed).path("tableOccupiedNow").asBoolean())
                .as("the party stays; the host is told")
                .isTrue();

        MvcResult read = mvc.perform(
                        get(reservationsPath(LOCATION_A1) + "/" + booking).with(tokenFor(MANAGER_A1)))
                .andReturn();
        assertThat(json(read).path("tableOccupiedNow").isNull()).isTrue();
    }

    // ------------------------------------------------------------------- settings

    @Test
    @DisplayName(
            "the settings write needs If-Match, reads a never-configured branch as version 0, and keeps self-seating off until asked")
    void settingsAreVersionedAndShipOff() throws Exception {
        MvcResult fresh = mvc.perform(get(settingsPath(LOCATION_A1)).with(tokenFor(MANAGER_A1)))
                .andReturn();
        assertThat(fresh.getResponse().getStatus()).isEqualTo(200);
        JsonNode defaults = json(fresh);
        assertThat(defaults.path("version").asInt()).isZero();
        assertThat(defaults.path("walkInSelfSeat").asBoolean()).isFalse();
        assertThat(defaults.path("walkInClaimTtlMinutes").asInt()).isEqualTo(15);
        assertThat(defaults.path("walkInHorizonMinutes").asInt()).isEqualTo(90);
        assertThat(defaults.path("walkInMaxUnconfirmed").asInt()).isEqualTo(5);
        assertThat(defaults.path("walkInDailyClaimsPerAccount").asInt()).isEqualTo(3);
        assertThat(defaults.path("walkInPaymentDeferMinutes").asInt()).isEqualTo(30);
        assertThat(defaults.path("sessionCurrency").asText()).isEqualTo("UZS");

        String body = "{\"qrMode\":\"ORDER_AND_PAY\",\"walkInSelfSeat\":true,\"walkInClaimTtlMinutes\":20,"
                + "\"walkInMaxUnconfirmed\":3,\"reason\":\"Pilot at this branch\"}";
        assertThat(putSettings(MANAGER_A1, LOCATION_A1, null, body)
                        .getResponse()
                        .getStatus())
                .as("no If-Match")
                .isEqualTo(400);
        assertThat(putSettings(MANAGER_A1, LOCATION_A1, 4, body).getResponse().getStatus())
                .as("a guess at a version that does not exist")
                .isEqualTo(409);
        assertThat(putSettings(STAFF_A1, LOCATION_A1, 0, body).getResponse().getStatus())
                .as("the waiter does not hold the floor-plan capability")
                .isEqualTo(403);
        assertThat(putSettings(MANAGER_A2, LOCATION_A1, 0, body).getResponse().getStatus())
                .as("another branch's manager")
                .isEqualTo(403);
        assertThat(count("SELECT count(*) FROM dinein.location_settings")).isZero();

        MvcResult saved = putSettings(MANAGER_A1, LOCATION_A1, 0, body);
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        JsonNode after = json(saved);
        assertThat(after.path("version").asInt()).isEqualTo(1);
        assertThat(after.path("walkInSelfSeat").asBoolean()).isTrue();
        assertThat(after.path("walkInClaimTtlMinutes").asInt()).isEqualTo(20);
        assertThat(after.path("walkInMaxUnconfirmed").asInt()).isEqualTo(3);
        assertThat(after.path("walkInHorizonMinutes").asInt())
                .as("omitted fields keep their value")
                .isEqualTo(90);

        assertThat(putSettings(MANAGER_A1, LOCATION_A1, 0, body).getResponse().getStatus())
                .as("the second manager with the version both read")
                .isEqualTo(409);

        // Turning it off again, naming only the switch.
        MvcResult off = putSettings(
                MANAGER_A1,
                LOCATION_A1,
                1,
                "{\"qrMode\":\"ORDER_AND_PAY\",\"walkInSelfSeat\":false,\"reason\":\"Pilot over\"}");
        assertThat(json(off).path("walkInSelfSeat").asBoolean()).isFalse();
        assertThat(json(off).path("walkInClaimTtlMinutes").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("out-of-range self-seating numbers are refused before anything is written")
    void settingsRangesAreEnforced() throws Exception {
        for (String field : List.of(
                "\"walkInClaimTtlMinutes\":1",
                "\"walkInClaimTtlMinutes\":61",
                "\"walkInHorizonMinutes\":481",
                "\"walkInMaxUnconfirmed\":101",
                "\"walkInDailyClaimsPerAccount\":0",
                "\"walkInDailyClaimsPerAccount\":21",
                "\"walkInPaymentDeferMinutes\":121",
                "\"sessionCurrency\":\"uzs\"")) {
            MvcResult refused = putSettings(
                    MANAGER_A1,
                    LOCATION_A1,
                    0,
                    "{\"qrMode\":\"ORDER_AND_PAY\"," + field + ",\"reason\":\"Out of range\"}");
            assertThat(refused.getResponse().getStatus()).as(field).isEqualTo(400);
        }
        assertThat(count("SELECT count(*) FROM dinein.location_settings")).isZero();
    }

    // ----------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("tenants are separate: tenant B's guest seats itself at B's table only, and A's staff never see it")
    void tenantIsolation() throws Exception {
        Fixture mine = freeTable("T1", 4);
        floorPlan.configure(
                new FloorPlanService.BranchSettings(
                        TENANT_B,
                        BRAND_B,
                        LOCATION_B1,
                        "ORDER_AND_PAY",
                        null,
                        null,
                        null,
                        new WalkInChange(true, null, null, null, null, null, null)),
                "manager",
                "Self-seating");
        TableRow theirs = createTable(TENANT_B, BRAND_B, LOCATION_B1, sectionB1, "N1", 4);
        String theirGuest = exchange(rotate(theirs)).path("guestToken").asText();
        SignedIn customer = signIn();

        // The customer is signed in at tenant A only: their session is no proof of anyone at B.
        MvcResult atB = seat(theirGuest, customer.token(), 2);

        assertThat(atB.getResponse().getStatus())
                .as("an account of tenant A does not exist at tenant B")
                .isEqualTo(401);
        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE tenant_id = '" + TENANT_B + "'"))
                .isZero();
        assertThat(seat(mine.guestToken, customer.token(), 2).getResponse().getStatus())
                .isEqualTo(200);

        MvcResult theirList = mvc.perform(
                        get(staffSessionsPath(TENANT_B, BRAND_B, LOCATION_B1)).with(tokenFor(MANAGER_B1)))
                .andReturn();
        assertThat(json(theirList)).isEmpty();
        MvcResult crossTenant = mvc.perform(
                        get(staffSessionsPath(TENANT_A, BRAND_A, LOCATION_A1)).with(tokenFor(MANAGER_B1)))
                .andReturn();
        assertThat(crossTenant.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    /** A table with the guest's printed code, a guest token minted from it, and self-seating on. */
    private Fixture freeTable(String code, int seats) throws Exception {
        return tableWith(code, seats, new WalkInChange(true, null, null, null, null, null, null));
    }

    private Fixture tableWith(String code, int seats, WalkInChange change) throws Exception {
        TableRow table = createTable(TENANT_A, BRAND_A, LOCATION_A1, sectionA1, code, seats);
        configure(change);
        String printed = rotate(table);
        return new Fixture(
                this, table, printed, exchange(printed).path("guestToken").asText());
    }

    private record Fixture(WalkInSeatingHttpTests test, TableRow table, String printed, String guestToken) {

        String otherGuestToken() throws Exception {
            return test.exchange(printed).path("guestToken").asText();
        }
    }

    private void configure(WalkInChange change) {
        floorPlan.configure(
                new FloorPlanService.BranchSettings(
                        TENANT_A, BRAND_A, LOCATION_A1, "ORDER_AND_PAY", null, null, null, change),
                "manager",
                "Self-seating");
    }

    private void confirmBooking(TableRow table, Instant from) {
        UUID booking = reservations
                .request(new ReservationService.NewReservation(
                        TENANT_A,
                        BRAND_A,
                        LOCATION_A1,
                        null,
                        "Dilnoza",
                        "998901234567",
                        null,
                        null,
                        4,
                        from,
                        from.plus(Duration.ofHours(2)),
                        List.of(table.id()),
                        channelA,
                        "host"))
                .id();
        reservations.move(TENANT_A, LOCATION_A1, booking, ReservationStatus.CONFIRMED, 1, "host", "Table available");
    }

    /** Issues or rotates the table's code, against the version the table has now. */
    private String rotate(TableRow table) {
        Integer version = jdbc.sql("SELECT version FROM dinein.tables WHERE id = :id")
                .param("id", table.id())
                .query(Integer.class)
                .single();
        return floorPlan
                .rotateQrToken(table.tenantId(), table.locationId(), table.id(), version, "manager", "Printing")
                .plaintext();
    }

    private JsonNode exchange(String printed) throws Exception {
        MvcResult exchanged = mvc.perform(post("/api/v1/storefront/dine-in/qr/token-exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + printed + "\"}"))
                .andReturn();
        assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
        return json(exchanged);
    }

    private MvcResult seat(String guestToken, String customerToken, int partySize) throws Exception {
        return mvc.perform(post(sessionsPath())
                        .header("X-Dine-In-Token", guestToken)
                        .with(session(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":" + partySize + "}"))
                .andReturn();
    }

    private MvcResult confirmClaim(String subject, String sessionId, int version, String reason) throws Exception {
        return mvc.perform(post(staffSessionsPath(LOCATION_A1) + "/" + sessionId + "/claim-confirmations")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    private MvcResult openStaffSession(String subject, UUID tableId) throws Exception {
        return mvc.perform(post(staffSessionsPath(LOCATION_A1))
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableIds\":[\"" + tableId + "\"],\"partySize\":3,\"currency\":\"UZS\","
                                + "\"reason\":\"Walk-in seated at the door\"}"))
                .andReturn();
    }

    private static JsonNode rowWithOrigin(JsonNode rows, String origin) {
        for (JsonNode row : rows) {
            if (origin.equals(row.path("origin").asText())) {
                return row;
            }
        }
        throw new AssertionError("no " + origin + " row in " + rows);
    }

    private MvcResult putSettings(String subject, UUID location, @Nullable Integer version, String body)
            throws Exception {
        var request = put(settingsPath(location))
                .with(tokenFor(subject))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (version != null) {
            request = request.header("If-Match", "\"" + version + "\"");
        }
        return mvc.perform(request).andReturn();
    }

    /** The whole journey: ask for a code, type it, exchange the grant (ADR 0051). */
    private SignedIn signIn() throws Exception {
        String identity = "/api/v1/storefront/tenants/" + TENANT_A + "/brands/" + BRAND_A + "/identity";
        MvcResult challenge = mvc.perform(post(identity + "/verification-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PRESET_PHONE + "\"}"))
                .andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(202);
        String challengeId = json(challenge).path("challengeId").asText();

        MvcResult attempt = mvc.perform(post(identity + "/verification-challenges/" + challengeId + "/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + PRESET_CODE + "\"}"))
                .andReturn();
        assertThat(attempt.getResponse().getStatus()).isEqualTo(200);
        String grant = json(attempt).path("grant").asText();

        MvcResult session = mvc.perform(post(identity + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":\"" + grant + "\"}"))
                .andReturn();
        assertThat(session.getResponse().getStatus()).isIn(200, 201);
        JsonNode body = json(session);
        return new SignedIn(
                body.path("token").asText(),
                UUID.fromString(body.path("accountId").asText()));
    }

    private record SignedIn(String token, UUID accountId) {}

    /** The customer's own credential, in the header a browser sends it in (ADR 0051). */
    private static RequestPostProcessor session(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private static String sessionsPath() {
        return "/api/v1/storefront/dine-in/sessions";
    }

    private static String billPath(String sessionId) {
        return sessionsPath() + "/" + sessionId;
    }

    private static String billRequestPath(String sessionId) {
        return sessionsPath() + "/" + sessionId + "/bill-requests";
    }

    private static String staffSessionsPath(UUID location) {
        return staffSessionsPath(TENANT_A, BRAND_A, location);
    }

    private static String staffSessionsPath(UUID tenant, UUID brand, UUID location) {
        return "/api/v1/tenants/" + tenant + "/brands/" + brand + "/locations/" + location + "/dine-in/sessions";
    }

    private static String settingsPath(UUID location) {
        return "/api/v1/tenants/" + TENANT_A + "/brands/" + BRAND_A + "/locations/" + location + "/dine-in/settings";
    }

    private static String reservationsPath(UUID location) {
        return "/api/v1/tenants/" + TENANT_A + "/brands/" + BRAND_A + "/locations/" + location + "/reservations";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private long count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0L : value;
    }

    private TableRow createTable(UUID tenant, UUID brand, UUID location, UUID section, String code, int seats) {
        return floorPlan.createTable(
                new FloorPlanService.NewTable(tenant, brand, location, section, code, code, seats, false, null, null));
    }

    private UUID section(UUID tenant, UUID brand, UUID location) {
        return floorPlan
                .createSection(new FloorPlanService.NewSection(tenant, brand, location, "HALL", "Hall", 0))
                .id();
    }

    private void seedTenant(UUID tenantId, UUID brandId, String slug, UUID channelId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from)
                VALUES (:id, :t, 1, 'TENANT_SHARED', TIMESTAMPTZ '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes(tenantId.toString().getBytes(UTF_8)))
                .param("t", tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
                """).param("id", channelId).param("t", tenantId).update();
    }

    private void seedLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID tenantId, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'walk-in http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
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
}
