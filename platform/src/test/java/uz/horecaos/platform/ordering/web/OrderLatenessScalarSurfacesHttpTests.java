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
import uz.horecaos.platform.support.TestDatabase;
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

    // ---------------------------------------------------------------- helpers

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
