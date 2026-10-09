package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
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
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0150, end to end and over HTTP: an operator sets «Заказ без обещанного времени опаздывает
 * через» for a branch and the order board's «Только опаздывающие» filter, the order header's own
 * severity read and the lateness policy every screen and wall colours from agree about the same
 * orders at the same instant.
 *
 * <p>The orders are the four shapes the record decides: an aggregator order with no promise (the
 * only kind the scalar can govern), a native order with a promise (which it must not touch), an
 * order still awaiting approval whose clock started at checkout (ADR 0036) and a scheduled order
 * taken hours ahead (late against its slot, not against the day it was placed).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLatenessScalarSurfacesHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb150-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb150-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb150-4000-7000-8000-0000000000c1");
    private static final UUID SIBLING = UUID.fromString("018fb150-4000-7000-8000-0000000000c2");

    private static final String OWNER = "lateness-surfaces-owner";
    private static final String LATE_THRESHOLD = "ordering.late_order_threshold_minutes";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CONFIG = "/api/v1/operations/tenants/" + TENANT + "/configuration";
    private static final String ORDERS = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/";
    private static final String LATENESS = "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/";
    private static final String KITCHEN =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/kitchen";

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
    private ConfigurationValueCache configurationCache;

    @MockitoBean
    @SuppressWarnings("NullAway")
    private StaffDirectory staffDirectory;

    private OrderBoardFixtures fixtures;
    private UUID aggregatorUnpromised;
    private UUID nativePromised;
    private UUID awaitingApproval;
    private UUID scheduledHoursAhead;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("TRUNCATE TABLE kitchen.tickets CASCADE").update();
        fixtures.clean();
        roleRegistry.synchronize();

        fixtures.tenant(TENANT, "lateness-surfaces", BRAND, LOCATION);
        fixtures.location(TENANT, BRAND, SIBLING, "SIB", "Sibling");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        configurationCache.evict(LATE_THRESHOLD, ResourceScope.tenant(TENANT));
        configurationCache.evict(LATE_THRESHOLD, ResourceScope.brand(TENANT, BRAND));
        configurationCache.evict(LATE_THRESHOLD, ResourceScope.location(TENANT, BRAND, LOCATION));
        configurationCache.evict(LATE_THRESHOLD, ResourceScope.location(TENANT, BRAND, SIBLING));

        Instant now = Instant.now();
        aggregatorUnpromised = fixtures.insertOrder(order("LS-AGG")
                .at(TENANT, BRAND, LOCATION)
                .createdAt(now.minus(Duration.ofMinutes(30)))
                .unpromised());
        nativePromised = fixtures.insertOrder(order("LS-NAT")
                .at(TENANT, BRAND, LOCATION)
                .createdAt(now.minus(Duration.ofMinutes(30)))
                .promisedAt(now.plus(Duration.ofMinutes(25))));
        awaitingApproval = fixtures.insertOrder(order("LS-AWA")
                .at(TENANT, BRAND, LOCATION)
                .status("AWAITING_APPROVAL")
                .createdAt(now.minus(Duration.ofMinutes(50)))
                .promisedAt(now.minus(Duration.ofMinutes(10))));
        scheduledHoursAhead = fixtures.insertOrder(order("LS-SCH")
                .at(TENANT, BRAND, LOCATION)
                .status("CONFIRMED")
                .createdAt(now.minus(Duration.ofHours(5)))
                .promisedAt(now.plus(Duration.ofHours(2))));
    }

    @Test
    @DisplayName("with the threshold unset only the order past its own promise is late, on every surface")
    void withTheThresholdUnsetTheSurfacesAgreeOnTodaysLine() throws Exception {
        assertThat(lateOnTheBoard(LOCATION)).containsExactly(awaitingApproval);
        assertThat(severityOf(awaitingApproval))
                .as("waiting for approval is late by the promise made at checkout")
                .isEqualTo("LATE");
        assertThat(severityOf(aggregatorUnpromised))
                .as("thirty minutes into the platform's forty-five")
                .isEqualTo("NORMAL");
        assertThat(severityOf(nativePromised)).isEqualTo("NORMAL");
        assertThat(severityOf(scheduledHoursAhead))
                .as("taken hours ahead, late against its slot and not against the day it was placed")
                .isEqualTo("NORMAL");
        assertThat(fallbackSeconds(LOCATION)).isEqualTo(2700);
    }

    @Test
    @DisplayName(
            "a branch threshold of 20 turns the unpromised order late on the board, the header and the policy at once")
    void aBranchThresholdMovesTheUnpromisedOrderOnEverySurfaceAndNothingElse() throws Exception {
        // Read first, so the cached answers the write must not leave behind exist.
        assertThat(lateOnTheBoard(LOCATION)).containsExactly(awaitingApproval);
        assertThat(severityOf(aggregatorUnpromised)).isEqualTo("NORMAL");

        setThreshold("LOCATION", LOCATION, 20, "aggregator orders should go red sooner");

        assertThat(lateOnTheBoard(LOCATION))
                .as("the board's filter")
                .containsExactlyInAnyOrder(awaitingApproval, aggregatorUnpromised)
                .doesNotContain(nativePromised, scheduledHoursAhead);
        assertThat(severityOf(aggregatorUnpromised))
                .as("the order header's own read")
                .isEqualTo("LATE");
        assertThat(fallbackSeconds(LOCATION))
                .as("the policy the boards, the kitchen queue, both VDUs and the wallboard colour from")
                .isEqualTo(1200);

        assertThat(severityOf(nativePromised))
                .as("an order with a promise is the document's grace and nothing the threshold touches")
                .isEqualTo("NORMAL");
        assertThat(severityOf(scheduledHoursAhead)).isEqualTo("NORMAL");
        assertThat(severityOf(awaitingApproval)).isEqualTo("LATE");

        assertThat(fallbackSeconds(SIBLING))
                .as("the sibling branch inherits nothing from a location override")
                .isEqualTo(2700);
    }

    @Test
    @DisplayName("the kitchen queue and the VDU carry the ORDER's clock, so a ticket is late exactly when the board "
            + "says its order is: an unpromised order accepted late, a delivery with road time, a finished order")
    void theKitchenSurfacesCarryTheOrdersClockAndAgreeWithTheBoard() throws Exception {
        setThreshold("LOCATION", LOCATION, 20, "aggregator orders should go red sooner");
        Instant now = Instant.now();
        // Promised in ten minutes with twenty on the road: its ticket's own target passed ten minutes ago, the
        // order's promise has not, and the board says it is not late.
        UUID onTheRoad = fixtures.insertOrder(order("LS-ROAD")
                .at(TENANT, BRAND, LOCATION)
                .createdAt(now.minus(Duration.ofMinutes(30)))
                .promisedAt(now.plus(Duration.ofMinutes(10))));
        // Over, with its ticket still on the pass: a finished order is never flagged.
        UUID finished = fixtures.insertOrder(order("LS-FIN")
                .at(TENANT, BRAND, LOCATION)
                .status("COMPLETED")
                .createdAt(now.minus(Duration.ofMinutes(50)))
                .unpromised());
        // Every ticket opens only now, as a ticket does after approval; the targets are the promise less the
        // road, the way the kitchen writes them.
        seedTicket(aggregatorUnpromised, "KS-AGG", null);
        seedTicket(nativePromised, "KS-NAT", now.plus(Duration.ofMinutes(25)));
        seedTicket(scheduledHoursAhead, "KS-SCH", now.plus(Duration.ofHours(2)));
        seedTicket(onTheRoad, "KS-ROAD", now.minus(Duration.ofMinutes(10)));
        seedTicket(finished, "KS-FIN", null);

        List<UUID> lateOnTheBoard = lateOnTheBoard(LOCATION);
        assertThat(lateOnTheBoard)
                .as("the board, which the kitchen's surfaces must agree with")
                .contains(aggregatorUnpromised)
                .doesNotContain(nativePromised, scheduledHoursAhead, onTheRoad, finished);

        JsonNode queue = readJson(KITCHEN + "/tickets");
        JsonNode wall = readJson(KITCHEN + "/vdu");
        Map<UUID, String> labelled = Map.of(
                aggregatorUnpromised, "KS-AGG",
                nativePromised, "KS-NAT",
                scheduledHoursAhead, "KS-SCH",
                onTheRoad, "KS-ROAD",
                finished, "KS-FIN");

        for (JsonNode surface : List.of(queue, wall)) {
            assertThat(surface.get("tickets")).as("one ticket per order").hasSize(labelled.size());
            for (Map.Entry<UUID, String> order : labelled.entrySet()) {
                JsonNode ticket = ticketLabelled(surface, order.getValue());
                assertThat(levelOnTheWire(ticket, wall.get("lateness"), now))
                        .as("%s, read the way a screen reads it, against the board", order.getValue())
                        .isEqualTo(levelOnTheBoard(order.getKey()));
            }
        }

        JsonNode aggregator = ticketLabelled(queue, "KS-AGG");
        assertThat(Instant.parse(aggregator.get("orderCreatedAt").asString()))
                .as("the order's own creation, not the ticket's opening")
                .isEqualTo(createdAtOf(aggregatorUnpromised))
                .isBefore(Instant.parse(aggregator.get("createdAt").asString()).minus(Duration.ofMinutes(29)));
        assertThat(ticketLabelled(wall, "KS-AGG").get("orderCreatedAt").asString())
                .isEqualTo(aggregator.get("orderCreatedAt").asString());
        assertThat(ticketLabelled(queue, "KS-ROAD").get("orderPromisedAt").asString())
                .as("the order's promise, not the ticket's target less the road")
                .isEqualTo(promisedAtOf(onTheRoad).toString())
                .isNotEqualTo(
                        ticketLabelled(queue, "KS-ROAD").get("targetReadyAt").asString());
        assertThat(ticketLabelled(queue, "KS-FIN").get("orderTerminal").asBoolean())
                .isTrue();
        assertThat(ticketLabelled(wall, "KS-FIN").get("orderTerminal").asBoolean())
                .isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode readJson(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(OWNER))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode ticketLabelled(JsonNode surface, String label) {
        for (JsonNode ticket : surface.get("tickets")) {
            if (label.equals(ticket.get("sequenceLabel").asString())) {
                return ticket;
            }
        }
        throw new AssertionError("no ticket labelled " + label + " in " + surface);
    }

    /**
     * What a screen concludes about a ticket from the fields the server sent it and the policy the wall
     * carries, by the domain's own evaluator ({@link OrderLatenessPolicy#evaluate}) -- the rule the board's
     * filter is held to.
     */
    private static OrderLatenessPolicy.LatenessLevel levelOnTheWire(JsonNode ticket, JsonNode policy, Instant now) {
        JsonNode delivery = policy.get("delivery");
        OrderLatenessPolicy.LatenessThresholds thresholds = new OrderLatenessPolicy.LatenessThresholds(
                delivery.get("atRiskBeforeSeconds").asInt(),
                delivery.get("lateAfterSeconds").asInt(),
                delivery.get("noPromiseFallbackSeconds").asInt());
        JsonNode promised = ticket.get("orderPromisedAt");
        OrderPromise promise = promised.isNull()
                ? OrderPromise.notPromised()
                : OrderPromise.scheduled(Instant.parse(promised.asString()));
        return new OrderLatenessPolicy(thresholds, thresholds, thresholds)
                .evaluate(
                        FulfillmentMode.DELIVERY,
                        promise,
                        ticket.get("orderTerminal").asBoolean() ? OrderStatus.COMPLETED : OrderStatus.CONFIRMED,
                        Instant.parse(ticket.get("orderCreatedAt").asString()),
                        now);
    }

    /** The order header's read of the order, as a domain level (the board's other face). */
    private OrderLatenessPolicy.LatenessLevel levelOnTheBoard(UUID orderId) throws Exception {
        return switch (severityOf(orderId)) {
            case "LATE" -> OrderLatenessPolicy.LatenessLevel.LATE;
            case "AT_RISK" -> OrderLatenessPolicy.LatenessLevel.AT_RISK;
            default -> OrderLatenessPolicy.LatenessLevel.NORMAL;
        };
    }

    private Instant createdAtOf(UUID orderId) {
        return jdbc.sql("SELECT created_at FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private Instant promisedAtOf(UUID orderId) {
        return jdbc.sql("SELECT promised_at FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /**
     * A FIRED ticket for the order, opened now: the shape the live queue and the wall read. {@code
     * targetReadyAt} is what the kitchen would have written, the promise less the road.
     */
    private void seedTicket(UUID orderId, String label, @Nullable Instant targetReadyAt) {
        jdbc.sql("""
                INSERT INTO kitchen.tickets (
                    id, tenant_id, brand_id, location_id, order_id, sequence_label,
                    fulfilment_mode, channel_code, status, release_mode, released_at, target_ready_at,
                    routing_version, version, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :orderId, :label, 'DELIVERY', 'TELEGRAM', 'FIRED',
                    'AUTO_ON_CONFIRM', now(), :target, 1, 1, now(), now())
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("label", label)
                .param("target", targetReadyAt == null ? null : targetReadyAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private List<UUID> lateOnTheBoard(UUID location) throws Exception {
        MvcResult result = mvc.perform(get(ORDERS + location + "/orders/board")
                        .param("late", "true")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        List<UUID> ids = new ArrayList<>();
        JSON.readTree(result.getResponse().getContentAsString())
                .get("items")
                .forEach(item -> ids.add(UUID.fromString(item.get("orderId").asString())));
        return ids;
    }

    private String severityOf(UUID orderId) throws Exception {
        MvcResult result = mvc.perform(get(LATENESS + LOCATION + "/orders/" + orderId + "/lateness")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString())
                .get("level")
                .asString();
    }

    private int fallbackSeconds(UUID location) throws Exception {
        MvcResult result = mvc.perform(
                        get(LATENESS + location + "/orders/lateness-policy").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode policy = JSON.readTree(result.getResponse().getContentAsString());
        int delivery = policy.get("delivery").get("noPromiseFallbackSeconds").asInt();
        assertThat(policy.get("pickup").get("noPromiseFallbackSeconds").asInt()).isEqualTo(delivery);
        assertThat(policy.get("dineIn").get("noPromiseFallbackSeconds").asInt()).isEqualTo(delivery);
        return delivery;
    }

    private void setThreshold(String scopeType, UUID location, int minutes, String reason) throws Exception {
        // The console always sends explicitNull (ConfigurationApi.setValue), and Jackson 3 refuses a body
        // that omits the primitive, so this sends what the console's real JSON carries.
        String body = "{\"scopeType\":\"" + scopeType + "\",\"brandId\":\"" + BRAND + "\",\"locationId\":\""
                + location + "\",\"integerValue\":" + minutes + ",\"explicitNull\":false,\"reason\":\"" + reason
                + "\"}";
        MvcResult result = mvc.perform(post(CONFIG + "/keys/" + LATE_THRESHOLD + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "set-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'lateness surfaces endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("scopeId", tenantId)
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
