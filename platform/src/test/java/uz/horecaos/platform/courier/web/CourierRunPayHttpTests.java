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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.application.ApprovalDecisionService;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.CourierSettlementService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.DeliveryAccrualOrderCompletionTrigger;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.fulfillment.application.DeliveryPlanningService;
import uz.horecaos.platform.fulfillment.application.DeliverySourcingRunner;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.payments.api.CashDueLookupPort;
import uz.horecaos.platform.payments.settlement.OrderSettlementService;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * How a courier is paid for a run of several orders, from the policy to the statement line, through the real
 * HTTP stack and the real sourcing path (ADR 0170, gap map row 3.8).
 *
 * <p>Nothing here writes a stamp, a run key or an earning by hand. The tenant decides the pay by {@code PUT
 * .../courier-policy}, publishes a rule that groups by {@code PUT .../dispatch-rules}, and two orders are planned and
 * sourced by the production planner and runner against a courier who is on shift and carrying the first; the
 * courier reads the offers and takes them over HTTP, and the accrual is what the production completion trigger
 * writes. The properties that matter live in the seams between those steps -- the stamp the offer was made with,
 * the key the acceptance gave both shipments, the distance the earning was paid on -- which is why a unit test of
 * any one of them would not have found a fault in the join.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourierRunPayHttpTests {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fe300-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe300-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe300-4000-7000-8000-0000000000c1");
    private static final UUID COURIER_TYPE = UUID.fromString("018fe300-4000-7000-8000-0000000000d1");
    private static final UUID COURIER = UUID.fromString("018fe300-4000-7000-8000-0000000000e1");

    private static final String RIDER = "keycloak-run-rider";
    private static final String MANAGER = "run-pay-manager";
    private static final String SECOND_SIGNATURE = "run-pay-second-signature";

    /** Amir Temur square, where the branch is. */
    private static final double BRANCH_LATITUDE = 41.311081;

    private static final double BRANCH_LONGITUDE = 69.240562;
    private static final double METRES_PER_LATITUDE_DEGREE = 111_320.0;
    private static final String CUSTOMER_NAME = "Dilnoza Karimova";
    private static final String STREET_LINE = "Bunyodkor 14";

    private static final String COURIER_BASE =
            "/api/v1/courier/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION;
    private static final String POLICY_PATH = "/api/v1/operations/tenants/" + TENANT + "/courier-policy";
    private static final String RULES_PATH = "/api/v1/operations/tenants/" + TENANT + "/dispatch-rules";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the run pay test");
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
        // The platform's kill switch is on in this class; the tenant's own pay decision is what is under test.
        registry.add("horecaos.fulfillment.dispatch.grouping-enabled", () -> "true");
        // The schedulers poll the very tables reset() truncates; the planner and the runner are driven by hand.
        registry.add("horecaos.fulfillment.sourcing.enabled", () -> "false");
        registry.add("horecaos.ordering.workers.enabled", () -> "false");
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
    private DeliveryPlanningService planning;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcSourcingJobStore jobStore;

    @Autowired
    @SuppressWarnings("NullAway")
    private DeliverySourcingRunner runner;

    @Autowired
    @SuppressWarnings("NullAway")
    private CourierShiftService shifts;

    @Autowired
    @SuppressWarnings("NullAway")
    private CourierRateCardService rateCards;

    @Autowired
    @SuppressWarnings("NullAway")
    private DeliveryAccrualOrderCompletionTrigger accrualTrigger;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApprovalDecisionService approvals;

    @Autowired
    @SuppressWarnings("NullAway")
    private CourierSettlementService settlement;

    private final AtomicInteger keys = new AtomicInteger();
    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_sourcing_jobs").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_plans CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_engagements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        cashDue.byOrder.clear();

        seedTenancy();
        seedCatalog();
        insertCourier();
        seedActiveEngagement();

        roleRegistry.synchronize();
        grant(MANAGER);
        grant(SECOND_SIGNATURE);
    }

    // ------------------------------------------------------------ the decision

    @Test
    @DisplayName("a rule cannot group until the pay for a run is decided; deciding first-full-then-increment needs the"
            + " engagement-terms acknowledgement, which the audit fact records; and a wait above zero is refused")
    void groupingNeedsTheDecisionAndTheDecisionNeedsTheAcknowledgement() throws Exception {
        // A document stored before the field existed reads as «not decided» and pays every order in full.
        assertThat(json(mvc.perform(get(POLICY_PATH).with(tokenFor(MANAGER))).andReturn())
                        .path("runPayMode")
                        .isNull())
                .isTrue();
        MvcResult editor = mvc.perform(get(RULES_PATH).with(tokenFor(MANAGER))).andReturn();
        assertThat(json(editor).path("groupingAllowed").asBoolean()).isTrue();
        assertThat(json(editor).path("runPayDecided").asBoolean()).isFalse();

        MvcResult undecided = putRules(0, groupingRules(0));
        assertThat(undecided.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(undecided).path("code").asText()).isEqualTo("RUN_PAY_MODE_UNDECIDED");

        // Choosing the increment without the acknowledgement is refused and changes nothing.
        MvcResult unacknowledged = putPolicy(Map.of("runPayMode", "FIRST_FULL_THEN_INCREMENT"));
        assertThat(unacknowledged.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(unacknowledged).path("detail").asText()).contains("acknowledgeEngagementTerms");
        MvcResult unknown = putPolicy(Map.of("runPayMode", "SPLIT_THE_ROAD", "acknowledgeEngagementTerms", true));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(mvc.perform(get(POLICY_PATH).with(tokenFor(MANAGER))).andReturn())
                        .path("runPayMode")
                        .isNull())
                .as("neither refusal published anything")
                .isTrue();
        assertThat(auditCount("courier.run-pay.changed")).isZero();

        MvcResult decided =
                putPolicy(Map.of("runPayMode", "FIRST_FULL_THEN_INCREMENT", "acknowledgeEngagementTerms", true));
        assertThat(decided.getResponse().getStatus()).as(body(decided)).isEqualTo(200);
        assertThat(json(decided).path("runPayMode").asText()).isEqualTo("FIRST_FULL_THEN_INCREMENT");
        JsonNode fact = auditEvent("courier.run-pay.changed");
        assertThat(fact.path("audit_class").asText()).isEqualTo("BUSINESS");
        assertThat(fact.path("actor_subject").asText()).isEqualTo(MANAGER);
        assertThat(fact.path("change_document").toString())
                .contains("FIRST_FULL_THEN_INCREMENT")
                .contains("UNDECIDED")
                .contains("acknowledgedEngagementTerms");

        // Now that the pay is decided, the rule publishes; a wait above zero still does not.
        assertThat(json(mvc.perform(get(RULES_PATH).with(tokenFor(MANAGER))).andReturn())
                        .path("runPayDecided")
                        .asBoolean())
                .isTrue();
        MvcResult waiting = putRules(0, groupingRules(120));
        assertThat(waiting.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(waiting).path("code").asText()).isEqualTo("GROUPING_WAIT_NOT_AVAILABLE");
        MvcResult published = putRules(0, groupingRules(0));
        assertThat(published.getResponse().getStatus()).as(body(published)).isEqualTo(200);

        // Editing something else while the mode stays put does not ask for the acknowledgement again, and
        // moving back to PER_ORDER (the courier's side of the change only improves) never does.
        assertThat(putPolicy(Map.of("runPayMode", "FIRST_FULL_THEN_INCREMENT", "graceSeconds", 420))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(putPolicy(Map.of("runPayMode", "PER_ORDER")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(auditCount("courier.run-pay.changed"))
                .as("one fact for the move to the increment and one for the move back")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("where the tenant keeps a second-signature policy for courier.run-pay.change, the change waits for it"
            + " and publishes only when the same publication is repeated after the approval")
    void aSecondSignatureHoldsBackTheChange() throws Exception {
        jdbc.sql("""
                INSERT INTO audit.approval_policies (id, tenant_id, action_code, scope_type, threshold_json,
                    required_approver_capability, valid_from, version, approved_by)
                VALUES (gen_random_uuid(), :t, 'courier.run-pay.change', 'TENANT',
                        CAST('{"description":"every change"}' AS jsonb), 'delivery.policy.write',
                        now() - interval '1 day', 1, 'platform-admin')
                """).param("t", TENANT).update();
        Map<String, Object> increment =
                Map.of("runPayMode", "FIRST_FULL_THEN_INCREMENT", "acknowledgeEngagementTerms", true);

        MvcResult waiting = putPolicy(increment);

        assertThat(waiting.getResponse().getStatus()).as(body(waiting)).isEqualTo(200);
        UUID request =
                UUID.fromString(json(waiting).path("pendingApprovalRequestId").asText());
        assertThat(json(waiting).path("runPayMode").isNull())
                .as("the document in force is unchanged while the request waits")
                .isTrue();
        assertThat(json(mvc.perform(get(POLICY_PATH).with(tokenFor(MANAGER))).andReturn())
                        .path("runPayMode")
                        .isNull())
                .isTrue();
        assertThat(auditCount("courier.run-pay.changed")).isZero();

        approvals.decide(
                TENANT,
                request,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(SECOND_SIGNATURE, null),
                "the engagement terms were checked");

        MvcResult repeated = putPolicy(increment);
        assertThat(repeated.getResponse().getStatus()).as(body(repeated)).isEqualTo(200);
        assertThat(json(repeated).path("runPayMode").asText()).isEqualTo("FIRST_FULL_THEN_INCREMENT");
        assertThat(json(repeated).path("pendingApprovalRequestId").isNull()).isTrue();
        assertThat(auditEvent("courier.run-pay.changed").path("change_document").toString())
                .contains("FIRST_FULL_THEN_INCREMENT");
    }

    // ------------------------------------------------------- the whole run

    @Test
    @DisplayName("two orders to neighbouring doors: the second offer shows a smaller figure and says why, accepting it"
            + " joins the first's run, and the second delivery accrues the per-order amount and the short distance")
    void theSecondOrderOfARunIsQuotedAndPaidOnTheExtraDistance() throws Exception {
        assertThat(putPolicy(Map.of("runPayMode", "FIRST_FULL_THEN_INCREMENT", "acknowledgeEngagementTerms", true))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(putRules(0, groupingRules(0)).getResponse().getStatus()).isEqualTo(200);
        activateCard();
        shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, LOCATION, COURIER, ShiftActor.COURIER, courierActor(), "opening my shift", null, "UZS"));

        // ---- the first order: nobody is carrying anything, so it is offered as it always was.
        UUID first = seedDeliveryOrder(metresNorth(2_000));
        sourceOrder(first);
        JsonNode firstOffer = onlyOffer();
        assertThat(firstOffer.path("payBasis").asText()).isEqualTo("DIRECT");
        int firstDistance = planDistance(first);
        assertThat(firstOffer.path("quotedEarningMinor").asLong())
                .as("the first order of a run is quoted exactly as an order alone: 3 000 + 2 000 per km")
                .isEqualTo(3_000L + Math.round(firstDistance * 2.0));
        assertThat(firstOffer.path("quotedDistanceMeters").asInt()).isEqualTo(firstDistance);
        UUID firstShipment = acceptedShipment(firstOffer);

        // ---- the second, 300 m further along the same road.
        UUID second = seedDeliveryOrder(metresNorth(2_300));
        sourceOrder(second);
        JsonNode secondOffer = onlyOffer();
        int secondDistance = planDistance(second);
        assertThat(secondOffer.path("payBasis").asText()).isEqualTo("RUN_INCREMENT");
        assertThat(secondOffer.path("quotedDistanceMeters").asInt())
                .as("paid on the distance to the nearest drop-off already in the run, not the whole road")
                .isBetween(295, 305)
                .isLessThan(secondDistance);
        long expectedQuote =
                3_000L + Math.round(secondOffer.path("quotedDistanceMeters").asInt() * 2.0);
        assertThat(secondOffer.path("quotedEarningMinor").asLong())
                .isEqualTo(expectedQuote)
                .isLessThan(3_000L + Math.round(secondDistance * 2.0));
        UUID secondAttempt = UUID.fromString(secondOffer.path("offerId").asText());
        assertThat(jdbc.sql("""
                                SELECT pay_basis || '/' || run_merge_distance_m || '/' || run_lead_shipment_id
                                  FROM fulfillment.assignment_attempts WHERE id = :id
                                """).param("id", secondAttempt).query(String.class).single())
                .as("stamped on the attempt when the offer was made, naming the shipment it was measured to")
                .startsWith("RUN_INCREMENT/")
                .endsWith("/" + firstShipment);
        assertThat(jdbc.sql("SELECT decision_reason FROM fulfillment.assignment_attempts WHERE id = :id")
                        .param("id", secondAttempt)
                        .query(String.class)
                        .single())
                .endsWith("+GROUPED_STRAIGHT_LINE");
        assertThat(String.valueOf(secondOffer)).doesNotContain(CUSTOMER_NAME).doesNotContain(STREET_LINE);

        UUID secondShipment = acceptedShipment(secondOffer);
        List<Map<String, Object>> runs =
                jdbc.sql("""
                        SELECT id, run_key, run_merge_distance_m FROM fulfillment.shipments
                         WHERE tenant_id = :t ORDER BY assigned_at, id
                        """).param("t", TENANT).query().listOfRows();
        assertThat(runs).hasSize(2);
        Object runKey = runs.get(0).get("run_key");
        assertThat(runKey)
                .as("the acceptance gave the lead the key it had none of")
                .isNotNull();
        assertThat(runs.get(1).get("run_key")).isEqualTo(runKey);
        assertThat(runs.get(0).get("run_merge_distance_m")).isNull();
        assertThat((int) number(runs.get(1), "run_merge_distance_m"))
                .isEqualTo(secondOffer.path("quotedDistanceMeters").asInt());

        // ---- both delivered: the first as it would have been alone, the second on the quoted basis.
        complete(first, 45_000L);
        complete(second, 30_000L);

        Map<String, Object> firstEarning = earningOf(firstShipment);
        Map<String, Object> secondEarning = earningOf(secondShipment);
        assertThat(firstEarning.get("pay_basis")).isEqualTo("DIRECT");
        assertThat(number(firstEarning, "total_minor"))
                .isEqualTo(firstOffer.path("quotedEarningMinor").asLong());
        assertThat(secondEarning.get("pay_basis")).isEqualTo("RUN_INCREMENT");
        assertThat(number(secondEarning, "total_minor"))
                .as("the quote is the pay")
                .isEqualTo(secondOffer.path("quotedEarningMinor").asLong());
        assertThat(number(secondEarning, "per_order_minor")).isEqualTo(3_000L);
        assertThat((int) number(secondEarning, "distance_meters"))
                .isEqualTo(secondOffer.path("quotedDistanceMeters").asInt());
        assertThat((int) number(secondEarning, "direct_distance_meters"))
                .as("both distances sit on the earning, so a statement line can show the reason")
                .isEqualTo(secondDistance);
        assertThat(secondEarning.get("run_key")).isEqualTo(runKey);

        // The cost lines are the incremental amounts and cash stays per order.
        assertThat(jdbc.sql("""
                                SELECT sum(amount_minor) FROM fulfillment.delivery_cost_lines WHERE tenant_id = :t
                                """).param("t", TENANT).query(Long.class).single())
                .isEqualTo(number(firstEarning, "total_minor") + number(secondEarning, "total_minor"));
        assertThat(jdbc.sql("""
                                SELECT array_agg(amount_minor ORDER BY amount_minor)::text
                                  FROM fulfillment.courier_ledger_entries
                                 WHERE tenant_id = :t AND entry_type = 'CASH_COLLECTED'
                                """).param("t", TENANT).query(String.class).single())
                .isEqualTo("{-45000,-30000}");

        // The statement line is where the courier sees it: the later order shows both distances and the reason, and
        // the first order's line is exactly the line it always was.
        UUID period = jdbc.sql(
                        "SELECT settlement_period_id FROM fulfillment.courier_assignment_earnings WHERE tenant_id = :t LIMIT 1")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) java.util.Objects.requireNonNull(settlement
                .close(TENANT, period, ActorRef.user(MANAGER, null), "closing the run test's period")
                .document()
                .get("lines"));
        Map<String, Object> directLine = lineOf(lines, firstShipment);
        Map<String, Object> runLine = lineOf(lines, secondShipment);
        assertThat(directLine).doesNotContainKeys("payBasis", "directDistanceMeters", "runKey");
        assertThat(runLine)
                .containsEntry("payBasis", "RUN_INCREMENT")
                .containsEntry("directDistanceMeters", secondDistance)
                .containsEntry("runKey", String.valueOf(runKey))
                .containsEntry(
                        "distanceMeters",
                        secondOffer.path("quotedDistanceMeters").asInt());

        // The reveal of each door for the feed is on record, for the named purpose, and carries no address.
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM audit.audit_events
                                 WHERE action_code = 'fulfillment.grouping.dropoff-revealed'
                                   AND change_document::text LIKE '%DISPATCH_GROUPING%'
                                   AND change_document::text NOT LIKE '%Bunyodkor%'
                                """).query(Long.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a tenant that chose PER_ORDER still groups for the road it saves, but every order is offered and"
            + " paid DIRECT")
    void aPerOrderTenantGroupsAtDirectPay() throws Exception {
        assertThat(putPolicy(Map.of("runPayMode", "PER_ORDER")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(putRules(0, groupingRules(0)).getResponse().getStatus()).isEqualTo(200);
        activateCard();
        shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, LOCATION, COURIER, ShiftActor.COURIER, courierActor(), "opening my shift", null, "UZS"));

        UUID first = seedDeliveryOrder(metresNorth(2_000));
        sourceOrder(first);
        acceptedShipment(onlyOffer());
        UUID second = seedDeliveryOrder(metresNorth(2_300));
        sourceOrder(second);

        JsonNode offer = onlyOffer();
        assertThat(offer.path("payBasis").asText()).isEqualTo("DIRECT");
        assertThat(offer.path("quotedDistanceMeters").asInt()).isEqualTo(planDistance(second));
        UUID attempt = UUID.fromString(offer.path("offerId").asText());
        assertThat(jdbc.sql("SELECT decision_reason FROM fulfillment.assignment_attempts WHERE id = :id")
                        .param("id", attempt)
                        .query(String.class)
                        .single())
                .as("ranked as groupable, which the attempt says")
                .endsWith("+GROUPED_STRAIGHT_LINE");
        acceptedShipment(offer);
        assertThat(jdbc.sql("SELECT count(DISTINCT run_key) FROM fulfillment.shipments WHERE run_key IS NOT NULL")
                        .query(Long.class)
                        .single())
                .as("a DIRECT offer joins no run: nothing was reduced, so nothing needs a key")
                .isZero();
    }

    // ----------------------------------------------------------------- the steps

    /** Plans an order the way a confirmation does, then runs the one sourcing tick the planner scheduled for it. */
    private void sourceOrder(UUID orderId) {
        Instant confirmedAt = Instant.now().minus(Duration.ofMinutes(12));
        DeliveryPlan plan = planning.open(TENANT, BRAND, LOCATION, orderId, confirmedAt)
                .orElseThrow(() -> new AssertionError("the order was not planned"));
        var claimed =
                jobStore.claim(Instant.now().plus(Duration.ofHours(1)), Duration.ofMinutes(2), 10, "run-pay-test");
        assertThat(claimed).as("the planner scheduled a tick for %s", plan.id()).isNotEmpty();
        claimed.forEach(job -> runner.run(job));
    }

    /** The single offer the courier is holding; the test fails if there are none or several. */
    private JsonNode onlyOffer() throws Exception {
        MvcResult listed =
                mvc.perform(get(COURIER_BASE + "/offers").with(tokenFor(RIDER))).andReturn();
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        assertThat(json(listed))
                .as("offers held by the courier: %s", body(listed))
                .hasSize(1);
        return json(listed).get(0);
    }

    private UUID acceptedShipment(JsonNode offer) throws Exception {
        MvcResult accepted = mvc.perform(
                        post(COURIER_BASE + "/offers/" + offer.path("offerId").asText() + "/accept")
                                .with(tokenFor(RIDER))
                                .header("Idempotency-Key", nextKey())
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        "W/\"" + offer.path("version").asLong() + "\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andReturn();
        assertThat(json(accepted).path("outcome").asText()).as(body(accepted)).isEqualTo("ACCEPTED");
        return UUID.fromString(
                json(accepted).path("delivery").path("shipmentId").asText());
    }

    /** The operator's «completed»: the production trigger closes the shipment and accrues the courier. */
    private void complete(UUID orderId, long totalMinor) {
        cashDue.byOrder.put(orderId, totalMinor);
        Instant now = Instant.now();
        accrualTrigger.onOrderingEvent(new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), orderId, now, BRAND, LOCATION, now, "UZS", totalMinor, 1));
    }

    private Map<String, Object> earningOf(UUID shipmentId) {
        return jdbc.sql("""
                        SELECT pay_basis, run_key, distance_meters, direct_distance_meters, per_order_minor,
                               per_km_minor, total_minor
                          FROM fulfillment.courier_assignment_earnings
                         WHERE tenant_id = :t AND shipment_id = :s
                        """).param("t", TENANT).param("s", shipmentId).query().singleRow();
    }

    private static Map<String, Object> lineOf(List<Map<String, Object>> lines, UUID shipmentId) {
        return lines.stream()
                .filter(line -> shipmentId.toString().equals(line.get("shipmentId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no statement line for shipment " + shipmentId));
    }

    private int planDistance(UUID orderId) {
        return jdbc.sql("SELECT distance_meters FROM fulfillment.delivery_plans WHERE tenant_id = :t AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(Integer.class)
                .single();
    }

    private void activateCard() {
        UUID card = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT,
                BRAND,
                null,
                null,
                "STANDARD",
                1,
                "UZS",
                List.of(
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_ORDER, 0, 3_000, null, null, null),
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 2_000, 0, null, null))));
        rateCards.activate(TENANT, card, ActorRef.user(MANAGER, "Manager"), "activating the standard card");
    }

    // ------------------------------------------------------------ the documents

    private MvcResult putPolicy(Map<String, Object> overrides) throws Exception {
        MvcResult current =
                mvc.perform(get(POLICY_PATH).with(tokenFor(MANAGER))).andReturn();
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        String etag = java.util.Objects.requireNonNull(current.getResponse().getHeader(HttpHeaders.ETAG));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reverificationDays", 180);
        body.put("warningDays", 30);
        body.put("settlementPeriodDays", 14);
        body.put("cashCeilingMinor", 5_000_000);
        body.put("penaltyApprovalThresholdMinor", 200_000);
        body.put("shiftEnforcement", "ENFORCED");
        body.put("graceSeconds", 300);
        body.put("confirmationPointRetentionDays", 30);
        body.put("gpsVerificationEnabled", false);
        body.put("gpsAcceptRadiusMeters", 1000);
        body.put("gpsStatusChangeRadiusMeters", 150);
        body.put("kitchenReadyOnly", false);
        body.put("revealCustomerLocationTiming", "AFTER_ACCEPT");
        body.put("postDeliveryPaymentCheckRequired", false);
        body.put("onlineWithinMinutes", 10);
        body.put("reason", "Run pay test");
        body.putAll(overrides);

        return mvc.perform(put(POLICY_PATH)
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult putRules(int ifMatch, String body) throws Exception {
        MvcResult current = mvc.perform(get(RULES_PATH).with(tokenFor(MANAGER))).andReturn();
        String etag = java.util.Objects.requireNonNull(current.getResponse().getHeader(HttpHeaders.ETAG));
        return mvc.perform(put(RULES_PATH)
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", nextKey())
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** One document of its default alone: the fleet first, grouping orders within 700 m, up to three to a run. */
    private static String groupingRules(int maxWaitSeconds) {
        return """
                { "rules": [], "default": { "mode": "FLEET_FIRST", "grouping":
                    { "mergeRadiusMeters": 700, "maxOrdersPerRun": 3, "maxWaitSeconds": %d } },
                  "reason": "Group neighbouring orders" }
                """.formatted(maxWaitSeconds);
    }

    // ----------------------------------------------------------------- fixtures

    private static double metresNorth(int metres) {
        return BRANCH_LATITUDE + metres / METRES_PER_LATITUDE_DEGREE;
    }

    private static ActorRef courierActor() {
        return ActorRef.user(RIDER, "Courier");
    }

    private String nextKey() {
        return "run-pay-" + keys.incrementAndGet();
    }

    /** A numeric column of a row read with {@code singleRow()}; the test fails, not NPEs, when it is absent. */
    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertThat(value).as("column %s", column).isInstanceOf(Number.class);
        return ((Number) java.util.Objects.requireNonNull(value)).longValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(UTF_8);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
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
                                   reason, change_document
                            FROM audit.audit_events WHERE action_code = :action
                            ORDER BY occurred_at DESC LIMIT 1) e
                        """).param("action", action).query(String.class).list();
        assertThat(rows).as("audit rows for %s", action).hasSize(1);
        return JSON.readTree(rows.get(0));
    }

    private UUID seedDeliveryOrder(double doorLatitude) {
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
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', 'CONFIRMED', 'NOT_REQUIRED',
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", "RP-" + orderId.toString().substring(0, 8))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        DeliveryDestination destination = new DeliveryDestination(
                STREET_LINE, "", "Tashkent", "Chilonzor", "100000", "2", "5", "17", "", doorLatitude, BRANCH_LONGITUDE);
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id, display_name_encrypted,
                    contact_encrypted, address_encrypted, delivery_instructions_encrypted)
                VALUES (:order, :t, :name, :contact, :address, :instructions)
                """)
                .param("order", orderId)
                .param("t", TENANT)
                .param("name", seal(orderId, "display_name_encrypted", CUSTOMER_NAME))
                .param("contact", seal(orderId, "contact_encrypted", "+998901112233"))
                .param("address", seal(orderId, "address_encrypted", objectMapper.writeValueAsString(destination)))
                .param("instructions", seal(orderId, "delivery_instructions_encrypted", "Ring twice"))
                .update();
        return orderId;
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

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'run-pay-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source, address_line,
                    district, city)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0,
                    :lat, :lon, 'MERCHANT_PIN', 'Amir Temur 1', 'Yunusobod', 'Tashkent')
                """)
                .param("id", LOCATION)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("lat", BRANCH_LATITUDE)
                .param("lon", BRANCH_LONGITUDE)
                .update();
        // A courier type that can carry three orders: a run needs a ceiling of two or more (ADR 0170).
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class,
                    max_concurrent_assignments)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER', 3)
                """).param("id", COURIER_TYPE).param("t", TENANT).update();
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

    private void insertCourier() {
        jdbc.sql("""
                INSERT INTO fulfillment.couriers
                    (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                VALUES (:id, :tenantId, :typeId, :subject, 'REF-RUN', 'ciphertext-not-exercised-here', 'ACTIVE')
                """)
                .param("id", COURIER)
                .param("tenantId", TENANT)
                .param("typeId", COURIER_TYPE)
                .param("subject", RIDER)
                .update();
    }

    private void seedActiveEngagement() {
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
                .param("tenantId", TENANT)
                .param("courierId", COURIER)
                .param("engagedFrom", now.atOffset(ZoneOffset.UTC).toLocalDate().minusMonths(1))
                .param("validUntil", now.atOffset(ZoneOffset.UTC).toLocalDate().plusYears(1))
                .param("verifiedAt", now.atOffset(ZoneOffset.UTC))
                .param(
                        "reverificationDue",
                        now.atOffset(ZoneOffset.UTC).toLocalDate().plusMonths(6))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'run pay test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + PlatformRole.TENANT_ADMIN.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.TENANT_ADMIN))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    /** What the settlement says is due in cash, per order; an order a test did not state a figure for is asked of the real one. */
    static final class StubCashDue implements CashDueLookupPort {

        final Map<UUID, Long> byOrder = new ConcurrentHashMap<>();

        private final OrderSettlementService settlement;

        StubCashDue(OrderSettlementService settlement) {
            this.settlement = settlement;
        }

        @Override
        public long cashDueMinor(UUID tenantId, UUID orderId) {
            Long due = byOrder.get(orderId);
            return due != null ? due : settlement.cashDueMinor(tenantId, orderId);
        }

        @Override
        public OptionalLong cashDueMinorIfSettled(UUID tenantId, UUID orderId) {
            Long due = byOrder.get(orderId);
            return due != null ? OptionalLong.of(due) : settlement.cashDueMinorIfSettled(tenantId, orderId);
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
        StubCashDue stubCashDue(OrderSettlementService settlement) {
            return new StubCashDue(settlement);
        }

        /** Replaces the Kafka publisher, whose first send blocks for the producer's whole max.block.ms without a broker. */
        @Bean
        @Primary
        RecordingSignals recordingSignals() {
            return new RecordingSignals();
        }
    }
}
