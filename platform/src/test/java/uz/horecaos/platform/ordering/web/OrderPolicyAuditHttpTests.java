package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Staff row {@code 9.3a}: {@code OrderAcceptancePolicyService} and {@code
 * OrderOutcomeReasonService} used to write no audit fact of their own (the
 * shared policy author writes only a hash-carrying {@code tenant.policy.authored};
 * the reason service wrote nothing, only bumping the reason's {@code version}).
 * Over HTTP, so the assertion is on what an activity-log reader would find:
 * the actor the token names, the scope, the target, and a field-level
 * before/after for each write.
 *
 * <p>A refused write must leave no fact behind, in the same way the change
 * itself is rolled back -- proven for a stale {@code If-Match}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPolicyAuditHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000e7");
    private static final String OWNER = "policy-audit-owner";

    private static final String REASONS = "/api/v1/operations/tenants/" + TENANT + "/order-outcome-reasons";
    private static final String ACCEPTANCE = "/api/v1/control-plane/tenants/" + TENANT + "/order-acceptance-policy";

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reason_texts").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_outcome_reasons CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'policy-audit', 'Policy Audit', 'Policy Audit',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
    }

    // ---------------------------------------------------------------- reasons

    @Test
    @DisplayName("creating a reason records who authored it and every field it was created with")
    void creatingAReasonLeavesAFieldLevelFact() throws Exception {
        UUID reasonId = createReason("Нет товара", "WRITE_OFF");

        JsonNode fact = onlyFact("ordering.outcome-reason.created");
        assertThat(fact.get("actor_type").asText()).isEqualTo("USER");
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        assertThat(fact.get("scope_type").asText()).isEqualTo("TENANT");
        assertThat(fact.get("target_type").asText()).isEqualTo("ordering.outcome-reason");
        assertThat(fact.get("target_id").asText()).isEqualTo(reasonId.toString());
        assertThat(fact.get("target_version").asLong()).isEqualTo(1L);
        assertThat(fact.get("reason").asText())
                .as("ADR 0027: a user action always carries a why")
                .isNotBlank();

        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("stockDisposition").get("before").isNull())
                .as("a creation has no prior state")
                .isTrue();
        assertThat(change.get("stockDisposition").get("after").asText()).isEqualTo("WRITE_OFF");
        assertThat(change.get("internalName").get("after").asText()).isEqualTo("Нет товара");
        assertThat(change.get("liabilityParty").get("after").asText()).isEqualTo("TENANT");
        assertThat(change.get("customerRefund").get("after").asText()).isEqualTo("FULL");
        assertThat(change.get("customerTexts").get("after").get("uz-Latn").asText())
                .isEqualTo("Kechirasiz, taom tugadi");
    }

    @Test
    @DisplayName("rewriting a reason records the consequence field that changed, with its before and after")
    void updatingAReasonRecordsTheBeforeAndAfter() throws Exception {
        UUID reasonId = createReason("Нет товара", "RELEASE");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        MvcResult updated = mvc.perform(put(REASONS + "/" + reasonId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-update-1")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Нет товара", "WRITE_OFF", "Kechirasiz, taom tugadi")))
                .andReturn();
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);

        JsonNode fact = onlyFact("ordering.outcome-reason.updated");
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        assertThat(fact.get("target_id").asText()).isEqualTo(reasonId.toString());
        assertThat(fact.get("target_version").asLong()).isEqualTo(2L);

        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("stockDisposition").get("before").asText()).isEqualTo("RELEASE");
        assertThat(change.get("stockDisposition").get("after").asText()).isEqualTo("WRITE_OFF");
        assertThat(change.get("internalName").get("before").asText())
                .as("an unchanged field is carried with equal sides, so the reader sees what did not move")
                .isEqualTo(change.get("internalName").get("after").asText());
    }

    @Test
    @DisplayName("editing the customer wording alone is a recorded change to customerTexts")
    void editingOnlyTheWordingIsRecorded() throws Exception {
        UUID reasonId = createReason("Нет товара", "RELEASE");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        mvc.perform(put(REASONS + "/" + reasonId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-update-words")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Нет товара", "RELEASE", "Uzr, taom qolmadi")))
                .andReturn();

        JsonNode change = JSON.readTree(onlyFact("ordering.outcome-reason.updated")
                .get("change_document")
                .asText());
        assertThat(change.get("customerTexts").get("before").get("uz-Latn").asText())
                .isEqualTo("Kechirasiz, taom tugadi");
        assertThat(change.get("customerTexts").get("after").get("uz-Latn").asText())
                .isEqualTo("Uzr, taom qolmadi");
    }

    @Test
    @DisplayName("changing the system category is stored, and the fact describes the stored row, not the request")
    void aCategoryChangeIsStoredAndTheFactMatchesTheRow() throws Exception {
        UUID reasonId = createReason("Нет товара", "RELEASE", "ITEM_UNAVAILABLE");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        MvcResult updated = mvc.perform(put(REASONS + "/" + reasonId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-update-category")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Нет товара", "RELEASE", "Kechirasiz, taom tugadi", "CUSTOMER_CANCELLED")))
                .andReturn();
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);

        String stored = jdbc.sql("SELECT system_category FROM ordering.order_outcome_reasons WHERE id = :id")
                .param("id", reasonId)
                .query(String.class)
                .single();
        assertThat(stored)
                .as("the console offers the category for editing and answered 200, so it must have been written")
                .isEqualTo("CUSTOMER_CANCELLED");

        JsonNode change = JSON.readTree(onlyFact("ordering.outcome-reason.updated")
                .get("change_document")
                .asText());
        assertThat(change.get("systemCategory").get("before").asText()).isEqualTo("ITEM_UNAVAILABLE");
        assertThat(change.get("systemCategory").get("after").asText())
                .as("the fact must say what the row now holds, whatever the request asked for")
                .isEqualTo(stored);
    }

    @Test
    @DisplayName("a stale If-Match is refused and leaves no audit fact -- the write and its fact roll back together")
    void aRefusedWriteLeavesNoFact() throws Exception {
        UUID reasonId = createReason("Нет товара", "RELEASE");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        MvcResult refused = mvc.perform(put(REASONS + "/" + reasonId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-update-stale")
                        .header("If-Match", "W/\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson("Нет товара", "WRITE_OFF", "Kechirasiz, taom tugadi")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(factCount("ordering.outcome-reason.updated")).isZero();
    }

    @Test
    @DisplayName("retiring a reason records the status moving from ACTIVE to ARCHIVED")
    void archivingAReasonIsRecorded() throws Exception {
        UUID reasonId = createReason("Нет товара", "RELEASE");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        MvcResult archived = mvc.perform(delete(REASONS + "/" + reasonId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-archive-1")
                        .header("If-Match", "W/\"1\""))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(204);

        JsonNode fact = onlyFact("ordering.outcome-reason.archived");
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        assertThat(fact.get("target_id").asText()).isEqualTo(reasonId.toString());
        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("status").get("before").asText()).isEqualTo("ACTIVE");
        assertThat(change.get("status").get("after").asText()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("re-ranking the reasons records the ranking before and after as ordered ids")
    void reorderingIsRecorded() throws Exception {
        UUID first = createReason("Клиент передумал", "RELEASE");
        UUID second = createReason("Нет товара", "RELEASE");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        // If-Match is the sum of the active reasons' versions (1 + 1).
        MvcResult reordered = mvc.perform(put(REASONS + "/reorder")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-reorder-1")
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CANCELLATION","orderedReasonIds":["%s","%s"]}
                                """.formatted(second, first)))
                .andReturn();
        assertThat(reordered.getResponse().getStatus())
                .as(reordered.getResponse().getContentAsString())
                .isEqualTo(200);

        JsonNode fact = onlyFact("ordering.outcome-reason.reordered");
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("order").get("before").get(0).asText())
                .as("the alphabetically first reason led before the re-rank")
                .isEqualTo(first.toString());
        assertThat(change.get("order").get("after").get(0).asText()).isEqualTo(second.toString());
        assertThat(change.get("kind").get("after").asText()).isEqualTo("CANCELLATION");
    }

    // ------------------------------------------------------ acceptance policy

    @Test
    @DisplayName("publishing the acceptance policy records the mode and timeout moving from what was in force")
    void authoringTheAcceptancePolicyLeavesAFieldLevelFact() throws Exception {
        publishAcceptance("RESTAURANT_APPROVAL", 600, "go-live: a person accepts each order");

        JsonNode fact = onlyFact("ordering.acceptance-policy.authored");
        assertThat(fact.get("actor_type").asText()).isEqualTo("USER");
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        assertThat(fact.get("scope_type").asText()).isEqualTo("TENANT");
        assertThat(fact.get("target_type").asText()).isEqualTo("ordering.acceptance-policy");
        assertThat(fact.get("target_version").asLong()).isEqualTo(1L);
        assertThat(fact.get("reason").asText()).isEqualTo("go-live: a person accepts each order");

        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("mode").get("before").asText())
                .as("nothing was authored, so the platform default was in force")
                .isEqualTo("AUTO_CONFIRM");
        assertThat(change.get("mode").get("after").asText()).isEqualTo("RESTAURANT_APPROVAL");
        assertThat(change.get("approvalTimeoutSeconds").get("after").asInt()).isEqualTo(600);
        assertThat(change.get("policyVersion").get("before").asInt()).isZero();
        assertThat(change.get("policyVersion").get("after").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second publication diffs against the first version, not against the platform default")
    void aSecondPublicationDiffsAgainstTheFirst() throws Exception {
        publishAcceptance("RESTAURANT_APPROVAL", 600, "go-live");
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();

        publishAcceptance("RESTAURANT_APPROVAL", 60, "shorten the window");

        JsonNode fact = onlyFact("ordering.acceptance-policy.authored");
        assertThat(fact.get("target_version").asLong()).isEqualTo(2L);
        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("approvalTimeoutSeconds").get("before").asInt()).isEqualTo(600);
        assertThat(change.get("approvalTimeoutSeconds").get("after").asInt()).isEqualTo(60);
        assertThat(change.get("mode").get("before").asText()).isEqualTo("RESTAURANT_APPROVAL");
        assertThat(change.get("policyVersion").get("before").asInt()).isEqualTo(1);
        assertThat(change.get("policyVersion").get("after").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private UUID createReason(String internalName, String stockDisposition) throws Exception {
        return createReason(internalName, stockDisposition, "CUSTOMER_CANCELLED");
    }

    private UUID createReason(String internalName, String stockDisposition, String systemCategory) throws Exception {
        MvcResult created = mvc.perform(post(REASONS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonJson(internalName, stockDisposition, "Kechirasiz, taom tugadi", systemCategory)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        return UUID.fromString(JSON.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    private static String reasonJson(String internalName, String stockDisposition, String uzText) {
        return reasonJson(internalName, stockDisposition, uzText, "CUSTOMER_CANCELLED");
    }

    private static String reasonJson(
            String internalName, String stockDisposition, String uzText, String systemCategory) {
        return """
                {"kind":"CANCELLATION","systemCategory":"%s","internalName":"%s",
                 "stockDisposition":"%s","liabilityParty":"TENANT","customerRefund":"FULL",
                 "customerTexts":{"ru":"Извините","uz-Latn":"%s","en":"Sorry"}}
                """.formatted(systemCategory, internalName, stockDisposition, uzText);
    }

    private void publishAcceptance(String mode, int timeoutSeconds, String reason) throws Exception {
        MvcResult published = mvc.perform(post(ACCEPTANCE)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "acceptance-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"%s","approvalChannel":"HORECAOS_OPERATIONS",
                                 "approvalTimeoutSeconds":%d,"timeoutAction":"AUTO_REJECT",
                                 "rejectionReasonRequired":true,"notifyCustomerWhilePending":true,
                                 "reason":"%s"}
                                """.formatted(mode, timeoutSeconds, reason)))
                .andReturn();
        assertThat(published.getResponse().getStatus())
                .as(published.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode onlyFact(String actionCode) {
        List<String> rows =
                jdbc.sql("""
                        SELECT row_to_json(e)::text FROM (
                            SELECT actor_type, actor_subject, scope_type, target_type, target_id::text AS target_id,
                                   target_version, reason, change_document::text AS change_document
                            FROM audit.audit_events WHERE action_code = :code
                        ) e
                        """).param("code", actionCode).query(String.class).list();
        assertThat(rows).as("exactly one %s fact", actionCode).hasSize(1);
        return JSON.readTree(rows.getFirst());
    }

    private long factCount(String actionCode) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :code")
                .param("code", actionCode)
                .query(Long.class)
                .single();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'policy audit endpoint test', :validFrom)
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
