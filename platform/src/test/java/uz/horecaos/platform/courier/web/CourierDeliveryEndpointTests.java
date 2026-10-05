package uz.horecaos.platform.courier.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
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
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.RegistrationWarningState;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.domain.Haversine;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.payments.api.CashDueLookupPort;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The courier app's offers and deliveries, held to the courier policy, through the real HTTP stack
 * (gap map row 3.9).
 *
 * <p>The policy is switched on the way an operator switches it, by {@code PUT .../courier-policy}
 * under a real tenant-admin grant, and the courier then acts with a token whose subject is the only
 * thing that says who they are. Nothing here sets a policy row by hand or builds a shipment in a
 * sequence production cannot produce: a delivery exists because a courier accepted an offer over
 * HTTP, and moves because the same courier advanced it.
 *
 * <p>Each gate is asserted from both sides in the same class -- refused with the stable
 * {@code reason} when the switch is on, allowed when it is off -- so a test that passes because the
 * gate was never reached cannot stay green, and the refusal asserts that nothing changed in the
 * database, not only that a status code came back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourierDeliveryEndpointTests {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fe100-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe100-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe100-4000-7000-8000-0000000000c1");
    private static final UUID COURIER_TYPE = UUID.fromString("018fe100-4000-7000-8000-0000000000d1");

    private static final UUID OTHER_TENANT = UUID.fromString("018fe100-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fe100-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fe100-4000-7000-8000-0000000000c2");
    private static final UUID OTHER_COURIER_TYPE = UUID.fromString("018fe100-4000-7000-8000-0000000000d2");

    private static final UUID COURIER_ALISHER = UUID.fromString("018fe100-4000-7000-8000-0000000000e1");
    private static final UUID COURIER_BOBUR = UUID.fromString("018fe100-4000-7000-8000-0000000000e2");
    private static final UUID COURIER_ELSEWHERE = UUID.fromString("018fe100-4000-7000-8000-0000000000e3");

    private static final String ALISHER = "keycloak-alisher";
    private static final String BOBUR = "keycloak-bobur";
    private static final String ELSEWHERE = "keycloak-elsewhere";
    private static final String NOT_A_COURIER = "keycloak-not-a-courier";

    /** Holds TENANT_ADMIN's bundle at TENANT, which is what writes the courier policy. */
    private static final String MANAGER = "courier-delivery-manager";

    /** Amir Temur square, where the branch is. */
    private static final double BRANCH_LATITUDE = 41.311081;

    private static final double BRANCH_LONGITUDE = 69.240562;

    /** About two kilometres north of the branch: the customer's door. */
    private static final double DOOR_LATITUDE = 41.330000;

    private static final double DOOR_LONGITUDE = 69.240000;

    private static final String STREET_LINE = "Bunyodkor 14";
    private static final String CUSTOMER_NAME = "Dilnoza Karimova";
    private static final String CUSTOMER_PHONE = "+998901112233";
    private static final String GATE_NOTE = "Ring twice, the gate code is 4471";

    private static final String BASE =
            "/api/v1/courier/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier delivery endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // ADR 0029: the customer's address is envelope-encrypted on the order, so a real key is needed
        // to seal the fixture and to open it again through the production reveal.
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
    private FieldProtection protection;

    @Autowired
    @SuppressWarnings("NullAway")
    private ObjectMapper objectMapper;

    @Autowired
    @SuppressWarnings("NullAway")
    private StubCashDue cashDue;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcCourierStore courierStore;

    @Autowired
    @SuppressWarnings("NullAway")
    private RecordingSignals signals;

    @Autowired
    @SuppressWarnings("NullAway")
    private DeliveryOrderPort deliveryOrders;

    private final AtomicInteger keys = new AtomicInteger();
    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_plans CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_engagements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        // tenant.policy_current (ADR 0033) is an in-process cache shared by every test in this class's
        // one Spring context; a policy a previous test published would otherwise still answer.
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        cashDue.byOrder.clear();
        signals.published.clear();

        seedTenancy(TENANT, BRAND, LOCATION, COURIER_TYPE, BRANCH_LATITUDE, BRANCH_LONGITUDE);
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, OTHER_COURIER_TYPE, BRANCH_LATITUDE, BRANCH_LONGITUDE);
        seedCatalog();

        insertCourier(COURIER_ALISHER, TENANT, COURIER_TYPE, ALISHER);
        insertCourier(COURIER_BOBUR, TENANT, COURIER_TYPE, BOBUR);
        insertCourier(COURIER_ELSEWHERE, OTHER_TENANT, OTHER_COURIER_TYPE, ELSEWHERE);
        seedActiveEngagement(COURIER_ALISHER, TENANT);
        seedActiveEngagement(COURIER_BOBUR, TENANT);
        seedActiveEngagement(COURIER_ELSEWHERE, OTHER_TENANT);

        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.TENANT_ADMIN, TENANT);
    }

    // ------------------------------------------------------------------ the list

    @Test
    @DisplayName("a courier lists the offers made to them and nobody else's, with no part of the customer in them")
    void offersAreTheCallersOwnAndCarryNothingAboutTheCustomer() throws Exception {
        UUID mine = seedOffer(COURIER_ALISHER, "CONFIRMED");
        UUID bobursOffer = seedOffer(COURIER_BOBUR, "CONFIRMED");

        MvcResult listed =
                mvc.perform(get(BASE + "/offers").with(tokenFor(ALISHER))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode offers = json(listed);
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).path("offerId").asText()).isEqualTo(mine.toString());
        assertThat(offers.get(0).path("pickup").path("name").asText()).isEqualTo("Centre");
        assertThat(offers.get(0).path("destinationLabel").asText()).isEqualTo("Chilonzor");
        assertThat(offers.get(0).path("customerLocationRevealable").asBoolean())
                .as("the default policy reveals the customer's door only after acceptance")
                .isFalse();

        String text = listed.getResponse().getContentAsString(UTF_8);
        assertThat(text)
                .as("the customer's name, telephone number, address and the courier's offer for anyone else")
                .doesNotContain(CUSTOMER_NAME)
                .doesNotContain(CUSTOMER_PHONE)
                .doesNotContain(STREET_LINE)
                .doesNotContain(GATE_NOTE)
                .doesNotContain(bobursOffer.toString());

        MvcResult bobursList =
                mvc.perform(get(BASE + "/offers").with(tokenFor(BOBUR))).andReturn();
        assertThat(json(bobursList)).hasSize(1);
        assertThat(json(bobursList).get(0).path("offerId").asText()).isEqualTo(bobursOffer.toString());
    }

    @Test
    @DisplayName("a lapsed offer is not listed and cannot be taken")
    void aLapsedOfferIsGone() throws Exception {
        UUID lapsed = seedOffer(COURIER_ALISHER, "CONFIRMED");
        jdbc.sql("UPDATE fulfillment.assignment_attempts SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", lapsed)
                .update();

        assertThat(json(mvc.perform(get(BASE + "/offers").with(tokenFor(ALISHER)))
                        .andReturn()))
                .isEmpty();
        MvcResult accepted = accept(ALISHER, lapsed, 1, null);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(accepted).path("outcome").asText()).isEqualTo("NO_LONGER_AVAILABLE");
        assertThat(shipmentCount()).isZero();
    }

    // ------------------------------------------------------------ accept and decline

    @Test
    @DisplayName(
            "accepting an offer makes the courier the one carrier of the delivery, audited, and a second tap loses")
    void acceptingTakesTheDelivery() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult accepted = accept(ALISHER, offer, 1, null);

        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(accepted);
        assertThat(body.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("delivery").path("status").asText()).isEqualTo("ASSIGNED");
        assertThat(body.path("delivery").path("kitchenReady").asBoolean()).isTrue();
        UUID shipment = UUID.fromString(body.path("delivery").path("shipmentId").asText());

        assertThat(jdbc.sql("SELECT status FROM fulfillment.assignment_attempts WHERE id = :id")
                        .param("id", offer)
                        .query(String.class)
                        .single())
                .isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT courier_id FROM fulfillment.shipments WHERE id = :id")
                        .param("id", shipment)
                        .query(UUID.class)
                        .single())
                .isEqualTo(COURIER_ALISHER);
        assertThat(jdbc.sql("SELECT p.status FROM fulfillment.delivery_plans p "
                                + "JOIN fulfillment.shipments s ON s.delivery_plan_id = p.id WHERE s.id = :id")
                        .param("id", shipment)
                        .query(String.class)
                        .single())
                .as("the plan is settled, so the sourcing job that was waiting on the offer finds nothing to do")
                .isEqualTo("ASSIGNED");

        JsonNode audit = auditEvent("courier.offer.accepted");
        assertThat(audit.path("actor_subject").asText()).isEqualTo(ALISHER);
        assertThat(audit.path("target_id").asText()).isEqualTo(shipment.toString());
        assertThat(audit.path("change_document").toString())
                .contains(COURIER_ALISHER.toString())
                .doesNotContain(CUSTOMER_NAME)
                .doesNotContain(STREET_LINE);

        assertThat(signals.published)
                .as("the dispatch board redraws on the strength of this signal, so an accepted offer raises one")
                .hasSize(1)
                .allSatisfy(signal -> {
                    assertThat(signal.channel()).isEqualTo(StreamChannel.DISPATCH_BOARD);
                    assertThat(signal.tenantId()).isEqualTo(TENANT);
                    assertThat(signal.scopeKey().canonical()).contains(LOCATION.toString());
                });

        MvcResult mine =
                mvc.perform(get(BASE + "/deliveries").with(tokenFor(ALISHER))).andReturn();
        assertThat(json(mine)).hasSize(1);

        MvcResult second = accept(ALISHER, offer, 1, null);
        assertThat(json(second).path("outcome").asText())
                .as("the second tap is 'somebody else took it', not an error")
                .isEqualTo("NO_LONGER_AVAILABLE");
        assertThat(shipmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("another courier's offer id answers exactly as one that does not exist, and changes nothing")
    void aStrangersOfferIsNotAnOracle() throws Exception {
        UUID alishersOffer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult attempt = accept(BOBUR, alishersOffer, 1, null);
        MvcResult nonsense = accept(BOBUR, UUID.randomUUID(), 1, null);

        assertThat(attempt.getResponse().getStatus())
                .isEqualTo(nonsense.getResponse().getStatus());
        assertThat(attempt.getResponse().getContentAsString(UTF_8))
                .isEqualTo(nonsense.getResponse().getContentAsString(UTF_8));
        assertThat(json(attempt).path("outcome").asText()).isEqualTo("NO_LONGER_AVAILABLE");
        assertThat(attemptStatus(alishersOffer)).isEqualTo("OFFERED");
        assertThat(shipmentCount()).isZero();

        MvcResult decline = decline(BOBUR, alishersOffer, 1);
        assertThat(json(decline).path("outcome").asText()).isEqualTo("NO_LONGER_AVAILABLE");
        assertThat(attemptStatus(alishersOffer))
                .as("Bobur cannot decline Alisher's offer for him")
                .isEqualTo("OFFERED");

        MvcResult reveal = revealOffer(BOBUR, alishersOffer);
        assertThat(reveal.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("declining closes the offer, wakes sourcing for the plan at once, and is audited")
    void decliningWakesSourcing() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");
        UUID plan = planOfAttempt(offer);
        UUID job = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_sourcing_jobs (id, tenant_id, delivery_plan_id, status, due_at)
                VALUES (:id, :t, :plan, 'PENDING', now() + interval '1 hour')
                """).param("id", job).param("t", TENANT).param("plan", plan).update();

        MvcResult declined = decline(ALISHER, offer, 1);

        assertThat(declined.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(declined).path("outcome").asText()).isEqualTo("DECLINED");
        assertThat(attemptStatus(offer)).isEqualTo("DECLINED");
        assertThat(jdbc.sql("SELECT declined_at IS NOT NULL AND failed_at IS NULL FROM fulfillment.assignment_attempts "
                                + "WHERE id = :id")
                        .param("id", offer)
                        .query(Boolean.class)
                        .single())
                .as("a person saying no is not a failure")
                .isTrue();
        assertThat(jdbc.sql("SELECT due_at <= now() FROM fulfillment.delivery_sourcing_jobs WHERE id = :id")
                        .param("id", job)
                        .query(Boolean.class)
                        .single())
                .as("without this the next courier is asked when the declined offer would have lapsed")
                .isTrue();
        assertThat(auditEvent("courier.offer.declined").path("actor_subject").asText())
                .isEqualTo(ALISHER);
        assertThat(shipmentCount()).isZero();

        assertThat(json(decline(ALISHER, offer, 2)).path("outcome").asText())
                .as("declining twice is not an error either")
                .isEqualTo("NO_LONGER_AVAILABLE");
    }

    @Test
    @DisplayName("an If-Match that no longer matches is refused with STALE_VERSION, and a missing one is a bad request")
    void concurrencyIsCheckedWithIfMatch() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult stale = accept(ALISHER, offer, 7, null);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).path("code").asText()).isEqualTo("STALE_VERSION");
        assertThat(attemptStatus(offer)).isEqualTo("OFFERED");

        MvcResult missing = mvc.perform(post(BASE + "/offers/" + offer + "/accept")
                        .with(tokenFor(ALISHER))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(missing).path("code").asText()).isEqualTo("INVALID_REQUEST");

        MvcResult noKey = mvc.perform(post(BASE + "/offers/" + offer + "/accept")
                        .with(tokenFor(ALISHER))
                        .header(HttpHeaders.IF_MATCH, "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(noKey).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(attemptStatus(offer)).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("an accept with no body at all is an accept with no position")
    void anAcceptNeedsNoBodyWhenNothingIsMeasured() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult accepted = mvc.perform(post(BASE + "/offers/" + offer + "/accept")
                        .with(tokenFor(ALISHER))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, "W/\"1\""))
                .andReturn();

        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(accepted).path("outcome").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("replaying the same accept with the same key settles once and says so")
    void aReplayedAcceptIsNotASecondAcceptance() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult first = accept(ALISHER, offer, 1, null, "accept-once");
        MvcResult replay = accept(ALISHER, offer, 1, null, "accept-once");

        assertThat(json(first).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay).path("delivery").path("shipmentId").asText())
                .isEqualTo(json(first).path("delivery").path("shipmentId").asText());
        assertThat(shipmentCount()).isEqualTo(1);
        assertThat(auditCount("courier.offer.accepted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a courier whose registration has lapsed cannot take new work, but finishes what they carry")
    void aSuspendedEngagementStopsNewWorkOnly() throws Exception {
        UUID carried = seedOffer(COURIER_ALISHER, "READY");
        UUID shipment = acceptedShipment(ALISHER, carried);
        UUID another = seedOffer(COURIER_ALISHER, "READY");

        UUID engagementId = courierStore
                .findLiveEngagement(TENANT, COURIER_ALISHER)
                .orElseThrow()
                .id();
        assertThat(courierStore.suspend(
                        TENANT,
                        engagementId,
                        EngagementStatus.SUSPENDED_COMPLIANCE,
                        "REGISTRATION_LAPSED",
                        RegistrationWarningState.LAPSED,
                        Instant.now()))
                .as("the same call the compliance sweeper makes when a registration lapses")
                .isTrue();

        MvcResult refused = accept(ALISHER, another, 1, null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(refused)).isEqualTo("COURIER_NOT_ELIGIBLE");
        assertThat(attemptStatus(another)).isEqualTo("OFFERED");

        MvcResult finished = advance(ALISHER, shipment, versionOf(shipment), "PICKED_UP", null);
        assertThat(finished.getResponse().getStatus())
                .as("work already accepted still finishes (ADR 0042)")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ the GPS gate

    @Test
    @DisplayName("with the GPS toggle off, a courier on the other side of the city may take and move a delivery")
    void theToggleOffChecksNothing() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult accepted = accept(ALISHER, offer, 1, position(DOOR_LATITUDE + 0.2, DOOR_LONGITUDE + 0.2, 5));
        assertThat(json(accepted).path("outcome").asText()).isEqualTo("ACCEPTED");
        UUID shipment = UUID.fromString(
                json(accepted).path("delivery").path("shipmentId").asText());

        MvcResult moved = advance(ALISHER, shipment, versionOf(shipment), "PICKED_UP", null);
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(moved).path("status").asText()).isEqualTo("PICKED_UP");
    }

    @Test
    @DisplayName(
            "with the GPS gate on, the accept radius governs taking an offer and the status radius every step after")
    void theTwoRadiiAreEnforcedSeparately() throws Exception {
        publishPolicy(Map.of(
                "gpsVerificationEnabled", true, "gpsAcceptRadiusMeters", 400, "gpsStatusChangeRadiusMeters", 60));
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        double nearBranch = BRANCH_LATITUDE + 0.00027;
        double threeHundredMetres = BRANCH_LATITUDE + 0.0027;
        double twoKilometres = BRANCH_LATITUDE + 0.018;
        assertThat(Haversine.metersBetween(
                        new GeoPoint(BRANCH_LATITUDE, BRANCH_LONGITUDE), new GeoPoint(nearBranch, BRANCH_LONGITUDE)))
                .isLessThan(60);
        assertThat(Haversine.metersBetween(
                        new GeoPoint(BRANCH_LATITUDE, BRANCH_LONGITUDE),
                        new GeoPoint(threeHundredMetres, BRANCH_LONGITUDE)))
                .as("the fixture distance must sit between the two radii for this test to separate them")
                .isBetween(61, 399);

        MvcResult none = accept(ALISHER, offer, 1, null);
        assertThat(none.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(none).path("code").asText()).isEqualTo("UNPROCESSABLE_STATE");
        assertThat(reason(none)).isEqualTo("GPS_POSITION_REQUIRED");

        MvcResult far = accept(ALISHER, offer, 1, position(twoKilometres, BRANCH_LONGITUDE, 8));
        assertThat(far.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(far)).isEqualTo("TOO_FAR_FROM_PICKUP");
        assertThat(far.getResponse().getContentAsString(UTF_8))
                .as("the courier's position is measured and discarded, never echoed")
                .doesNotContain(String.valueOf(twoKilometres));
        assertThat(attemptStatus(offer))
                .as("a refused acceptance changed nothing")
                .isEqualTo("OFFERED");
        assertThat(shipmentCount()).isZero();

        MvcResult coarse = accept(ALISHER, offer, 1, position(nearBranch, BRANCH_LONGITUDE, 900));
        assertThat(reason(coarse))
                .as("a 900 m error circle cannot show anyone is within 400 m")
                .isEqualTo("GPS_ACCURACY_INSUFFICIENT");

        MvcResult accepted = accept(ALISHER, offer, 1, position(threeHundredMetres, BRANCH_LONGITUDE, 8));
        assertThat(json(accepted).path("outcome").asText())
                .as("300 m is outside the 60 m status radius and inside the 400 m accept radius")
                .isEqualTo("ACCEPTED");
        UUID shipment = UUID.fromString(
                json(accepted).path("delivery").path("shipmentId").asText());

        MvcResult arrivedFar = advance(
                ALISHER,
                shipment,
                versionOf(shipment),
                "PICKUP_PENDING",
                position(threeHundredMetres, BRANCH_LONGITUDE, 8));
        assertThat(arrivedFar.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(arrivedFar)).isEqualTo("TOO_FAR_FROM_PICKUP");
        assertThat(shipmentStatus(shipment)).isEqualTo("ASSIGNED");

        MvcResult arrived = advance(
                ALISHER, shipment, versionOf(shipment), "PICKUP_PENDING", position(nearBranch, BRANCH_LONGITUDE, 8));
        assertThat(arrived.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(arrived).path("status").asText()).isEqualTo("PICKUP_PENDING");

        MvcResult pickedUpFar = advance(
                ALISHER, shipment, versionOf(shipment), "PICKED_UP", position(threeHundredMetres, BRANCH_LONGITUDE, 8));
        assertThat(reason(pickedUpFar)).isEqualTo("TOO_FAR_FROM_PICKUP");

        MvcResult pickedUp =
                advance(ALISHER, shipment, versionOf(shipment), "PICKED_UP", position(nearBranch, BRANCH_LONGITUDE, 8));
        assertThat(json(pickedUp).path("status").asText()).isEqualTo("PICKED_UP");
        assertThat(json(pickedUp).path("pickedUpAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("handing over is measured against the customer's door, not the branch")
    void theHandoverIsMeasuredAtTheDoor() throws Exception {
        UUID shipment = pickedUpShipment(ALISHER, "READY", true);
        // Switched on only now: the delivery was taken and collected under the default policy, and
        // the gate is about the step still to come.
        publishPolicy(Map.of(
                "gpsVerificationEnabled", true, "gpsAcceptRadiusMeters", 400, "gpsStatusChangeRadiusMeters", 60));

        MvcResult atTheBranch = advance(
                ALISHER, shipment, versionOf(shipment), "DELIVERED", position(BRANCH_LATITUDE, BRANCH_LONGITUDE, 5));
        assertThat(atTheBranch.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(atTheBranch))
                .as("standing at the branch is not standing at the door")
                .isEqualTo("TOO_FAR_FROM_DROPOFF");
        assertThat(shipmentStatus(shipment)).isEqualTo("PICKED_UP");

        MvcResult noPosition = advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null);
        assertThat(reason(noPosition)).isEqualTo("GPS_POSITION_REQUIRED");

        MvcResult atTheDoor = advance(
                ALISHER,
                shipment,
                versionOf(shipment),
                "DELIVERED",
                position(DOOR_LATITUDE + 0.0002, DOOR_LONGITUDE, 5));
        assertThat(atTheDoor.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(atTheDoor).path("status").asText()).isEqualTo("DELIVERED");
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");

        assertThat(latestAuditEvent("courier.delivery.advanced")
                        .path("change_document")
                        .toString())
                .as("the audit says the position was checked, never where the courier was")
                .contains("positionChecked")
                .doesNotContain(String.valueOf(DOOR_LATITUDE))
                .doesNotContain(String.valueOf(BRANCH_LATITUDE));
    }

    @Test
    @DisplayName("a branch with no coordinate cannot be measured against, so the gate refuses rather than opens")
    void anUnplacedBranchRefusesWhenTheGateIsOn() throws Exception {
        publishPolicy(Map.of("gpsVerificationEnabled", true));
        jdbc.sql("UPDATE tenant.locations SET latitude = NULL, longitude = NULL, coordinate_source = 'NOT_GEOCODED' "
                        + "WHERE id = :id")
                .param("id", LOCATION)
                .update();
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult refused = accept(ALISHER, offer, 1, position(BRANCH_LATITUDE, BRANCH_LONGITUDE, 5));

        assertThat(reason(refused)).isEqualTo("GPS_REFERENCE_UNAVAILABLE");
        assertThat(attemptStatus(offer)).isEqualTo("OFFERED");
    }

    // ------------------------------------------------------------- kitchen-ready only

    @Test
    @DisplayName(
            "kitchen-ready-only hides a cooking order from the list and refuses it, and an unset switch does neither")
    void kitchenReadyOnly() throws Exception {
        UUID cooking = seedOffer(COURIER_ALISHER, "PREPARING");

        assertThat(json(mvc.perform(get(BASE + "/offers").with(tokenFor(ALISHER)))
                        .andReturn()))
                .as("with the switch off a cooking order is offered, as it always was")
                .hasSize(1);

        publishPolicy(Map.of("kitchenReadyOnly", true));

        assertThat(json(mvc.perform(get(BASE + "/offers").with(tokenFor(ALISHER)))
                        .andReturn()))
                .as("the setting's own sentence: the courier sees and can take only ready orders")
                .isEmpty();
        MvcResult refused = accept(ALISHER, cooking, 1, null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(refused)).isEqualTo("KITCHEN_NOT_READY");
        assertThat(attemptStatus(cooking)).isEqualTo("OFFERED");

        jdbc.sql("UPDATE ordering.orders SET status = 'READY' WHERE id = :id")
                .param("id", orderOfAttempt(cooking))
                .update();
        assertThat(json(mvc.perform(get(BASE + "/offers").with(tokenFor(ALISHER)))
                        .andReturn()))
                .as("the same offer appears the moment the kitchen finishes")
                .hasSize(1);
        assertThat(json(accept(ALISHER, cooking, 1, null)).path("outcome").asText())
                .isEqualTo("ACCEPTED");
    }

    // ------------------------------------------------------------------- the reveal

    @Test
    @DisplayName(
            "the default policy shows the customer's door only after acceptance; the reveal is audited without the address")
    void revealAfterAcceptByDefault() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        MvcResult early = revealOffer(ALISHER, offer);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(early)).isEqualTo("LOCATION_NOT_YET_REVEALED");
        assertThat(early.getResponse().getContentAsString(UTF_8)).doesNotContain(STREET_LINE);
        assertThat(auditCount("courier.customer_location.revealed"))
                .as("a refused reveal opens nothing, so it records no reveal")
                .isZero();

        UUID shipment = UUID.fromString(json(accept(ALISHER, offer, 1, null))
                .path("delivery")
                .path("shipmentId")
                .asText());
        MvcResult opened = revealDelivery(ALISHER, shipment, "reveal-after-accept");

        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        JsonNode door = json(opened);
        assertThat(door.path("addressLine").asText()).contains(STREET_LINE);
        assertThat(door.path("latitude").asDouble()).isEqualTo(DOOR_LATITUDE);
        assertThat(door.path("longitude").asDouble()).isEqualTo(DOOR_LONGITUDE);
        assertThat(door.path("instructions").asText()).isEqualTo(GATE_NOTE);
        assertThat(opened.getResponse().getContentAsString(UTF_8))
                .as("the door, and not the person: the name and number stay sealed")
                .doesNotContain(CUSTOMER_NAME)
                .doesNotContain(CUSTOMER_PHONE);

        JsonNode audit = auditEvent("courier.customer_location.revealed");
        assertThat(audit.path("audit_class").asText()).isEqualTo("SECURITY");
        assertThat(audit.path("actor_subject").asText()).isEqualTo(ALISHER);
        assertThat(audit.path("target_id").asText()).isEqualTo(shipment.toString());
        assertThat(audit.path("capability_used").asText()).isEqualTo("courier.delivery.location.reveal");
        String recorded = audit.toString();
        assertThat(recorded)
                .contains(COURIER_ALISHER.toString())
                .contains(orderOfShipment(shipment).toString())
                .doesNotContain(STREET_LINE)
                .doesNotContain(GATE_NOTE)
                .doesNotContain(String.valueOf(DOOR_LATITUDE))
                .doesNotContain(CUSTOMER_NAME);

        assertThat(jdbc.sql("SELECT response_body FROM platform.idempotency_records WHERE response_body IS NOT NULL")
                        .query(String.class)
                        .list())
                .as("the idempotency record of a reveal must not hold the address in clear (ADR 0029)")
                .allSatisfy(body -> assertThat(body).doesNotContain(STREET_LINE).doesNotContain(GATE_NOTE));
    }

    @Test
    @DisplayName(
            "when the tenant reveals before acceptance, the offer's door opens, audited, and only for the courier it was offered to")
    void revealBeforeAccept() throws Exception {
        publishPolicy(Map.of("revealCustomerLocationTiming", "BEFORE_ACCEPT"));
        UUID offer = seedOffer(COURIER_ALISHER, "READY");

        JsonNode listed =
                json(mvc.perform(get(BASE + "/offers").with(tokenFor(ALISHER))).andReturn());
        assertThat(listed.get(0).path("customerLocationRevealable").asBoolean()).isTrue();

        assertThat(revealOffer(BOBUR, offer).getResponse().getStatus())
                .as("the other courier is not told the offer exists")
                .isEqualTo(404);

        MvcResult opened = revealOffer(ALISHER, offer);
        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(opened).path("addressLine").asText()).contains(STREET_LINE);
        assertThat(attemptStatus(offer))
                .as("looking at the door is not taking the offer")
                .isEqualTo("OFFERED");

        JsonNode audit = auditEvent("courier.customer_location.revealed");
        assertThat(audit.path("target_id").asText()).isEqualTo(offer.toString());
        assertThat(audit.toString()).doesNotContain(STREET_LINE).doesNotContain(String.valueOf(DOOR_LONGITUDE));
    }

    @Test
    @DisplayName(
            "the customer-location read answers only for its own tenant's delivery orders, and never prints the door")
    void theCustomerLocationReadIsScoped() {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");
        UUID order = orderOfAttempt(offer);

        var door = deliveryOrders.customerLocation(TENANT, order, "TEST").orElseThrow();
        assertThat(door.addressLine()).contains(STREET_LINE);
        assertThat(door.instructions()).isEqualTo(GATE_NOTE);
        assertThat(door.toString())
                .as("a record's generated toString would put a home address into one interpolated log line")
                .doesNotContain(STREET_LINE)
                .doesNotContain(String.valueOf(DOOR_LATITUDE));

        assertThat(deliveryOrders.customerLocation(OTHER_TENANT, order, "TEST"))
                .as("an order id is not proof of anything; the other tenant holds it and gets nothing")
                .isEmpty();

        jdbc.sql("UPDATE ordering.orders SET fulfillment_mode = 'PICKUP' WHERE id = :id")
                .param("id", order)
                .update();
        assertThat(deliveryOrders.customerLocation(TENANT, order, "TEST"))
                .as("a pickup order has no door to send anybody to")
                .isEmpty();
    }

    @Test
    @DisplayName("a finished delivery no longer reveals where its customer lives")
    void noRevealOnceDelivered() throws Exception {
        UUID shipment = pickedUpShipment(ALISHER, "READY", true);
        assertThat(revealDelivery(ALISHER, shipment, "reveal-while-carrying")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(revealDelivery(ALISHER, shipment, "reveal-after-delivery")
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(revealDelivery(BOBUR, shipment, "reveal-by-stranger")
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    // ----------------------------------------------------------- the payment check

    @Test
    @DisplayName(
            "with the payment check on, a cash delivery is not delivered until the courier states exactly the cash due")
    void thePaymentCheck() throws Exception {
        publishPolicy(Map.of("postDeliveryPaymentCheckRequired", true));
        UUID shipment = pickedUpShipment(ALISHER, "READY", false);
        cashDue.byOrder.put(orderOfShipment(shipment), 15_000L); // part of the 20,000 was paid in loyalty points

        JsonNode before = json(mvc.perform(get(BASE + "/deliveries/" + shipment).with(tokenFor(ALISHER)))
                .andReturn());
        assertThat(before.path("cashDueMinor").asLong()).isEqualTo(15_000L);
        assertThat(before.path("orderTotalMinor").asLong()).isEqualTo(20_000L);
        assertThat(before.path("paymentConfirmationRequired").asBoolean()).isTrue();

        MvcResult early = advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(early)).isEqualTo("PAYMENT_CONFIRMATION_REQUIRED");
        assertThat(shipmentStatus(shipment)).isEqualTo("PICKED_UP");

        MvcResult wrong = confirmPayment(ALISHER, shipment, versionOf(shipment), 20_000L);
        assertThat(wrong.getResponse().getStatus())
                .as("the order total is not the cash due: the customer's points paid the rest")
                .isEqualTo(422);
        assertThat(reason(wrong)).isEqualTo("PAYMENT_AMOUNT_MISMATCH");
        assertThat(jdbc.sql("SELECT payment_confirmed_at FROM fulfillment.shipments WHERE id = :id")
                        .param("id", shipment)
                        .query(Instant.class)
                        .optional())
                .as("a refused confirmation stores nothing")
                .isEmpty();

        MvcResult confirmed = confirmPayment(ALISHER, shipment, versionOf(shipment), 15_000L);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(confirmed).path("paymentConfirmedMinor").asLong()).isEqualTo(15_000L);
        assertThat(json(confirmed).path("paymentConfirmedAt").isNull()).isFalse();
        assertThat(auditEvent("courier.delivery.payment_confirmed")
                        .path("change_document")
                        .toString())
                .contains("15000");

        MvcResult delivered = advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null);
        assertThat(delivered.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(delivered).path("status").asText()).isEqualTo("DELIVERED");
        assertThat(json(delivered).path("deliveredAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("an order the platform already holds the money for needs no cash confirmation, even with the check on")
    void aPrepaidOrderHasNothingToCount() throws Exception {
        publishPolicy(Map.of("postDeliveryPaymentCheckRequired", true));
        UUID shipment = pickedUpShipment(ALISHER, "READY", true);

        JsonNode view = json(mvc.perform(get(BASE + "/deliveries/" + shipment).with(tokenFor(ALISHER)))
                .andReturn());
        assertThat(view.path("prepaid").asBoolean()).isTrue();
        assertThat(view.path("cashDueMinor").asLong()).isZero();
        assertThat(view.path("paymentConfirmationRequired").asBoolean()).isFalse();

        assertThat(advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("with the check off, cash due and unconfirmed delivers as it always did")
    void thePaymentCheckOffChangesNothing() throws Exception {
        UUID shipment = pickedUpShipment(ALISHER, "READY", false);

        assertThat(advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("cash cannot be confirmed before the bag is picked up")
    void cashIsConfirmedAtTheDoorNotBefore() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");
        UUID shipment = acceptedShipment(ALISHER, offer);

        MvcResult early = confirmPayment(ALISHER, shipment, versionOf(shipment), 20_000L);

        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(early)).isEqualTo("PAYMENT_NOT_CONFIRMABLE");
    }

    // ------------------------------------------------------------ steps and ownership

    @Test
    @DisplayName("a delivery takes its steps in order: arrival may be skipped, handover may not")
    void stepsAreInOrder() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");
        UUID shipment = acceptedShipment(ALISHER, offer);

        MvcResult skipped = advance(ALISHER, shipment, versionOf(shipment), "DELIVERED", null);
        assertThat(skipped.getResponse().getStatus()).isEqualTo(422);
        assertThat(reason(skipped)).isEqualTo("STEP_NOT_ALLOWED");
        assertThat(shipmentStatus(shipment)).isEqualTo("ASSIGNED");

        MvcResult pickedUp = advance(ALISHER, shipment, versionOf(shipment), "PICKED_UP", null);
        assertThat(pickedUp.getResponse().getStatus())
                .as("walking in and being handed the bag is a legitimate shortcut")
                .isEqualTo(200);

        MvcResult again = advance(ALISHER, shipment, versionOf(shipment), "PICKED_UP", null);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(again).path("status").asText()).isEqualTo("PICKED_UP");

        MvcResult backwards = advance(ALISHER, shipment, versionOf(shipment), "PICKUP_PENDING", null);
        assertThat(reason(backwards)).as("a delivery never moves backwards").isEqualTo("STEP_NOT_ALLOWED");

        MvcResult stale = advance(ALISHER, shipment, 1, "DELIVERED", null);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).path("code").asText()).isEqualTo("STALE_VERSION");
    }

    @Test
    @DisplayName(
            "another courier, another tenant's courier and a non-courier all see 404 for a delivery that is not theirs")
    void deliveriesBelongToTheirCourierOnly() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");
        UUID shipment = acceptedShipment(ALISHER, offer);
        long version = versionOf(shipment);

        for (String stranger : List.of(BOBUR, ELSEWHERE, NOT_A_COURIER, MANAGER)) {
            assertThat(mvc.perform(get(BASE + "/deliveries/" + shipment).with(tokenFor(stranger)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("%s reading", stranger)
                    .isEqualTo(404);
            assertThat(advance(stranger, shipment, version, "PICKED_UP", null)
                            .getResponse()
                            .getStatus())
                    .as("%s advancing", stranger)
                    .isEqualTo(404);
            assertThat(confirmPayment(stranger, shipment, version, 20_000L)
                            .getResponse()
                            .getStatus())
                    .as("%s confirming cash", stranger)
                    .isEqualTo(404);
        }
        assertThat(shipmentStatus(shipment))
                .as("nothing a stranger tried moved the delivery")
                .isEqualTo("ASSIGNED");

        assertThat(json(mvc.perform(get(BASE + "/deliveries").with(tokenFor(BOBUR)))
                        .andReturn()))
                .as("Bobur carries nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a courier of one branch cannot read, take or move another branch's work through their own path")
    void aBranchPathCannotBeUsedToReachAnotherBranch() throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, "READY");
        String otherBranchPath =
                "/api/v1/courier/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + UUID.randomUUID();

        assertThat(json(mvc.perform(get(otherBranchPath + "/offers").with(tokenFor(ALISHER)))
                        .andReturn()))
                .as("the offer belongs to the real branch, not to whichever branch id the path names")
                .isEmpty();
        MvcResult accepted = mvc.perform(post(otherBranchPath + "/offers/" + offer + "/accept")
                        .with(tokenFor(ALISHER))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(json(accepted).path("outcome").asText()).isEqualTo("NO_LONGER_AVAILABLE");
        assertThat(attemptStatus(offer)).isEqualTo("OFFERED");
    }

    // ----------------------------------------------------------------- fixtures

    private MvcResult accept(String subject, UUID offerId, long version, @Nullable String positionJson)
            throws Exception {
        return accept(subject, offerId, version, positionJson, nextKey());
    }

    private MvcResult accept(String subject, UUID offerId, long version, @Nullable String positionJson, String key)
            throws Exception {
        return mvc.perform(post(BASE + "/offers/" + offerId + "/accept")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(positionJson == null ? "{}" : "{\"position\":" + positionJson + "}"))
                .andReturn();
    }

    private MvcResult decline(String subject, UUID offerId, long version) throws Exception {
        return mvc.perform(post(BASE + "/offers/" + offerId + "/decline")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    private MvcResult advance(String subject, UUID shipmentId, long version, String step, @Nullable String positionJson)
            throws Exception {
        String body = positionJson == null
                ? "{\"step\":\"" + step + "\"}"
                : "{\"step\":\"" + step + "\",\"position\":" + positionJson + "}";
        return mvc.perform(post(BASE + "/deliveries/" + shipmentId + "/advance")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult confirmPayment(String subject, UUID shipmentId, long version, long collectedMinor)
            throws Exception {
        return mvc.perform(post(BASE + "/deliveries/" + shipmentId + "/payment-confirmation")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectedMinor\":" + collectedMinor + "}"))
                .andReturn();
    }

    private MvcResult revealOffer(String subject, UUID offerId) throws Exception {
        return mvc.perform(post(BASE + "/offers/" + offerId + "/customer-location-reveals")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    private MvcResult revealDelivery(String subject, UUID shipmentId, String key) throws Exception {
        return mvc.perform(post(BASE + "/deliveries/" + shipmentId + "/customer-location-reveals")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    private static String position(double latitude, double longitude, double accuracyMeters) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + ",\"accuracyMeters\":" + accuracyMeters
                + "}";
    }

    private String nextKey() {
        return "courier-delivery-" + keys.incrementAndGet();
    }

    /** An offer taken over HTTP, which is the only way production creates a courier's shipment. */
    private UUID acceptedShipment(String subject, UUID offerId) throws Exception {
        MvcResult accepted = accept(subject, offerId, 1, null);
        assertThat(json(accepted).path("outcome").asText()).isEqualTo("ACCEPTED");
        return UUID.fromString(
                json(accepted).path("delivery").path("shipmentId").asText());
    }

    /** A shipment the courier has accepted and collected, ready to be handed over. */
    private UUID pickedUpShipment(String subject, String orderStatus, boolean prepaid) throws Exception {
        UUID offer = seedOffer(COURIER_ALISHER, orderStatus, prepaid);
        UUID shipment = acceptedShipment(subject, offer);
        MvcResult pickedUp = advance(subject, shipment, versionOf(shipment), "PICKED_UP", null);
        assertThat(pickedUp.getResponse().getStatus()).isEqualTo(200);
        return shipment;
    }

    private long versionOf(UUID shipmentId) {
        return jdbc.sql("SELECT version FROM fulfillment.shipments WHERE id = :id")
                .param("id", shipmentId)
                .query(Long.class)
                .single();
    }

    private String shipmentStatus(UUID shipmentId) {
        return jdbc.sql("SELECT status FROM fulfillment.shipments WHERE id = :id")
                .param("id", shipmentId)
                .query(String.class)
                .single();
    }

    private String attemptStatus(UUID attemptId) {
        return jdbc.sql("SELECT status FROM fulfillment.assignment_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(String.class)
                .single();
    }

    private long shipmentCount() {
        return jdbc.sql("SELECT count(*) FROM fulfillment.shipments")
                .query(Long.class)
                .single();
    }

    private UUID planOfAttempt(UUID attemptId) {
        return jdbc.sql("SELECT delivery_plan_id FROM fulfillment.assignment_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(UUID.class)
                .single();
    }

    private UUID orderOfAttempt(UUID attemptId) {
        return jdbc.sql("SELECT p.order_id FROM fulfillment.assignment_attempts a "
                        + "JOIN fulfillment.delivery_plans p ON p.id = a.delivery_plan_id WHERE a.id = :id")
                .param("id", attemptId)
                .query(UUID.class)
                .single();
    }

    private UUID orderOfShipment(UUID shipmentId) {
        return jdbc.sql("SELECT order_id FROM fulfillment.shipments WHERE id = :id")
                .param("id", shipmentId)
                .query(UUID.class)
                .single();
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    /** The one audit row of this action, as JSON, so a test reads a column by name. */
    private JsonNode auditEvent(String action) throws Exception {
        List<String> rows =
                jdbc.sql("""
                        SELECT row_to_json(e)::text FROM (
                            SELECT audit_class, action_code, actor_subject, target_type, target_id,
                                   capability_used, reason, change_document, correlation_id
                            FROM audit.audit_events WHERE action_code = :action) e
                        """).param("action", action).query(String.class).list();
        assertThat(rows).as("audit rows for %s", action).hasSize(1);
        return JSON.readTree(rows.get(0));
    }

    /** The most recent audit row of this action, for tests that legitimately produce several. */
    private JsonNode latestAuditEvent(String action) throws Exception {
        List<String> rows =
                jdbc.sql("""
                        SELECT row_to_json(e)::text FROM (
                            SELECT audit_class, action_code, actor_subject, target_type, target_id,
                                   capability_used, reason, change_document, correlation_id
                            FROM audit.audit_events WHERE action_code = :action
                            ORDER BY occurred_at DESC LIMIT 1) e
                        """).param("action", action).query(String.class).list();
        assertThat(rows).as("audit rows for %s", action).hasSize(1);
        return JSON.readTree(rows.get(0));
    }

    private static String reason(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8))
                .path("reason")
                .asText();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    /**
     * Publishes a new courier-policy version the way an operator does: read the version in force,
     * then write the whole document with that version as {@code If-Match}.
     */
    private void publishPolicy(Map<String, Object> overrides) throws Exception {
        String path = "/api/v1/operations/tenants/" + TENANT + "/courier-policy";
        MvcResult current = mvc.perform(get(path).with(tokenFor(MANAGER))).andReturn();
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        String etag = java.util.Objects.requireNonNull(current.getResponse().getHeader(HttpHeaders.ETAG));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reverificationDays", 180);
        body.put("warningDays", 30);
        body.put("settlementPeriodDays", 14);
        body.put("cashCeilingMinor", 5_000_000);
        body.put("penaltyApprovalThresholdMinor", 200_000);
        body.put("shiftEnforcement", "ADVISORY");
        body.put("graceSeconds", 300);
        body.put("confirmationPointRetentionDays", 30);
        body.put("gpsVerificationEnabled", false);
        body.put("gpsAcceptRadiusMeters", 1000);
        body.put("gpsStatusChangeRadiusMeters", 150);
        body.put("kitchenReadyOnly", false);
        body.put("revealCustomerLocationTiming", "AFTER_ACCEPT");
        body.put("postDeliveryPaymentCheckRequired", false);
        body.put("onlineWithinMinutes", 10);
        body.put("reason", "Courier delivery endpoint test");
        body.putAll(overrides);

        MvcResult written = mvc.perform(put(path)
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
        assertThat(written.getResponse().getStatus())
                .as("publishing the courier policy: %s", written.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
    }

    private UUID seedOffer(UUID courierId, String orderStatus) {
        return seedOffer(courierId, orderStatus, false);
    }

    /** An order, its plan, and an unexpired OFFERED internal attempt made to {@code courierId}. */
    private UUID seedOffer(UUID courierId, String orderStatus, boolean prepaid) {
        UUID orderId = seedDeliveryOrder(orderStatus, prepaid);
        UUID planId = seedDeliveryPlan(orderId);
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.assignment_attempts (id, tenant_id, delivery_plan_id, sequence_number,
                    source_type, courier_id, status, idempotency_key, decision_reason, requested_at, expires_at,
                    version)
                VALUES (:id, :t, :plan, 1, 'INTERNAL', :courier, 'OFFERED', :key, 'INTERNAL_COURIER_AVAILABLE',
                    now(), now() + interval '10 minutes', 1)
                """)
                .param("id", attemptId)
                .param("t", TENANT)
                .param("plan", planId)
                .param("courier", courierId)
                .param("key", "offer-" + attemptId)
                .update();
        return attemptId;
    }

    private UUID seedDeliveryOrder(String status, boolean prepaid) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, payment_status_projection, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', :status, :payment,
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", "CD-" + orderId.toString().substring(0, 8))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("status", status)
                .param("payment", prepaid ? "CAPTURED" : "NOT_REQUIRED")
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        sealCustomerSnapshot(orderId);
        return orderId;
    }

    /** The customer's name, number, address and gate note, sealed exactly as checkout seals them. */
    private void sealCustomerSnapshot(UUID orderId) {
        DeliveryDestination destination = new DeliveryDestination(
                STREET_LINE, "", "Tashkent", "Chilonzor", "100000", "2", "5", "17", "", DOOR_LATITUDE, DOOR_LONGITUDE);
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id, display_name_encrypted,
                    contact_encrypted, address_encrypted, delivery_instructions_encrypted)
                VALUES (:order, :t, :name, :contact, :address, :instructions)
                """)
                .param("order", orderId)
                .param("t", TENANT)
                .param("name", seal(orderId, "display_name_encrypted", CUSTOMER_NAME))
                .param("contact", seal(orderId, "contact_encrypted", CUSTOMER_PHONE))
                .param("address", seal(orderId, "address_encrypted", objectMapper.writeValueAsString(destination)))
                .param("instructions", seal(orderId, "delivery_instructions_encrypted", GATE_NOTE))
                .update();
    }

    private String seal(UUID orderId, String column, String plaintext) {
        return protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new RecordRef("ordering.order_customer_snapshots", column, orderId),
                        plaintext)
                .serialize();
    }

    /** A minimal, directly inserted plan -- delivery planning itself is not what this suite proves. */
    private UUID seedDeliveryPlan(UUID orderId) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id, order_id,
                    status, currency, customer_delivery_fee_minor, confirmed_at, preparation_seconds,
                    estimated_ready_at, pickup_window_start, pickup_window_end, promised_delivery_start,
                    promised_delivery_end, source_at, latest_assignment_at, branch_zone, distance_meters,
                    distance_source, destination_label, version)
                VALUES (:id, :t, :b, :loc, :orderId, 'SOURCING', 'UZS', 10000, :confirmedAt, 900,
                    :readyAt, :pickupStart, :pickupEnd, :promiseStart, :promiseEnd, :sourceAt,
                    :latestAssignmentAt, 'Asia/Tashkent', 2100, 'RADIUS', 'Chilonzor', 1)
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("confirmedAt", now.atOffset(ZoneOffset.UTC))
                .param("readyAt", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("pickupStart", now.plusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("pickupEnd", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .param("promiseStart", now.plusSeconds(1_800).atOffset(ZoneOffset.UTC))
                .param("promiseEnd", now.plusSeconds(3_000).atOffset(ZoneOffset.UTC))
                .param("sourceAt", now.atOffset(ZoneOffset.UTC))
                .param("latestAssignmentAt", now.plusSeconds(1_200).atOffset(ZoneOffset.UTC))
                .update();
        return planId;
    }

    private void seedTenancy(
            UUID tenantId, UUID brandId, UUID locationId, UUID courierTypeId, double latitude, double longitude) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "courier-delivery-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source, address_line,
                    district, city)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0,
                    :lat, :lon, 'MERCHANT_PIN', 'Amir Temur 1', 'Yunusobod', 'Tashkent')
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("lat", latitude)
                .param("lon", longitude)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", courierTypeId).param("t", tenantId).update();
    }

    private void seedCatalog() {
        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    private void insertCourier(UUID courierId, UUID tenantId, UUID courierTypeId, String subject) {
        jdbc.sql("""
                INSERT INTO fulfillment.couriers
                    (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'ciphertext-not-exercised-here', 'ACTIVE')
                """)
                .param("id", courierId)
                .param("tenantId", tenantId)
                .param("typeId", courierTypeId)
                .param("subject", subject)
                .param("reference", "REF-" + courierId.toString().substring(30))
                .update();
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
                        'ACTIVE', 'test-fixture', 'courier delivery endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    /**
     * What the settlement says is due in cash, per order. Unset orders throw, as the real lookup does
     * for an order it holds no settlement for, so the order-total fallback is what a test sees unless
     * it states a figure.
     */
    static final class StubCashDue implements CashDueLookupPort {

        final Map<UUID, Long> byOrder = new ConcurrentHashMap<>();

        @Override
        public long cashDueMinor(UUID tenantId, UUID orderId) {
            Long due = byOrder.get(orderId);
            if (due == null) {
                throw new IllegalStateException("No settlement for " + orderId);
            }
            return due;
        }
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

        @Bean
        @Primary
        StubCashDue stubCashDue() {
            return new StubCashDue();
        }

        /**
         * Replaces the Kafka publisher, whose first send blocks for the producer's whole
         * {@code max.block.ms} when no broker is reachable, as here. The signal a courier's tap raises is
         * what the dispatch board redraws on, so it is recorded and asserted rather than dropped.
         */
        @Bean
        @Primary
        RecordingSignals recordingSignals() {
            return new RecordingSignals();
        }
    }
}
