package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.CourierAssignmentPort;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.domain.BulkActionType;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBulkOperationStore;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0172, gap map row {@code 1.1f}: {@code ASSIGN_COURIER} on the orders board's bulk endpoint,
 * through the real HTTP stack and a real PostgreSQL.
 *
 * <p>What this proves that a service test cannot: the second-capability rule at the controller
 * ({@code order.bulk-action} declared, {@code delivery.manual_assign} asked for), Jackson reading
 * the request the console really sends, the always-202 per-item contract, the idempotent replay
 * answered from {@code ordering.bulk_operation_items}, and the single-winner and ceiling rules
 * reached from the outside rather than by calling the port.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class OperationsOrderBulkCourierAssignmentHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fe100-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe100-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fe100-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fe100-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fe100-4000-7000-8000-0000000000c2");

    /** LOCATION_MANAGER at A: holds order.bulk-action and delivery.manual_assign. */
    private static final String MANAGER = "bulk-assign-manager";

    /** COURIER_DISPATCHER at A: delivery.manual_assign, never order.bulk-action. */
    private static final String DISPATCHER = "bulk-assign-dispatcher";

    /** A tenant-defined role holding order.read and order.bulk-action and nothing about couriers. */
    private static final String BULK_ONLY = "bulk-assign-bulk-only";

    /** LOCATION_MANAGER at B only. */
    private static final String OTHER_BRANCH_MANAGER = "bulk-assign-other-branch";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the bulk courier assignment HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.ordering.bulk.assign-courier.enabled", () -> "true");
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
    private CourierAssignmentPort courierAssignment;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcBulkOperationStore bulkStore;

    private UUID channelId;
    private UUID publicationId;
    private int sequence;

    @BeforeEach
    void reset() {
        Cache policyCurrent = cacheManager.getCache("tenant.policy_current");
        if (policyCurrent != null) {
            policyCurrent.clear();
        }
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(MANAGER, roleId(PlatformRole.LOCATION_MANAGER, true), LOCATION_A);
        grant(DISPATCHER, roleId(PlatformRole.COURIER_DISPATCHER, true), LOCATION_A);
        grant(OTHER_BRANCH_MANAGER, roleId(PlatformRole.LOCATION_MANAGER, true), LOCATION_B);
        UUID bulkOnly = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :t, 'BULK_ONLY', 'Bulk only', 'LOCATION', 'ACTIVE', false)
                """).param("id", bulkOnly).param("t", TENANT).update();
        for (String capability : List.of("order.read", "order.bulk-action")) {
            jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:r, :c)")
                    .param("r", bulkOnly)
                    .param("c", capability)
                    .update();
        }
        grant(BULK_ONLY, bulkOnly, false, LOCATION_A);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("ASSIGN_COURIER assigns each selected delivery order, says what it overrode, and audits per item")
    void assignsEachOrderAndRecordsWhatItOverrode() throws Exception {
        UUID courier = seedCourier("K-100", 3, "ACTIVE", "VALID");
        Order plain = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        // The plan's stored decision never asks the fleet: a person's choice overrides it, visibly.
        Order partnerOnly =
                seedOrder("DELIVERY", "WAITING_TO_SOURCE", "{\"ruleId\":\"FAR-ZONE\",\"mode\":\"PARTNER_ONLY\"}");

        JsonNode body = bulk("assign-happy", MANAGER, assignRequest(courier, plain, partnerOnly))
                .json();

        assertThat(body.path("appliedCount").asInt()).isEqualTo(2);
        assertThat(body.path("failedCount").asInt()).isZero();
        assertThat(body.path("replayed").asBoolean()).isFalse();
        JsonNode first = item(body, plain);
        assertThat(first.path("itemStatus").asText()).isEqualTo("APPLIED");
        assertThat(first.path("planId").asText()).isEqualTo(plain.plan().toString());
        assertThat(first.path("shipmentId").asText()).isNotBlank();
        // The version the order already had: an assignment moves the plan and the shipment, not the order.
        assertThat(first.path("resultingOrderVersion").asInt()).isEqualTo(1);
        assertThat(first.path("note").asText())
                .as("advisory shift enforcement: no open shift is a note")
                .contains("NO_OPEN_SHIFT")
                .doesNotContain("OVERRODE_DISPATCH_RULE");
        assertThat(item(body, partnerOnly).path("note").asText()).contains("OVERRODE_DISPATCH_RULE");

        assertThat(shipmentCourier(plain)).isEqualTo(courier);
        assertThat(shipmentCourier(partnerOnly)).isEqualTo(courier);
        assertThat(planStatus(plain)).isEqualTo("ASSIGNED");

        UUID bulkId = UUID.fromString(body.path("bulkOperationId").asText());
        assertThat(auditCount("ordering.order.bulk-action-item.assign_courier", bulkId.toString()))
                .as("one fact per order, applied or failed")
                .isEqualTo(2);
        assertThat(auditCount("ordering.order.bulk-action.assign_courier", bulkId.toString()))
                .isEqualTo(1);
        assertThat(auditCount("fulfillment.dispatch.assign", partnerOnly.plan().toString()))
                .isEqualTo(1);
        String change = jdbc.sql("""
                SELECT change_document::text FROM audit.audit_events
                WHERE action_code = 'fulfillment.dispatch.assign' AND correlation_id = :c
                """)
                .param("c", partnerOnly.plan().toString())
                .query(String.class)
                .single();
        assertThat(change)
                .as("the assignment's own fact names the bulk, the rule the plan matched and the override")
                .contains(bulkId.toString())
                .contains("FAR-ZONE")
                .contains("OVERRODE_DISPATCH_RULE");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.shipments WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ the ceiling and the replay

    @Test
    @DisplayName("eight orders to a courier whose type carries three apply three in submission order, refuse five, "
            + "and a replay changes nothing")
    void theCeilingIsHonouredInSubmissionOrderAndAReplayChangesNothing() throws Exception {
        UUID courier = seedCourier("K-101", 3, "ACTIVE", "VALID");
        List<Order> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(seedOrder("DELIVERY", "WAITING_TO_SOURCE", null));
        }

        Map<String, Object> request = assignRequest(courier, eight.toArray(new Order[0]));
        JsonNode first = bulk("assign-ceiling", MANAGER, request).json();

        assertThat(first.path("appliedCount").asInt()).isEqualTo(3);
        assertThat(first.path("failedCount").asInt()).isEqualTo(5);
        for (int i = 0; i < 8; i++) {
            JsonNode it = item(first, eight.get(i));
            assertThat(it.path("itemStatus").asText())
                    .as("order %d of 8, in the order sent", i + 1)
                    .isEqualTo(i < 3 ? "APPLIED" : "FAILED");
            if (i >= 3) {
                assertThat(it.path("itemProblemCode").asText()).isEqualTo("COURIER_AT_CAPACITY");
            }
        }
        assertThat(openShipments(courier)).isEqualTo(3);

        Response replayed = bulk("assign-ceiling", MANAGER, request);
        JsonNode replay = replayed.json();
        // Served by the HTTP idempotency layer (ADR 0031) from the stored first response; the
        // service's own replay path (the bulk row already exists) is covered by the
        // OrderAmendmentAndOutcomeTests replay case for the actions it shares.
        assertThat(replayed.replayHeader()).isEqualTo("true");
        assertThat(replay.path("appliedCount").asInt()).isEqualTo(3);
        assertThat(replay.path("failedCount").asInt()).isEqualTo(5);
        assertThat(replay.path("bulkOperationId").asText())
                .isEqualTo(first.path("bulkOperationId").asText());
        assertThat(openShipments(courier)).as("the replay assigned nothing").isEqualTo(3);

        // With the HTTP layer's record gone (it is purged after a day), the same key still reaches
        // the service, which answers from ordering.bulk_operation_items: applied and failed alike.
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        JsonNode servicePath = bulk("assign-ceiling", MANAGER, request).json();
        assertThat(servicePath.path("replayed").asBoolean()).isTrue();
        assertThat(servicePath.path("appliedCount").asInt()).isEqualTo(3);
        assertThat(servicePath.path("failedCount").asInt()).isEqualTo(5);
        assertThat(servicePath.path("bulkOperationId").asText())
                .isEqualTo(first.path("bulkOperationId").asText());
        assertThat(openShipments(courier)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.assignment_attempts")
                        .query(Long.class)
                        .single())
                .as("and opened no attempt")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the failed items sent as a new submission under a new key reach a second courier")
    void retryingOnlyTheFailuresUnderANewKeyAssignsThem() throws Exception {
        UUID courier = seedCourier("K-102", 1, "ACTIVE", "VALID");
        UUID second = seedCourier("K-103", 3, "ACTIVE", "VALID");
        Order a = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order b = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);

        JsonNode first =
                bulk("assign-retry-1", MANAGER, assignRequest(courier, a, b)).json();
        assertThat(item(first, b).path("itemProblemCode").asText()).isEqualTo("COURIER_AT_CAPACITY");

        JsonNode retry =
                bulk("assign-retry-2", MANAGER, assignRequest(second, b)).json();
        assertThat(retry.path("appliedCount").asInt()).isEqualTo(1);
        assertThat(shipmentCourier(b)).isEqualTo(second);
        assertThat(shipmentCourier(a)).isEqualTo(courier);
    }

    // ------------------------------------------------------------------ every problem code

    @Test
    @DisplayName("each problem code is reached by a fixture and nothing is thrown as an error")
    void everyProblemCodeIsReached() throws Exception {
        UUID courier = seedCourier("K-110", 9, "ACTIVE", "VALID");
        UUID other = seedCourier("K-111", 9, "ACTIVE", "VALID");
        UUID suspended = seedCourier("K-112", 9, "SUSPENDED_COMPLIANCE", "VALID");

        Order stale = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order pickup = seedOrder("PICKUP", null, null);
        Order noPlan = seedOrder("DELIVERY", null, null);
        Order completed = seedOrder("DELIVERY", "COMPLETED", null);
        Order cancelled = seedOrder("DELIVERY", "CANCELLED", null);
        Order mine = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order theirs = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order sourcing = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order uncertain = seedOrder("DELIVERY", "MANUAL_ACTION_REQUIRED", null);
        Order midDecision = seedOrder("DELIVERY", "SOURCING", null);
        UUID elsewhere =
                seedOrderAt(LOCATION_B, "DELIVERY", "WAITING_TO_SOURCE", null).orderId();

        insertShipment(mine, courier);
        insertShipment(theirs, other);
        insertAttempt(sourcing, other, "OFFERED", "automated-tick-1", false);
        insertAttempt(uncertain, other, "UNCERTAIN", "partner-uncertain-1", true);

        List<Map<String, Object>> orders = new ArrayList<>();
        orders.add(ref(stale, 99));
        for (Order o : List.of(pickup, noPlan, completed, cancelled, mine, theirs, sourcing, uncertain, midDecision)) {
            orders.add(ref(o, 1));
        }
        orders.add(Map.of("orderId", elsewhere, "expectedVersion", 1));

        JsonNode body = bulk("assign-codes", MANAGER, request("ASSIGN_COURIER", courier, orders))
                .json();

        assertThat(code(body, stale)).isEqualTo("STALE_VERSION");
        assertThat(code(body, pickup)).isEqualTo("NOT_A_DELIVERY_ORDER");
        assertThat(code(body, noPlan)).isEqualTo("NO_DELIVERY_PLAN");
        assertThat(code(body, completed)).isEqualTo("PLAN_NOT_OPEN");
        assertThat(code(body, cancelled)).isEqualTo("PLAN_NOT_OPEN");
        assertThat(code(body, mine)).isEqualTo("ALREADY_ASSIGNED_TO_THIS_COURIER");
        assertThat(code(body, theirs)).isEqualTo("ALREADY_ASSIGNED");
        assertThat(code(body, sourcing)).isEqualTo("ALREADY_BEING_SOURCED");
        assertThat(code(body, uncertain)).isEqualTo("PARTNER_ATTEMPT_UNRECONCILED");
        assertThat(code(body, midDecision)).isEqualTo("ALREADY_BEING_SOURCED");
        JsonNode wrongBranch = body.path("items").get(10);
        assertThat(wrongBranch.path("itemProblemCode").asText()).isEqualTo("ORDER_NOT_FOUND_AT_LOCATION");
        assertThat(body.path("appliedCount").asInt()).isZero();
        assertThat(openShipments(courier))
                .as("only the order this courier already carried")
                .isEqualTo(1);

        // Nothing opened an attempt on the plan that is being sourced or on the uncertain one.
        assertThat(attemptCount(uncertain)).isEqualTo(1);
        assertThat(attemptCount(sourcing)).isEqualTo(1);

        JsonNode suspendedBody = bulk(
                        "assign-suspended",
                        MANAGER,
                        assignRequest(suspended, seedOrder("DELIVERY", "WAITING_TO_SOURCE", null)))
                .json();
        JsonNode failed = suspendedBody.path("items").get(0);
        assertThat(failed.path("itemProblemCode").asText()).isEqualTo("COURIER_NOT_ELIGIBLE");
        assertThat(failed.path("note").asText())
                .as("the gate's named refusals ride in the detail")
                .contains("REGISTRATION_LAPSED");
    }

    @Test
    @DisplayName("an enforced shift policy refuses a courier with no open shift, and OUTSIDE_DISTANCE_BAND only notes")
    void shiftEnforcementRefusesAndTheDistanceBandOnlyNotes() throws Exception {
        UUID courier = seedCourier("K-120", 9, "ACTIVE", "VALID");
        jdbc.sql("UPDATE fulfillment.courier_types SET min_distance_meters = 0, max_distance_meters = 500")
                .update();
        Order far = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        jdbc.sql(
                        "UPDATE fulfillment.delivery_plans SET distance_meters = 4200, distance_source = 'STRAIGHT_LINE' WHERE id = :p")
                .param("p", far.plan())
                .update();

        JsonNode advisory =
                bulk("assign-band", MANAGER, assignRequest(courier, far)).json();
        assertThat(item(advisory, far).path("itemStatus").asText()).isEqualTo("APPLIED");
        assertThat(item(advisory, far).path("note").asText())
                .contains("OUTSIDE_DISTANCE_BAND")
                .contains("NO_OPEN_SHIFT");

        publishShiftEnforcement("ENFORCED");
        Order next = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        JsonNode enforced =
                bulk("assign-enforced", MANAGER, assignRequest(courier, next)).json();
        assertThat(item(enforced, next).path("itemProblemCode").asText()).isEqualTo("COURIER_NOT_ELIGIBLE");
        assertThat(item(enforced, next).path("note").asText()).contains("NO_OPEN_SHIFT");
    }

    // ------------------------------------------------------------------ the request

    @Test
    @DisplayName("a request without a courier, with a malformed reason, or naming another tenant's courier is a "
            + "400 before any item runs")
    void requestLevelRefusalsHappenBeforeAnyItemRuns() throws Exception {
        UUID courier = seedCourier("K-130", 3, "ACTIVE", "VALID");
        Order order = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);

        Map<String, Object> noCourier = new LinkedHashMap<>(assignRequest(courier, order));
        noCourier.remove("courierId");
        assertThat(bulk("assign-400-1", MANAGER, noCourier).status()).isEqualTo(400);

        Map<String, Object> badReason = new LinkedHashMap<>(assignRequest(courier, order));
        badReason.put("reasonCode", "lower case with spaces");
        assertThat(bulk("assign-400-2", MANAGER, badReason).status()).isEqualTo(400);

        Map<String, Object> noReason = new LinkedHashMap<>(assignRequest(courier, order));
        noReason.remove("reasonCode");
        assertThat(bulk("assign-400-3", MANAGER, noReason).status()).isEqualTo(400);

        Map<String, Object> strangerCourier = new LinkedHashMap<>(assignRequest(courier, order));
        strangerCourier.put("courierId", UUID.randomUUID());
        assertThat(bulk("assign-400-4", MANAGER, strangerCourier).status()).isEqualTo(400);

        Map<String, Object> courierOnAdvance = new LinkedHashMap<>(assignRequest(courier, order));
        courierOnAdvance.put("actionType", "ADVANCE");
        courierOnAdvance.put("targetStatus", "PREPARING");
        assertThat(bulk("assign-400-5", MANAGER, courierOnAdvance).status())
                .as("only ASSIGN_COURIER names a courier")
                .isEqualTo(400);

        assertThat(openShipments(courier)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.bulk_operations")
                        .query(Long.class)
                        .single())
                .as("no bulk operation was recorded for a refused request")
                .isZero();
    }

    // ------------------------------------------------------------------ authorisation

    @Test
    @DisplayName(
            "order.bulk-action without delivery.manual_assign, the reverse, a dispatcher and another branch are all refused")
    void bothCapabilitiesAreRequiredAndScopeIsEnforced() throws Exception {
        UUID courier = seedCourier("K-140", 3, "ACTIVE", "VALID");
        Order order = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Map<String, Object> request = assignRequest(courier, order);

        assertThat(bulk("assign-auth-1", BULK_ONLY, request).status())
                .as("order.bulk-action alone")
                .isEqualTo(403);
        assertThat(bulk("assign-auth-2", DISPATCHER, request).status())
                .as("delivery.manual_assign alone: the dispatcher keeps the board's own per-plan loop")
                .isEqualTo(403);
        assertThat(bulk("assign-auth-3", OTHER_BRANCH_MANAGER, request).status())
                .as("a manager of another branch")
                .isEqualTo(403);
        assertThat(openShipments(courier)).isZero();

        // The coarse capability still serves the other two actions without the courier grant.
        Map<String, Object> advance = new LinkedHashMap<>();
        advance.put("actionType", "ADVANCE");
        advance.put("targetStatus", "PREPARING");
        advance.put("reasonCode", "KITCHEN_STARTED");
        advance.put("orders", List.of(ref(order, 1)));
        assertThat(bulk("assign-auth-4", BULK_ONLY, advance).status()).isEqualTo(202);
    }

    // ------------------------------------------------------------------ the rule, the run and the crash

    @Test
    @DisplayName("an item names the rule the plan matched, a bulk makes no run and pays DIRECT, and the plan's stored"
            + " decision and every rule document are left exactly as they were")
    void anItemNamesTheRuleAndAnAssignmentByAPersonMakesNoRunAndTouchesNoRule() throws Exception {
        UUID courier = seedCourier("K-150", 5, "ACTIVE", "VALID");
        Order decided =
                seedOrder("DELIVERY", "WAITING_TO_SOURCE", "{\"ruleId\":\"FAR-ZONE\",\"mode\":\"PARTNER_ONLY\"}");
        Order byDefault = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        String decisionBefore = storedDecision(decided);
        long policiesBefore = dispatchRuleDocuments();

        JsonNode body = bulk("assign-rule", MANAGER, assignRequest(courier, decided, byDefault))
                .json();

        assertThat(item(body, decided).path("ruleId").asText()).isEqualTo("FAR-ZONE");
        assertThat(item(body, decided).path("note").asText()).contains("OVERRODE_DISPATCH_RULE");
        assertThat(item(body, byDefault).path("ruleId").asText())
                .as("the document's own default decided this one")
                .isEqualTo("DEFAULT");
        assertThat(jdbc.sql("""
                                SELECT rule_id FROM ordering.bulk_operation_items
                                 WHERE bulk_operation_id = :b AND order_id = :o
                                """)
                        .param("b", UUID.fromString(body.path("bulkOperationId").asText()))
                        .param("o", decided.orderId())
                        .query(String.class)
                        .single())
                .as("recorded on the item, not only returned")
                .isEqualTo("FAR-ZONE");

        // Replayed from the stored items, the rule is still there.
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        assertThat(item(
                                bulk("assign-rule", MANAGER, assignRequest(courier, decided, byDefault))
                                        .json(),
                                decided)
                        .path("ruleId")
                        .asText())
                .isEqualTo("FAR-ZONE");

        assertThat(storedDecision(decided))
                .as("the plan's decision is read, never re-evaluated or rewritten")
                .isEqualTo(decisionBefore);
        assertThat(dispatchRuleDocuments()).isEqualTo(policiesBefore);
        assertThat(jdbc.sql("""
                                SELECT string_agg(DISTINCT pay_basis, ',') FROM fulfillment.assignment_attempts
                                 WHERE tenant_id = :t
                                """).param("t", TENANT).query(String.class).single())
                .as(
                        "a person's assignment never creates a run (ADR 0170): the operator pays three full cards by design")
                .isEqualTo("DIRECT");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.shipments WHERE run_key IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a bulk that died after an item's assignment committed is finished by the same key: the shipment is"
            + " reported, no second attempt is opened, and an item that never ran is applied")
    void aReplayUnderTheSameKeyFinishesACrashedBulk() throws Exception {
        UUID courier = seedCourier("K-160", 5, "ACTIVE", "VALID");
        Order committed = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order neverRan = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        String key = "assign-crash";

        // What the first request left behind: the claim on both orders, one assignment committed, no
        // result written for it, and the operation never completed.
        UUID bulkId = Ids.newId();
        bulkStore.insert(new JdbcBulkOperationStore.NewBulkOperation(
                bulkId,
                TENANT,
                BRAND,
                LOCATION_A,
                BulkActionType.ASSIGN_COURIER,
                2,
                key,
                "USER",
                MANAGER,
                Instant.now(),
                courier,
                "OPERATIONS_BULK_ASSIGN"));
        bulkStore.insertPendingItem(TENANT, bulkId, committed.orderId());
        bulkStore.insertPendingItem(TENANT, bulkId, neverRan.orderId());
        CourierAssignmentPort.Outcome first = courierAssignment.assignForOrder(
                TENANT,
                BRAND,
                LOCATION_A,
                committed.orderId(),
                courier,
                "OPERATIONS_BULK_ASSIGN",
                attemptKeyOf(key, committed.orderId()),
                bulkId,
                ActorRef.user(MANAGER, null));
        assertThat(first.applied()).isTrue();
        assertThat(attemptCount(committed)).isEqualTo(1);

        JsonNode body =
                bulk(key, MANAGER, assignRequest(courier, committed, neverRan)).json();

        assertThat(body.path("replayed").asBoolean()).isTrue();
        assertThat(body.path("bulkOperationId").asText()).isEqualTo(bulkId.toString());
        assertThat(body.path("appliedCount").asInt()).isEqualTo(2);
        assertThat(body.path("failedCount").asInt()).isZero();
        assertThat(item(body, committed).path("shipmentId").asText())
                .as("the shipment the crashed run committed is the one reported")
                .isEqualTo(Objects.requireNonNull(first.shipmentId()).toString());
        assertThat(attemptCount(committed))
                .as("the retry reached the same attempt")
                .isEqualTo(1);
        assertThat(attemptCount(neverRan)).isEqualTo(1);
        assertThat(shipmentCourier(neverRan)).isEqualTo(courier);
        assertThat(openShipments(courier)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT completed_at IS NOT NULL FROM ordering.bulk_operations WHERE id = :b")
                        .param("b", bulkId)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(auditCount("ordering.order.bulk-action.assign_courier", bulkId.toString()))
                .as("the summary is written once, when the operation completes")
                .isEqualTo(1);
        assertThat(auditCount("ordering.order.bulk-action-item.assign_courier", bulkId.toString()))
                .isEqualTo(2);

        // And a further replay changes nothing at all.
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        JsonNode again =
                bulk(key, MANAGER, assignRequest(courier, committed, neverRan)).json();
        assertThat(again.path("appliedCount").asInt()).isEqualTo(2);
        assertThat(openShipments(courier)).isEqualTo(2);
        assertThat(auditCount("ordering.order.bulk-action.assign_courier", bulkId.toString()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two bulks with distinct keys over one order produce one winner and one shipment, even sent together")
    void twoBulksOverOneOrderHaveOneWinner() throws Exception {
        UUID courier = seedCourier("K-170", 5, "ACTIVE", "VALID");
        Order contested = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);

        List<JsonNode> answers = together(
                () -> bulk("assign-race-1", MANAGER, assignRequest(courier, contested))
                        .json(),
                () -> bulk("assign-race-2", MANAGER, assignRequest(courier, contested))
                        .json());

        long applied = answers.stream()
                .map(answer -> item(answer, contested).path("itemStatus").asText())
                .filter("APPLIED"::equals)
                .count();
        assertThat(applied).as("exactly one request won the order").isEqualTo(1);
        assertThat(answers.stream()
                        .map(answer ->
                                item(answer, contested).path("itemProblemCode").asText())
                        .filter(code -> !"null".equals(code) && !code.isEmpty()))
                .singleElement()
                .isIn("ALREADY_ASSIGNED_TO_THIS_COURIER", "ALREADY_BEING_SOURCED", "ALREADY_ASSIGNED");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.shipments WHERE order_id = :o AND status <> 'CANCELLED'")
                        .param("o", contested.orderId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two bulks sent together to one courier together never exceed the courier type's ceiling")
    void twoSimultaneousBulksNeverExceedTheCeiling() throws Exception {
        UUID courier = seedCourier("K-180", 3, "ACTIVE", "VALID");
        Order[] first = {
            seedOrder("DELIVERY", "WAITING_TO_SOURCE", null),
            seedOrder("DELIVERY", "WAITING_TO_SOURCE", null),
            seedOrder("DELIVERY", "WAITING_TO_SOURCE", null)
        };
        Order[] second = {
            seedOrder("DELIVERY", "WAITING_TO_SOURCE", null),
            seedOrder("DELIVERY", "WAITING_TO_SOURCE", null),
            seedOrder("DELIVERY", "WAITING_TO_SOURCE", null)
        };

        List<JsonNode> answers = together(
                () -> bulk("assign-cap-1", MANAGER, assignRequest(courier, first))
                        .json(),
                () -> bulk("assign-cap-2", MANAGER, assignRequest(courier, second))
                        .json());

        assertThat(answers.stream()
                        .mapToInt(answer -> answer.path("appliedCount").asInt())
                        .sum())
                .as("six orders, a ceiling of three: three apply across both requests")
                .isEqualTo(3);
        assertThat(answers.stream()
                        .mapToInt(answer -> answer.path("failedCount").asInt())
                        .sum())
                .isEqualTo(3);
        assertThat(openShipments(courier)).isEqualTo(3);
    }

    @Test
    @DisplayName("the single assignment from the board is not held to the ceiling the bulk honours: it is unchanged")
    void theSingleAssignmentIsUnchangedByTheCeiling() throws Exception {
        UUID courier = seedCourier("K-190", 1, "ACTIVE", "VALID");
        Order carried = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order extra = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        insertShipment(carried, courier);

        assertThat(code(
                        bulk("assign-single-1", MANAGER, assignRequest(courier, extra))
                                .json(),
                        extra))
                .as("the bulk refuses at the ceiling")
                .isEqualTo("COURIER_AT_CAPACITY");

        MvcResult single = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                                + LOCATION_A + "/dispatch/plans/" + extra.plan() + "/assign")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "single-assign-over-ceiling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                Map.of("courierId", courier, "expectedVersion", 1, "reasonCode", "BOARD_ASSIGN"))))
                .andReturn();
        assertThat(single.getResponse().getStatus())
                .as(single.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(openShipments(courier))
                .as("the dispatcher's own click is a person's decision and is not capped here")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a refused item is audited like an applied one, with its code")
    void aFailedItemIsAuditedToo() throws Exception {
        UUID courier = seedCourier("K-200", 5, "ACTIVE", "VALID");
        UUID other = seedCourier("K-201", 5, "ACTIVE", "VALID");
        Order theirs = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        insertShipment(theirs, other);

        JsonNode body = bulk("assign-audit-failed", MANAGER, assignRequest(courier, theirs))
                .json();

        UUID bulkId = UUID.fromString(body.path("bulkOperationId").asText());
        assertThat(code(body, theirs)).isEqualTo("ALREADY_ASSIGNED");
        assertThat(jdbc.sql("""
                                SELECT outcome || ':' || (change_document -> 'problemCode' ->> 'after')
                                  FROM audit.audit_events
                                 WHERE action_code = 'ordering.order.bulk-action-item.assign_courier'
                                   AND correlation_id = :c
                                """)
                        .param("c", bulkId.toString())
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED:ALREADY_ASSIGNED");
        assertThat(jdbc.sql("""
                                SELECT outcome FROM audit.audit_events
                                 WHERE action_code = 'ordering.order.bulk-action.assign_courier'
                                   AND correlation_id = :c
                                """)
                        .param("c", bulkId.toString())
                        .query(String.class)
                        .single())
                .as("a summary with a failure is not reported as a success")
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName(
            "a courier with no engagement, or one on a break, is not eligible and the gate's reason rides in the note")
    void aCourierWithNoEngagementOrOnABreakIsNotEligible() throws Exception {
        UUID unengaged = seedCourierWithoutEngagement("K-210");
        UUID onBreak = seedCourier("K-211", 5, "ACTIVE", "VALID");
        openShift(onBreak, "ON_BREAK");
        Order first = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);
        Order second = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);

        JsonNode noEngagement =
                bulk("assign-elig-1", MANAGER, assignRequest(unengaged, first)).json();
        JsonNode breaking =
                bulk("assign-elig-2", MANAGER, assignRequest(onBreak, second)).json();

        assertThat(code(noEngagement, first)).isEqualTo("COURIER_NOT_ELIGIBLE");
        assertThat(item(noEngagement, first).path("note").asText()).contains("ENGAGEMENT_NOT_ACTIVE");
        assertThat(code(breaking, second)).isEqualTo("COURIER_NOT_ELIGIBLE");
        assertThat(item(breaking, second).path("note").asText()).contains("DUTY_STATE_ON_BREAK");
        assertThat(openShipments(unengaged) + openShipments(onBreak)).isZero();
    }

    @Test
    @DisplayName(
            "an exception inside an item becomes UNEXPECTED_FAILURE, without its message, and the other items still run")
    void anExceptionInAnItemIsReportedWithoutItsMessage() throws Exception {
        UUID courier = seedCourier("K-220", 5, "ACTIVE", "VALID");
        // A stored decision the planner's own reader cannot parse: the item throws before it can name a code.
        Order unreadable = seedOrder("DELIVERY", "WAITING_TO_SOURCE", "{\"ruleId\":\"X\",\"mode\":\"NOT_A_MODE\"}");
        Order fine = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);

        Response response = bulk("assign-boom", MANAGER, assignRequest(courier, unreadable, fine));
        JsonNode body = response.json();

        assertThat(code(body, unreadable)).isEqualTo("UNEXPECTED_FAILURE");
        assertThat(response.raw())
                .as("no stack, no class name, no row content in what the client is told")
                .doesNotContain("NOT_A_MODE")
                .doesNotContain("Exception");
        assertThat(item(body, fine).path("itemStatus").asText()).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("naming a real courier of another tenant is a 400 before any item runs, and assigns nothing there")
    void anotherTenantsRealCourierIsRefused() throws Exception {
        UUID foreign = seedForeignCourier();
        Order order = seedOrder("DELIVERY", "WAITING_TO_SOURCE", null);

        Response refused = bulk("assign-foreign", MANAGER, assignRequest(foreign, order));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.shipments")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.bulk_operations")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private record Order(UUID orderId, @Nullable UUID planId) {
        UUID plan() {
            return Objects.requireNonNull(planId);
        }
    }

    private record Response(
            int status, String raw, @Nullable String replayHeader) {
        JsonNode json() throws Exception {
            assertThat(status).as("status of %s", raw).isEqualTo(202);
            return JSON.readTree(raw);
        }
    }

    private Response bulk(String key, String subject, Map<String, Object> request) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                                + LOCATION_A + "/orders/bulk-actions")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(),
                result.getResponse().getHeader("Idempotency-Replayed"));
    }

    private static Map<String, Object> assignRequest(UUID courier, Order... orders) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Order order : orders) {
            refs.add(ref(order, 1));
        }
        return request("ASSIGN_COURIER", courier, refs);
    }

    /** Runs the calls together, released by one latch, and returns their answers in the order given. */
    @SafeVarargs
    private static List<JsonNode> together(Callable<JsonNode>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<JsonNode>> pending = new ArrayList<>();
            for (Callable<JsonNode> call : calls) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<JsonNode> answers = new ArrayList<>();
            for (Future<JsonNode> future : pending) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The key the bulk derives for one order's attempt (ADR 0172 Decision 8), restated so a test can leave the crash behind. */
    private static String attemptKeyOf(String bulkKey, UUID orderId) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bulkKey.getBytes(UTF_8));
        return "bulk:" + java.util.HexFormat.of().formatHex(digest, 0, 8) + ":" + orderId;
    }

    private String storedDecision(Order order) {
        return jdbc.sql("SELECT dispatch_decision::text FROM fulfillment.delivery_plans WHERE id = :p")
                .param("p", order.plan())
                .query(String.class)
                .single();
    }

    private long dispatchRuleDocuments() {
        return jdbc.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'fulfillment.dispatch_rules'")
                .query(Long.class)
                .single();
    }

    private void openShift(UUID courier, String dutyState) {
        UUID engagement = jdbc.sql("SELECT id FROM fulfillment.courier_engagements WHERE courier_id = :c")
                .param("c", courier)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_shifts (id, tenant_id, brand_id, location_id, courier_id,
                    engagement_id, status, duty_state, opened_at, enforcement_mode)
                VALUES (gen_random_uuid(), :t, :b, :loc, :c, :e, 'OPEN', :duty, now(), 'ADVISORY')
                """)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION_A)
                .param("c", courier)
                .param("e", engagement)
                .param("duty", dutyState)
                .update();
    }

    private UUID seedCourierWithoutEngagement(String reference) {
        UUID typeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :t, :code, 'Scooter', 'SCOOTER', 5, 60, 'ACTIVE')
                """)
                .param("id", typeId)
                .param("t", TENANT)
                .param("code", "T-" + reference)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :t, :typeId, :subject, :ref, 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("t", TENANT)
                .param("typeId", typeId)
                .param("subject", "keycloak-" + reference)
                .param("ref", reference)
                .update();
        return courierId;
    }

    /** A real courier of the other tenant, so «not this tenant's» is tested with a row that exists. */
    private UUID seedForeignCourier() {
        UUID typeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :t, 'T-FOREIGN', 'Scooter', 'SCOOTER', 5, 60, 'ACTIVE')
                """).param("id", typeId).param("t", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :t, :typeId, 'keycloak-foreign', 'K-FOREIGN', 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("t", OTHER_TENANT)
                .param("typeId", typeId)
                .update();
        return courierId;
    }

    private static Map<String, Object> request(String type, UUID courier, List<Map<String, Object>> orders) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionType", type);
        body.put("courierId", courier);
        body.put("reasonCode", "OPERATIONS_BULK_ASSIGN");
        body.put("orders", orders);
        return body;
    }

    private static Map<String, Object> ref(Order order, int version) {
        return Map.of("orderId", order.orderId(), "expectedVersion", version);
    }

    private static JsonNode item(JsonNode body, Order order) {
        for (JsonNode item : body.path("items")) {
            if (item.path("orderId").asText().equals(order.orderId().toString())) {
                return item;
            }
        }
        throw new AssertionError("no item for " + order.orderId());
    }

    private static String code(JsonNode body, Order order) {
        return item(body, order).path("itemProblemCode").asText();
    }

    private long auditCount(String action, String correlation) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :a AND correlation_id = :c")
                .param("a", action)
                .param("c", correlation)
                .query(Long.class)
                .single();
    }

    private UUID shipmentCourier(Order order) {
        return jdbc.sql("""
                SELECT courier_id FROM fulfillment.shipments
                WHERE order_id = :o AND status <> 'CANCELLED'
                """).param("o", order.orderId()).query(UUID.class).single();
    }

    private String planStatus(Order order) {
        return jdbc.sql("SELECT status FROM fulfillment.delivery_plans WHERE id = :p")
                .param("p", order.plan())
                .query(String.class)
                .single();
    }

    private long openShipments(UUID courier) {
        return jdbc.sql("""
                SELECT count(*) FROM fulfillment.shipments
                WHERE courier_id = :c AND status NOT IN ('DELIVERED', 'CANCELLED')
                """).param("c", courier).query(Long.class).single();
    }

    private long attemptCount(Order order) {
        return jdbc.sql("SELECT count(*) FROM fulfillment.assignment_attempts WHERE delivery_plan_id = :p")
                .param("p", order.plan())
                .query(Long.class)
                .single();
    }

    private void publishShiftEnforcement(String mode) throws Exception {
        String admin = "bulk-assign-admin";
        grantTenant(admin, roleId(PlatformRole.TENANT_ADMIN, true));
        String body = """
                {"reverificationDays":180,"warningDays":30,"settlementPeriodDays":14,
                 "cashCeilingMinor":5000000,"penaltyApprovalThresholdMinor":200000,
                 "shiftEnforcement":"%s","graceSeconds":300,"confirmationPointRetentionDays":30,
                 "gpsVerificationEnabled":false,"gpsAcceptRadiusMeters":1000,
                 "gpsStatusChangeRadiusMeters":150,"kitchenReadyOnly":false,
                 "revealCustomerLocationTiming":"AFTER_ACCEPT","postDeliveryPaymentCheckRequired":false,
                 "onlineWithinMinutes":10,"reason":"bulk assignment test"}
                """.formatted(mode);
        MvcResult result = mvc.perform(put("/api/v1/operations/tenants/" + TENANT + "/courier-policy")
                        .with(tokenFor(admin))
                        .header("Idempotency-Key", "enforce-" + mode)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ fixtures

    private Order seedOrder(String mode, @Nullable String planStatus, @Nullable String dispatchDecisionJson) {
        return seedOrderAt(LOCATION_A, mode, planStatus, dispatchDecisionJson);
    }

    private Order seedOrderAt(
            UUID location, String mode, @Nullable String planStatus, @Nullable String dispatchDecisionJson) {
        sequence++;
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :mode, 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("ch", channelId)
                .param("mode", mode)
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
                .param("loc", location)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, :mode, 'AUTO_CONFIRM',
                    'HORECAOS_OPERATIONS', 'CONFIRMED', 'UZS', 20000, 0, 0, 20000, :quote, :hash,
                    :pub, :cart, :key, 1, :at, :at)
                """)
                .param("id", orderId)
                .param("number", String.valueOf(7000 + sequence))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("mode", mode)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        UUID planId = null;
        if (planStatus != null) {
            planId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id,
                        order_id, status, sourcing_mode, service_level, customer_delivery_fee_minor,
                        currency, confirmed_at, preparation_seconds, estimated_ready_at,
                        pickup_window_start, pickup_window_end, source_at, latest_assignment_at,
                        branch_zone, calculation_version, version, dispatch_decision)
                    VALUES (:id, :t, :b, :loc, :orderId, :status, 'FLEET_FIRST', 'STANDARD', 0,
                        'UZS', :now, 600, :now, :now, :windowEnd, :now, :windowEnd, 'Asia/Tashkent', 1, 1,
                        CAST(:decision AS jsonb))
                    """)
                    .param("id", planId)
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("loc", location)
                    .param("orderId", orderId)
                    .param("status", planStatus)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("windowEnd", now.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                    .param("decision", dispatchDecisionJson)
                    .update();
        }
        return new Order(orderId, planId);
    }

    private UUID seedCourier(String reference, int ceiling, String engagementStatus, String warningState) {
        UUID typeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name,
                    vehicle_class, max_concurrent_assignments, offer_ttl_seconds, status)
                VALUES (:id, :t, :code, 'Scooter', 'SCOOTER', :ceiling, 60, 'ACTIVE')
                """)
                .param("id", typeId)
                .param("t", TENANT)
                .param("code", "T-" + reference)
                .param("ceiling", ceiling)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                    principal_subject, display_reference, protected_full_name, status, version)
                VALUES (:id, :t, :typeId, :subject, :ref, 'protected', 'ACTIVE', 1)
                """)
                .param("id", courierId)
                .param("t", TENANT)
                .param("typeId", typeId)
                .param("subject", "keycloak-" + reference)
                .param("ref", reference)
                .update();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_engagements (
                    id, tenant_id, courier_id, engagement_type, status, engaged_from,
                    protected_registration_ref, registration_valid_until,
                    registration_verified_at, registration_verified_by, verification_method,
                    reverification_due_on, warning_state, version, created_at, updated_at,
                    suspended_at, suspension_reason_code)
                VALUES (:id, :t, :courier, 'SELF_EMPLOYED', :status, :from,
                    'ciphertext-not-exercised-here', :until, :verifiedAt, 'test-fixture', 'MANUAL_ATTESTATION',
                    :due, :warning, 1, :now, :now, :suspendedAt, :suspensionReason)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("courier", courierId)
                .param("status", engagementStatus)
                .param("from", java.time.LocalDate.now().minusMonths(6))
                .param("until", java.time.LocalDate.now().plusYears(1))
                .param("verifiedAt", now.atOffset(ZoneOffset.UTC))
                .param("due", java.time.LocalDate.now().plusMonths(6))
                .param("warning", warningState)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("suspendedAt", engagementStatus.startsWith("SUSPENDED") ? now.atOffset(ZoneOffset.UTC) : null)
                .param("suspensionReason", engagementStatus.startsWith("SUSPENDED") ? "COMPLIANCE_LAPSE" : null)
                .update();
        return courierId;
    }

    private void insertShipment(Order order, UUID courier) {
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (id, tenant_id, brand_id, location_id, order_id,
                    delivery_plan_id, status, source_type, courier_id, assigned_at, version)
                VALUES (:id, :t, :b, :loc, :orderId, :planId, 'ASSIGNED', 'INTERNAL', :courier, :now, 1)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION_A)
                .param("orderId", order.orderId())
                .param("planId", order.plan())
                .param("courier", courier)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertAttempt(Order order, @Nullable UUID courier, String status, String key, boolean uncertain) {
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.assignment_attempts (id, tenant_id, delivery_plan_id,
                    sequence_number, source_type, courier_id, provider_binding_id, status,
                    idempotency_key, decision_reason, uncertain_outcome, expires_at)
                VALUES (:id, :t, :plan, 1, :source, :courier, NULL, :status, :key, 'TEST_FIXTURE', :uncertain, :expires)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("plan", order.plan())
                .param("source", "INTERNAL")
                .param("courier", courier)
                .param("status", status)
                .param("key", key)
                .param("uncertain", uncertain)
                .param(
                        "expires",
                        "OFFERED".equals(status) ? now.plusSeconds(600).atOffset(ZoneOffset.UTC) : null)
                .update();
    }

    private void seedTenancy() {
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "bulk-assign-" + tenant)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        for (UUID location : List.of(LOCATION_A, LOCATION_B)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("code", location.equals(LOCATION_A) ? "CENTRE" : "NORTH")
                    .param("slug", location.equals(LOCATION_A) ? "centre" : "north")
                    .update();
        }
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

    private static UUID roleId(PlatformRole role, boolean platform) {
        return RoleRegistrySynchronizer.platformRoleId(role);
    }

    private void grant(String subject, UUID roleId, UUID locationId) {
        grant(subject, roleId, true, locationId);
    }

    private void grant(String subject, UUID roleId, boolean platformRole, UUID locationId) {
        insertGrant(subject, roleId, platformRole, "LOCATION", locationId);
    }

    private void grantTenant(String subject, UUID roleId) {
        insertGrant(subject, roleId, true, "TENANT", TENANT);
    }

    private void insertGrant(String subject, UUID roleId, boolean platformRole, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :t, :subject, :roleId, :platform, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'bulk assignment http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + roleId + scopeId).getBytes(UTF_8)))
                .param("t", TENANT)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("platform", platformRole)
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
