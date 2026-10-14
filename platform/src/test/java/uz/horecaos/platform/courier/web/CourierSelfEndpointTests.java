package uz.horecaos.platform.courier.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * The courier's own surface, through the real HTTP stack (ADR 0179, ADR 0045, ADR 0049): who the
 * platform says the caller is, going on and off duty, and sending a position.
 *
 * <p>Every courier here is a Keycloak subject and nothing else. Nothing sets a duty session, a
 * position or a track by hand: a shift is opened by the courier over HTTP, a session by the
 * courier over HTTP, and a position by the courier over HTTP, which is the only order production
 * can produce them in. The negative cases are the point as much as the positive ones: a courier of
 * another tenant, a tenant administrator and an account that is nobody's courier all get a 404 for
 * a record that is not theirs, and the refused observation is shown to have stored nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourierSelfEndpointTests {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fe200-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe200-4000-7000-8000-0000000000b1");
    private static final UUID CHILONZOR = UUID.fromString("018fe200-4000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018fe200-4000-7000-8000-0000000000c2");
    private static final UUID COURIER_TYPE = UUID.fromString("018fe200-4000-7000-8000-0000000000d1");

    private static final UUID OTHER_TENANT = UUID.fromString("018fe200-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fe200-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fe200-4000-7000-8000-0000000000c3");
    private static final UUID OTHER_COURIER_TYPE = UUID.fromString("018fe200-4000-7000-8000-0000000000d2");

    private static final String ELSEWHERE = "keycloak-elsewhere";
    private static final String NOT_A_COURIER = "keycloak-not-a-courier";
    private static final String MANAGER = "courier-self-manager";

    /** Where the fixture's handset is, in Tashkent. */
    private static final double LATITUDE = 41.311081;

    private static final double LONGITUDE = 69.240562;

    private static final String ME = "/api/v1/courier/tenants/" + TENANT + "/me";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier self endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // ADR 0029: a shift's opening point and every track window are envelope encrypted.
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
    private CacheManager cacheManager;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcCourierStore courierStore;

    @Autowired
    @SuppressWarnings("NullAway")
    private RecordingSignals signals;

    private final AtomicInteger keys = new AtomicInteger();
    private final AtomicInteger couriers = new AtomicInteger();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // Positions and tracks carry no foreign key to the tenant, so truncating the tenant below does
        // not clear them: a position a previous test stored would answer this one's "nothing stored".
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_track_summaries, fulfillment.courier_location_tracks, "
                        + "fulfillment.courier_positions_live, fulfillment.courier_duty_sessions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_engagements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        signals.published.clear();

        seedTenancy(TENANT, BRAND, List.of(CHILONZOR, YUNUSOBOD), COURIER_TYPE);
        seedTenancy(OTHER_TENANT, OTHER_BRAND, List.of(OTHER_LOCATION), OTHER_COURIER_TYPE);
        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.TENANT_ADMIN, TENANT);
    }

    // ------------------------------------------------------------------ /me

    @Test
    @DisplayName("a courier learns who the platform says they are: label, engagement, branches, and no open shift yet")
    void meAnswersWithTheCallersOwnRecord() throws Exception {
        Courier alisher = courier("keycloak-alisher");

        MvcResult answer = mvc.perform(get(ME).with(token(alisher.subject))).andReturn();

        assertThat(answer.getResponse().getStatus()).isEqualTo(200);
        JsonNode me = json(answer);
        assertThat(me.path("courierId").asText()).isEqualTo(alisher.id.toString());
        assertThat(me.path("displayReference").asText()).isEqualTo(alisher.reference);
        assertThat(me.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(me.path("engagement").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(me.path("engagement").path("warningState").asText()).isEqualTo("VALID");
        assertThat(me.path("engagement").path("dispatchable").asBoolean()).isTrue();
        assertThat(me.path("engagement").path("registrationValidUntil").asText())
                .isEqualTo(java.time.LocalDate.now(ZoneOffset.UTC).plusYears(1).toString());
        assertThat(me.path("openShift").isNull()).as("not working yet").isTrue();
        assertThat(me.path("branches")).hasSize(1);
        assertThat(me.path("branches").get(0).path("locationId").asText()).isEqualTo(CHILONZOR.toString());
        assertThat(me.path("branches").get(0).path("displayName").asText()).isEqualTo("Chilonzor");
        assertThat(me.path("branches").get(0).path("primary").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a courier with a shift open sees it, with its duty state and the name of the branch")
    void meCarriesTheOpenShift() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        String shiftId = openShift(alisher, CHILONZOR);

        JsonNode me = json(mvc.perform(get(ME).with(token(alisher.subject))).andReturn());

        JsonNode shift = me.path("openShift");
        assertThat(shift.path("shiftId").asText()).isEqualTo(shiftId);
        assertThat(shift.path("status").asText()).isEqualTo("OPEN");
        assertThat(shift.path("dutyState").asText()).isEqualTo("AVAILABLE");
        assertThat(shift.path("locationId").asText()).isEqualTo(CHILONZOR.toString());
        assertThat(shift.path("locationName").asText()).isEqualTo("Chilonzor");
        assertThat(Instant.parse(shift.path("openedAt").asText()))
                .isBefore(Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("a break shows as the duty state the dispatcher's board is working from")
    void meCarriesTheBreakAsDutyState() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        String shiftId = openShift(alisher, CHILONZOR);

        assertThat(shiftCall(alisher, CHILONZOR, shiftId, "/breaks", "{}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(202);

        assertThat(json(mvc.perform(get(ME).with(token(alisher.subject))).andReturn())
                        .path("openShift")
                        .path("dutyState")
                        .asText())
                .isEqualTo("ON_BREAK");
    }

    @Test
    @DisplayName("a courier is only ever told about themself: the other courier's record is nowhere in the answer")
    void meIsTheCallersAlone() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        Courier bobur = courier("keycloak-bobur");
        openShift(bobur, CHILONZOR);

        MvcResult answer = mvc.perform(get(ME).with(token(alisher.subject))).andReturn();

        String text = answer.getResponse().getContentAsString(UTF_8);
        assertThat(json(answer).path("courierId").asText()).isEqualTo(alisher.id.toString());
        assertThat(json(answer).path("openShift").isNull()).isTrue();
        assertThat(text).doesNotContain(bobur.id.toString()).doesNotContain(bobur.reference);
    }

    @Test
    @DisplayName("what a courier is shown carries no legal name, registration number or telephone, and is never cached")
    void meCarriesNoPersonalDataAndIsNotCached() throws Exception {
        Courier alisher = courier("keycloak-alisher");

        MvcResult answer = mvc.perform(get(ME).with(token(alisher.subject))).andReturn();

        assertThat(answer.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String text = answer.getResponse().getContentAsString(UTF_8);
        assertThat(text)
                .as("the protected full name and registration reference stay on the platform")
                .doesNotContain("ciphertext-not-exercised-here")
                .doesNotContainIgnoringCase("protected")
                .doesNotContainIgnoringCase("phone");
    }

    @Test
    @DisplayName(
            "an account that is nobody's courier, a tenant administrator and a courier of another tenant all get 404")
    void meIsNotFoundForAnyoneWhoIsNotThisTenantsCourier() throws Exception {
        Courier elsewhere = courierIn(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, OTHER_COURIER_TYPE, ELSEWHERE);

        for (String subject : List.of(NOT_A_COURIER, MANAGER, elsewhere.subject)) {
            MvcResult answer = mvc.perform(get(ME).with(token(subject))).andReturn();
            assertThat(answer.getResponse().getStatus()).as(subject).isEqualTo(404);
            assertThat(json(answer).path("code").asText()).as(subject).isEqualTo("RESOURCE_NOT_FOUND");
        }

        // …and the same courier is a courier at home: the 404 above is the tenant predicate, not a fixture that seeded
        // nothing.
        assertThat(mvc.perform(get("/api/v1/courier/tenants/" + OTHER_TENANT + "/me")
                                .with(token(elsewhere.subject)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an unauthenticated caller is refused before any courier is looked up")
    void meNeedsAToken() throws Exception {
        assertThat(mvc.perform(get(ME)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a courier with no live engagement is still told so, as an absent engagement, not an error")
    void meWithoutAnEngagement() throws Exception {
        Courier unregistered = courierWithoutEngagement("keycloak-unregistered");

        JsonNode me =
                json(mvc.perform(get(ME).with(token(unregistered.subject))).andReturn());

        assertThat(me.path("engagement").isNull()).isTrue();
        assertThat(me.path("courierId").asText()).isEqualTo(unregistered.id.toString());
    }

    // ----------------------------------------------------------- duty session

    @Test
    @DisplayName("going on duty without a shift is refused with the reason, and stores nothing")
    void aSessionCannotOpenWithoutAShift() throws Exception {
        Courier alisher = courier("keycloak-alisher");

        MvcResult refused = openSession(alisher, CHILONZOR, "device-1");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(refused).path("reason").asText()).isEqualTo("NO_OPEN_SHIFT");
        assertThat(sessionCount())
                .as("a courier with no shift is not working, so nothing is collected")
                .isZero();
    }

    @Test
    @DisplayName(
            "a courier with a shift goes on duty: the platform picks the gate, the cadence is the platform's, and the audit names the courier")
    void aCourierGoesOnDuty() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        String shiftId = openShift(alisher, CHILONZOR);

        MvcResult opened = openSession(alisher, CHILONZOR, "device-1");

        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        JsonNode session = json(opened);
        assertThat(session.path("shiftId").asText()).isEqualTo(shiftId);
        assertThat(session.path("locationId").asText()).isEqualTo(CHILONZOR.toString());
        assertThat(session.path("status").asText()).isEqualTo("OPEN");
        assertThat(session.path("collectionGate").asText()).isEqualTo("ON_DUTY");
        assertThat(session.path("batchCadenceSeconds").asInt()).isEqualTo(10);
        assertThat(session.path("maximumBatchSize").asInt()).isEqualTo(60);
        assertThat(session.has("courierId"))
                .as("a courier is not told their own internal id twice")
                .isFalse();

        assertThat(jdbc.sql("SELECT opened_by_subject FROM fulfillment.courier_duty_sessions")
                        .query(String.class)
                        .single())
                .isEqualTo(alisher.subject);
        JsonNode audit = auditEvent("telemetry.duty_session.opened");
        assertThat(audit.path("actor_subject").asText()).isEqualTo(alisher.subject);
        assertThat(audit.path("capability_used").asText()).isEqualTo("courier.telemetry");
        assertThat(audit.path("audit_class").asText()).isEqualTo("BUSINESS");
    }

    @Test
    @DisplayName("the collection gate is the branch's setting, not the courier's: a body that names one is ignored")
    void theCourierDoesNotChooseTheGate() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id, value_type,
                    string_value, set_by)
                VALUES (:id, 'telemetry.courier_collection_gate', 'TENANT', :tenantId, 'STRING',
                    'ON_ASSIGNMENT', 'a-test')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });

        MvcResult opened = mvc.perform(post(base(CHILONZOR) + "/duty-sessions")
                        .with(token(alisher.subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"device-1\",\"collectionGate\":\"ON_DUTY\"}"))
                .andReturn();

        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(opened).path("collectionGate").asText())
                .as("the tenant narrowed collection to deliveries; the handset does not get to widen it")
                .isEqualTo("ON_ASSIGNMENT");
    }

    @Test
    @DisplayName("going on duty twice answers with the session already open, never a second one")
    void goingOnDutyTwiceIsOneSession() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);

        String first = json(openSession(alisher, CHILONZOR, "device-1"))
                .path("sessionId")
                .asText();
        String second = json(openSession(alisher, CHILONZOR, "device-2"))
                .path("sessionId")
                .asText();

        assertThat(second)
                .as("a reconnecting handset and a swapped device both post this")
                .isEqualTo(first);
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("going on duty carries an Idempotency-Key like every effectful mutation (ADR 0031)")
    void openingNeedsAnIdempotencyKey() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);

        MvcResult refused = mvc.perform(post(base(CHILONZOR) + "/duty-sessions")
                        .with(token(alisher.subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"device-1\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(refused).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName(
            "a missing device id is a validation problem, not a 500 (Jackson 3 refuses a missing primitive, so the real JSON is sent)")
    void aMissingDeviceIdIsRefused() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);

        MvcResult refused = mvc.perform(post(base(CHILONZOR) + "/duty-sessions")
                        .with(token(alisher.subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(refused).path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a courier reads their own session at the branch they work at and none at another")
    void theCurrentSessionIsPerBranch() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        String before = json(mvc.perform(get(base(CHILONZOR) + "/duty-session").with(token(alisher.subject)))
                        .andReturn())
                .path("session")
                .toString();
        assertThat(before)
                .as("not on duty yet: 200 and a null session, not an error")
                .isEqualTo("null");

        openShift(alisher, CHILONZOR);
        String sessionId = json(openSession(alisher, CHILONZOR, "device-1"))
                .path("sessionId")
                .asText();

        MvcResult here = mvc.perform(get(base(CHILONZOR) + "/duty-session").with(token(alisher.subject)))
                .andReturn();
        assertThat(json(here).path("session").path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(here.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        MvcResult there = mvc.perform(get(base(YUNUSOBOD) + "/duty-session").with(token(alisher.subject)))
                .andReturn();
        assertThat(json(there).path("session").toString())
                .as("a courier working at Chilonzor is not on duty at Yunusobod")
                .isEqualTo("null");
    }

    @Test
    @DisplayName("a courier cannot sign off somebody else's session, and the other courier's session stays open")
    void aCourierCannotCloseAnotherCouriersSession() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        Courier bobur = courier("keycloak-bobur");
        openShift(alisher, CHILONZOR);
        openShift(bobur, CHILONZOR);
        String alishers = json(openSession(alisher, CHILONZOR, "device-a"))
                .path("sessionId")
                .asText();
        openSession(bobur, CHILONZOR, "device-b");

        MvcResult refused = closeSession(bobur, CHILONZOR, alishers);

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(sessionStatus(alishers)).isEqualTo("OPEN");
        assertThat(auditCount("telemetry.duty_session.closed")).isZero();
    }

    @Test
    @DisplayName("a courier signs off: collection stops, the reason is recorded, and the audit names the courier")
    void aCourierSignsOff() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        String sessionId = json(openSession(alisher, CHILONZOR, "device-1"))
                .path("sessionId")
                .asText();

        MvcResult closed = closeSession(alisher, CHILONZOR, sessionId);

        assertThat(closed.getResponse().getStatus()).isEqualTo(204);
        assertThat(sessionStatus(sessionId)).isEqualTo("CLOSED");
        assertThat(jdbc.sql("SELECT end_reason FROM fulfillment.courier_duty_sessions WHERE id = :id")
                        .param("id", UUID.fromString(sessionId))
                        .query(String.class)
                        .single())
                .isEqualTo("SIGNED_OFF");
        JsonNode audit = auditEvent("telemetry.duty_session.closed");
        assertThat(audit.path("actor_subject").asText()).isEqualTo(alisher.subject);
        assertThat(audit.path("capability_used").asText()).isEqualTo("courier.telemetry");
        assertThat(json(mvc.perform(get(base(CHILONZOR) + "/duty-session").with(token(alisher.subject)))
                                .andReturn())
                        .path("session")
                        .toString())
                .isEqualTo("null");
    }

    @Test
    @DisplayName(
            "neither a tenant administrator nor a courier of another tenant can go on duty: no courier row, no session")
    void onlyTheTenantsOwnCourierGoesOnDuty() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        Courier elsewhere = courierIn(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, OTHER_COURIER_TYPE, ELSEWHERE);

        for (String subject : List.of(MANAGER, elsewhere.subject, NOT_A_COURIER)) {
            MvcResult refused = mvc.perform(post(base(CHILONZOR) + "/duty-sessions")
                            .with(token(subject))
                            .header("Idempotency-Key", nextKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceId\":\"device-x\"}"))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as(subject).isEqualTo(404);
        }
        assertThat(sessionCount()).isZero();
    }

    // ------------------------------------------------------------ observations

    @Test
    @DisplayName("a position with no open session is refused with the reason, and nothing about it is stored anywhere")
    void anObservationWithNoSessionIsRefusedAndStoresNothing() throws Exception {
        Courier alisher = courier("keycloak-alisher");

        MvcResult refused =
                observe(alisher, CHILONZOR, batch(reading(Instant.now().minusSeconds(3))));

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(refused).path("reason").asText()).isEqualTo("NO_OPEN_DUTY_SESSION");
        assertThat(trackWindowCount()).isZero();
        assertThat(liveCount()).isZero();
        assertThat(signals.published).isEmpty();
    }

    @Test
    @DisplayName(
            "a courier on duty sends a batch: the pin moves, the track is written, the cadence comes back, and no coordinate is in the answer")
    void aCourierSendsABatch() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");

        MvcResult accepted = observe(
                alisher,
                CHILONZOR,
                batch(
                        reading(Instant.now().minusSeconds(8)),
                        reading(Instant.now().minusSeconds(2))));

        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        JsonNode outcome = json(accepted);
        assertThat(outcome.path("observationsAccepted").asInt()).isEqualTo(2);
        assertThat(outcome.path("livePositionMoved").asBoolean()).isTrue();
        assertThat(outcome.path("suspended").asBoolean()).isFalse();
        assertThat(outcome.path("collectionGate").asText()).isEqualTo("ON_DUTY");
        assertThat(outcome.path("nextBatchAfterSeconds").asInt()).isEqualTo(10);
        assertThat(accepted.getResponse().getContentAsString(UTF_8))
                .as("nothing about where the courier is comes back out")
                .doesNotContain(String.valueOf(LATITUDE))
                .doesNotContain(String.valueOf(LONGITUDE));

        assertThat(liveCount()).isEqualTo(1);
        assertThat(trackWindowCount()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT courier_id FROM fulfillment.courier_positions_live")
                        .query(UUID.class)
                        .single())
                .isEqualTo(alisher.id);
        assertThat(signals.published)
                .as("a dispatcher's map is told that the pin moved, by id and scope, never by coordinate")
                .anySatisfy(signal -> {
                    assertThat(signal.channel()).isEqualTo(StreamChannel.COURIER_POSITIONS);
                    assertThat(signal.resourceId()).isEqualTo(alisher.id);
                });
    }

    @Test
    @DisplayName(
            "the batch needs no Idempotency-Key: ADR 0045's one named exemption, because the track is idempotent on its own natural key")
    void theBatchNeedsNoIdempotencyKey() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");
        long recordedBefore = idempotencyRecords();

        MvcResult accepted = mvc.perform(post(base(CHILONZOR) + "/telemetry/observations")
                        .with(token(alisher.subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(reading(Instant.now().minusSeconds(3)))))
                .andReturn();

        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        assertThat(recordedBefore)
                .as("the shift and the session did record theirs, so a count that never moves would prove nothing")
                .isEqualTo(2);
        assertThat(idempotencyRecords())
                .as("six rows a minute per courier would otherwise land here for no benefit")
                .isEqualTo(recordedBefore);
    }

    @Test
    @DisplayName("a body that names another courier is ignored: the position is the caller's own")
    void theBodyCannotNameAnotherCourier() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        Courier bobur = courier("keycloak-bobur");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");

        MvcResult accepted = mvc.perform(post(base(CHILONZOR) + "/telemetry/observations")
                        .with(token(alisher.subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courierId\":\"" + bobur.id + "\",\"observations\":["
                                + reading(Instant.now().minusSeconds(3)) + "]}"))
                .andReturn();

        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        assertThat(jdbc.sql("SELECT courier_id FROM fulfillment.courier_positions_live")
                        .query(UUID.class)
                        .list())
                .containsExactly(alisher.id);
    }

    @Test
    @DisplayName("a batch posted to another branch's path is refused: the session is open at Chilonzor, not Yunusobod")
    void aBatchForAnotherBranchIsRefused() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");

        MvcResult refused =
                observe(alisher, YUNUSOBOD, batch(reading(Instant.now().minusSeconds(3))));

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(refused).path("reason").asText()).isEqualTo("NO_OPEN_DUTY_SESSION");
        assertThat(liveCount()).isZero();
    }

    @Test
    @DisplayName(
            "a second batch inside the cadence is refused with a retry-after, so a duplicating handset cannot drain the battery it is watched for")
    void aBatchInsideTheCadenceIsRefused() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");
        assertThat(observe(alisher, CHILONZOR, batch(reading(Instant.now().minusSeconds(4))))
                        .getResponse()
                        .getStatus())
                .isEqualTo(202);

        MvcResult tooSoon =
                observe(alisher, CHILONZOR, batch(reading(Instant.now().minusSeconds(1))));

        assertThat(tooSoon.getResponse().getStatus()).isEqualTo(429);
        assertThat(json(tooSoon).path("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("an impossible reading is a validation problem and a missing field is not a 500")
    void brokenReadingsAreValidationProblems() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");

        String outsideTheWorld = "{\"capturedAt\":\"" + Instant.now().minusSeconds(3)
                + "\",\"latitude\":91.0,\"longitude\":69.2,\"accuracyMeters\":10.0}";
        String noAccuracy =
                "{\"capturedAt\":\"" + Instant.now().minusSeconds(3) + "\",\"latitude\":41.3,\"longitude\":69.2}";
        String halfADeviceReading = "{\"capturedAt\":\"" + Instant.now().minusSeconds(3)
                + "\",\"latitude\":41.3,\"longitude\":69.2,\"accuracyMeters\":10.0,\"batteryPercent\":50}";

        for (String broken : List.of(outsideTheWorld, noAccuracy, halfADeviceReading)) {
            MvcResult refused = observe(alisher, CHILONZOR, "{\"observations\":[" + broken + "]}");
            assertThat(refused.getResponse().getStatus()).as(broken).isEqualTo(400);
            assertThat(json(refused).path("code").asText()).as(broken).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(observe(alisher, CHILONZOR, "{\"observations\":[]}")
                        .getResponse()
                        .getStatus())
                .as("an empty batch")
                .isEqualTo(400);
        assertThat(liveCount()).isZero();
    }

    @Test
    @DisplayName("a validation failure never echoes the position back")
    void aRefusalDoesNotEchoTheCoordinate() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        openShift(alisher, CHILONZOR);
        openSession(alisher, CHILONZOR, "device-1");

        MvcResult refused = observe(
                alisher,
                CHILONZOR,
                "{\"observations\":[{\"capturedAt\":\"" + Instant.now().minusSeconds(3)
                        + "\",\"latitude\":41.311081,\"longitude\":69.240562,\"accuracyMeters\":-5.0}]}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString(UTF_8))
                .doesNotContain("41.311081")
                .doesNotContain("69.240562");
    }

    @Test
    @DisplayName("the request types print as nothing: a stray log line cannot carry a courier's position")
    void readingsPrintAsNothing() {
        var reading = new CourierSelfDutyController.CourierObservationBody(
                Instant.parse("2026-10-14T05:00:00Z"), LATITUDE, LONGITUDE, 12.0, null, null, 80, false);
        var batch = new CourierSelfDutyController.CourierObservationBatchRequest(0, List.of(reading));

        assertThat(reading.toString()).doesNotContain("41.31").doesNotContain("69.24");
        assertThat(batch.toString()).doesNotContain("41.31").doesNotContain("69.24");
    }

    // ------------------------------------------------- the shift and its session

    @Test
    @DisplayName(
            "a break suspends collection: a batch sent while on break is accepted as 'suspended' and stores nothing; ending the break resumes it")
    void aBreakSuspendsCollection() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        String shiftId = openShift(alisher, CHILONZOR);
        String sessionId = json(openSession(alisher, CHILONZOR, "device-1"))
                .path("sessionId")
                .asText();

        shiftCall(alisher, CHILONZOR, shiftId, "/breaks", "{}");
        assertThat(sessionStatus(sessionId)).isEqualTo("SUSPENDED");
        assertThat(json(mvc.perform(get(base(CHILONZOR) + "/duty-session").with(token(alisher.subject)))
                                .andReturn())
                        .path("session")
                        .path("status")
                        .asText())
                .isEqualTo("SUSPENDED");

        MvcResult onBreak =
                observe(alisher, CHILONZOR, batch(reading(Instant.now().minusSeconds(3))));
        assertThat(onBreak.getResponse().getStatus()).isEqualTo(202);
        assertThat(json(onBreak).path("suspended").asBoolean())
                .as("so the app's indicator can say truthfully that collection has stopped")
                .isTrue();
        assertThat(liveCount()).isZero();
        assertThat(trackWindowCount()).isZero();

        shiftCall(alisher, CHILONZOR, shiftId, "/breaks/end", "{}");
        assertThat(sessionStatus(sessionId)).isEqualTo("OPEN");
        MvcResult resumed =
                observe(alisher, CHILONZOR, batch(reading(Instant.now().minusSeconds(3))));
        assertThat(resumed.getResponse().getStatus()).isEqualTo(202);
        assertThat(json(resumed).path("livePositionMoved").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("closing the shift closes the session, so a courier who forgets to sign off is not tracked at home")
    void closingTheShiftEndsCollection() throws Exception {
        Courier alisher = courier("keycloak-alisher");
        String shiftId = openShift(alisher, CHILONZOR);
        String sessionId = json(openSession(alisher, CHILONZOR, "device-1"))
                .path("sessionId")
                .asText();

        assertThat(shiftCall(alisher, CHILONZOR, shiftId, "/close", "{\"currency\":\"UZS\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(sessionStatus(sessionId)).isEqualTo("CLOSED");
        assertThat(jdbc.sql("SELECT end_reason FROM fulfillment.courier_duty_sessions WHERE id = :id")
                        .param("id", UUID.fromString(sessionId))
                        .query(String.class)
                        .single())
                .isEqualTo("SHIFT_ENDED");
        assertThat(auditEvent("telemetry.duty_session.closed")
                        .path("capability_used")
                        .asText())
                .isEqualTo("courier.shift.open");

        MvcResult after =
                observe(alisher, CHILONZOR, batch(reading(Instant.now().minusSeconds(3))));
        assertThat(after.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(after).path("reason").asText()).isEqualTo("NO_OPEN_DUTY_SESSION");
        assertThat(trackWindowCount()).isZero();
    }

    // ----------------------------------------------------------------- helpers

    private record Courier(UUID id, String subject, String reference) {}

    private Courier courier(String subject) {
        return courierIn(TENANT, BRAND, CHILONZOR, COURIER_TYPE, subject);
    }

    private Courier courierIn(UUID tenantId, UUID brandId, UUID locationId, UUID typeId, String subject) {
        Courier courier = insertCourier(tenantId, typeId, subject);
        seedActiveEngagement(courier.id, tenantId);
        courierStore.bindToBranch(tenantId, courier.id, brandId, locationId, true, "test-fixture", Instant.now());
        return courier;
    }

    private Courier courierWithoutEngagement(String subject) {
        return insertCourier(TENANT, COURIER_TYPE, subject);
    }

    private Courier insertCourier(UUID tenantId, UUID typeId, String subject) {
        UUID id = UUID.randomUUID();
        String reference =
                "REF-" + couriers.incrementAndGet() + "-" + id.toString().substring(0, 4);
        jdbc.sql("""
                INSERT INTO fulfillment.couriers
                    (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'ciphertext-not-exercised-here', 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("typeId", typeId)
                .param("subject", subject)
                .param("reference", reference)
                .update();
        return new Courier(id, subject, reference);
    }

    private String openShift(Courier courier, UUID locationId) throws Exception {
        MvcResult opened = mvc.perform(post(base(locationId) + "/shifts")
                        .with(token(courier.subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"UZS\"}"))
                .andReturn();
        assertThat(opened.getResponse().getStatus())
                .as("the courier opens their own shift over HTTP, the only way production makes one")
                .isEqualTo(200);
        return json(opened).path("shiftId").asText();
    }

    private MvcResult shiftCall(Courier courier, UUID locationId, String shiftId, String suffix, String body)
            throws Exception {
        return mvc.perform(post(base(locationId) + "/shifts/" + shiftId + suffix)
                        .with(token(courier.subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult openSession(Courier courier, UUID locationId, String deviceId) throws Exception {
        return mvc.perform(post(base(locationId) + "/duty-sessions")
                        .with(token(courier.subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andReturn();
    }

    private MvcResult closeSession(Courier courier, UUID locationId, String sessionId) throws Exception {
        return mvc.perform(post(base(locationId) + "/duty-sessions/" + sessionId + "/closures")
                        .with(token(courier.subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    private MvcResult observe(Courier courier, UUID locationId, String body) throws Exception {
        return mvc.perform(post(base(locationId) + "/telemetry/observations")
                        .with(token(courier.subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** One reading as the courier app sends it: only what the handset has, no optional field invented. */
    private static String reading(Instant capturedAt) {
        return "{\"capturedAt\":\"" + capturedAt + "\",\"latitude\":" + LATITUDE + ",\"longitude\":" + LONGITUDE
                + ",\"accuracyMeters\":12.5,\"batteryPercent\":80,\"deviceCharging\":false}";
    }

    private static String batch(String... readings) {
        return "{\"activeAssignmentCount\":0,\"observations\":[" + String.join(",", readings) + "]}";
    }

    private static String base(UUID locationId) {
        return "/api/v1/courier/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId;
    }

    private String nextKey() {
        return "courier-self-" + keys.incrementAndGet();
    }

    private String sessionStatus(String sessionId) {
        return jdbc.sql("SELECT status FROM fulfillment.courier_duty_sessions WHERE id = :id")
                .param("id", UUID.fromString(sessionId))
                .query(String.class)
                .single();
    }

    private long idempotencyRecords() {
        return jdbc.sql("SELECT count(*) FROM platform.idempotency_records")
                .query(Long.class)
                .single();
    }

    private long sessionCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_duty_sessions")
                .query(Long.class)
                .single();
    }

    private long liveCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_positions_live")
                .query(Long.class)
                .single();
    }

    private long trackWindowCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.courier_location_tracks")
                .query(Long.class)
                .single();
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private JsonNode auditEvent(String action) throws Exception {
        List<String> rows =
                jdbc.sql("""
                        SELECT row_to_json(e)::text FROM (
                            SELECT audit_class, action_code, actor_subject, target_type, target_id,
                                   capability_used, reason, change_document
                            FROM audit.audit_events WHERE action_code = :action) e
                        """).param("action", action).query(String.class).list();
        assertThat(rows).as("audit rows for %s", action).hasSize(1);
        return JSON.readTree(rows.get(0));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private void seedTenancy(UUID tenantId, UUID brandId, List<UUID> locations, UUID courierTypeId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "courier-self-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        String[] names = {"Chilonzor", "Yunusobod"};
        for (int index = 0; index < locations.size(); index++) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version, latitude, longitude, coordinate_source, address_line,
                        district, city)
                    VALUES (:id, :t, :b, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0,
                        :lat, :lon, 'MERCHANT_PIN', 'Amir Temur 1', 'Yunusobod', 'Tashkent')
                    """)
                    .param("id", locations.get(index))
                    .param("t", tenantId)
                    .param("b", brandId)
                    .param("code", "LOC" + index)
                    .param("slug", "loc-" + index)
                    .param("name", names[index % names.length])
                    .param("lat", LATITUDE)
                    .param("lon", LONGITUDE)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", courierTypeId).param("t", tenantId).update();
    }

    /** A minimal ACTIVE engagement: every column {@code ck_engagement_active_is_verified} requires, and nothing more. */
    private void seedActiveEngagement(UUID courierId, UUID tenantId) {
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_engagements (
                    id, tenant_id, courier_id, engagement_type, status, engaged_from,
                    protected_registration_ref, registration_valid_until,
                    registration_verified_at, registration_verified_by, verification_method,
                    reverification_due_on, warning_state, version, created_at, updated_at)
                VALUES (:id, :tenantId, :courierId, 'SELF_EMPLOYED', 'ACTIVE', :engagedFrom,
                    'ciphertext-not-exercised-here', :validUntil,
                    :verifiedAt, 'test-fixture', 'MANUAL_ATTESTATION',
                    :reverificationDue, 'VALID', 1, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("engagedFrom", now.atOffset(ZoneOffset.UTC).toLocalDate().minusMonths(1))
                .param("validUntil", now.atOffset(ZoneOffset.UTC).toLocalDate().plusYears(1))
                .param("verifiedAt", now.atOffset(ZoneOffset.UTC))
                .param(
                        "reverificationDue",
                        now.atOffset(ZoneOffset.UTC).toLocalDate().plusMonths(6))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier self endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    static final class RecordingSignals implements RealtimeSignalPublisher {

        final List<RealtimeSignal> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(RealtimeSignal signal) {
            published.add(signal);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        /**
         * Replaces the Kafka publisher, whose first send blocks for the producer's whole
         * {@code max.block.ms} when no broker is reachable, as here. The signal an observation raises is
         * what the dispatcher's map redraws on, so it is recorded and asserted rather than dropped.
         */
        @Bean
        @Primary
        RecordingSignals recordingSignals() {
            return new RecordingSignals();
        }
    }
}
