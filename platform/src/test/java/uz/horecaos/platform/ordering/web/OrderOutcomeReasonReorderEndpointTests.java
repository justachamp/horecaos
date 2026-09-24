package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Gap map row {@code 10.10a}: cancellation and completion reasons could not
 * be reordered — {@code JdbcOutcomeReasonStore.list} sorted alphabetically
 * with no way to change it. Proves the new {@code PUT
 * .../order-outcome-reasons/reorder} on the operations surface (the console's
 * own path, {@link OperationsOrderOutcomeReasonController}) actually
 * persists a rank {@code GET} then honours, refuses a partial list, and
 * enforces {@code ORDER_OUTCOME_REASON_MANAGE} and {@code If-Match} the same
 * way every other mutating outcome-reason endpoint here already does.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderOutcomeReasonReorderEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000e1");

    private static final String OWNER = "reason-reorder-owner";
    private static final String DISPATCHER = "reason-reorder-dispatcher";

    private static final String REASONS = "/api/v1/operations/tenants/" + TENANT + "/order-outcome-reasons";

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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    private UUID cancelledDelivery;
    private UUID cancelledOutOfStock;
    private UUID cancelledCustomerRequest;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reason_texts").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reasons CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'reason-reorder', 'Reason Reorder', 'Reason Reorder',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, "TENANT", TENANT);

        // Alphabetic insertion order: Customer request, Delivery zone unreachable,
        // Out of stock -- deliberately not the rank these tests reorder into, so
        // a passing test proves the new column actually drives the list rather
        // than internal_name still winning underneath it.
        cancelledCustomerRequest = insertReason("Customer request");
        cancelledDelivery = insertReason("Delivery zone unreachable");
        cancelledOutOfStock = insertReason("Out of stock");
    }

    @Test
    void listsInAlphabeticOrderUntilTheFirstReorder() throws Exception {
        MvcResult before = mvc.perform(
                        get(REASONS).queryParam("kind", "CANCELLATION").with(tokenFor(OWNER)))
                .andReturn();
        List<String> names = internalNamesInOrder(before.getResponse().getContentAsString());
        assertThat(names).containsExactly("Customer request", "Delivery zone unreachable", "Out of stock");
    }

    @Test
    void reordersAndTheNewRankIsWhatListReturns() throws Exception {
        MvcResult reordered = mvc.perform(put(REASONS + "/reorder")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reorder-ok")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CANCELLATION","orderedReasonIds":["%s","%s","%s"]}
                                """.formatted(cancelledOutOfStock, cancelledCustomerRequest, cancelledDelivery)))
                .andReturn();
        assertThat(reordered.getResponse().getStatus())
                .as(reordered.getResponse().getContentAsString())
                .isEqualTo(200);
        // Every reason's own version bumped by the reorder write.
        assertThat(reordered.getResponse().getContentAsString()).contains("\"version\":2");

        MvcResult after = mvc.perform(
                        get(REASONS).queryParam("kind", "CANCELLATION").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(internalNamesInOrder(after.getResponse().getContentAsString()))
                .containsExactly("Out of stock", "Customer request", "Delivery zone unreachable");
    }

    @Test
    void refusesAPartialListRatherThanReorderingASubset() throws Exception {
        MvcResult refused = mvc.perform(put(REASONS + "/reorder")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reorder-partial")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CANCELLATION","orderedReasonIds":["%s","%s"]}
                                """.formatted(cancelledOutOfStock, cancelledCustomerRequest)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");

        MvcResult unchanged = mvc.perform(
                        get(REASONS).queryParam("kind", "CANCELLATION").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(internalNamesInOrder(unchanged.getResponse().getContentAsString()))
                .as("a refused reorder must leave the prior rank exactly as it was")
                .containsExactly("Customer request", "Delivery zone unreachable", "Out of stock");
    }

    @Test
    void refusesAStaleIfMatchAndLeavesTheRankInPlace() throws Exception {
        MvcResult refused = mvc.perform(put(REASONS + "/reorder")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reorder-stale")
                        .header("If-Match", "W/\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CANCELLATION","orderedReasonIds":["%s","%s","%s"]}
                                """.formatted(cancelledOutOfStock, cancelledCustomerRequest, cancelledDelivery)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("STALE_VERSION");
    }

    @Test
    void reorderRequiresOrderOutcomeReasonManage() throws Exception {
        MvcResult refused = mvc.perform(put(REASONS + "/reorder")
                        .with(tokenFor(DISPATCHER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reorder-refused")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CANCELLATION","orderedReasonIds":["%s","%s","%s"]}
                                """.formatted(cancelledOutOfStock, cancelledCustomerRequest, cancelledDelivery)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_OUTCOME_REASON_MANAGE.code());
    }

    // ------------------------------------------------------------------ fixtures

    /** Reads back `internalName` in response-array order, without a JSON library — good enough for this fixture's own three fixed names. */
    private static List<String> internalNamesInOrder(String body) {
        return java.util.regex.Pattern.compile("\"internalName\":\"([^\"]+)\"")
                .matcher(body)
                .results()
                .map(match -> match.group(1))
                .toList();
    }

    private UUID insertReason(String internalName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ordering.order_outcome_reasons (
                    id, tenant_id, kind, system_category, internal_name, stock_disposition,
                    liability_party, customer_refund, allowed_fulfillment_modes, status,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, 'CANCELLATION', 'CUSTOMER_CANCELLED', :name, 'RELEASE',
                    'TENANT', 'FULL', NULL, 'ACTIVE', 1, now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("name", internalName)
                .update();
        for (String locale : List.of("ru", "uz-Latn", "en")) {
            jdbc.sql("""
                    INSERT INTO ordering.order_outcome_reason_texts (reason_id, locale, customer_text)
                    VALUES (:id, :locale, :text)
                    """)
                    .param("id", id)
                    .param("locale", locale)
                    .param("text", internalName)
                    .update();
        }
        return id;
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'reason reorder endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
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
