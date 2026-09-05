package uz.horecaos.platform.audit.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0025 and ADR 0027: the most security-sensitive endpoint in the platform.
 *
 * <p>{@code approval.decide} on the annotation is admission to the console and
 * nothing more, so the tests that matter are the ones behind it: a caller who
 * reaches the surface and still cannot sign this request, because the governing
 * policy named a capability they do not hold or because they are the person who
 * raised it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalRequestEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120e02");

    private static final String OWNER = "approval-decide-owner";
    private static final String FINANCE = "approval-decide-finance";
    private static final String STAFF = "approval-decide-support";

    private static final String ACTION = "payments.remedy.record";
    private static final String PARAMETERS = "b".repeat(64);

    /**
     * A private database on the JVM's one shared PostgreSQL, handed to Spring as
     * properties.
     *
     * <p>Not {@code @ServiceConnection}. That annotation takes precedence over
     * every {@code spring.datasource.*} property, so a URL registered below would
     * be silently ignored and this suite would go on running against a container
     * of its own — the conversion would look done and change nothing.
     *
     * <p>Assigned in {@link #properties} rather than in a field initializer: a
     * field initializer runs at class load, which is before the {@code @BeforeAll}
     * that skips this class when Docker is absent, and would turn a clean skip
     * into an {@code ExceptionInInitializerError}.
     *
     * <p>Never closed. Hikari holds connections to it and Spring caches the
     * context past the last test in this class, so dropping the database here
     * would surface as a failure in whichever class ran next. It dies with the
     * container.
     *
     * <p>Boot's Flyway autoconfiguration is left on. Against a clone already at
     * the latest version it is a validate, not a migration, and it is the only
     * thing in this suite that would notice a clone that arrived at the wrong one.
     */
    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method
    // runs (see the javadoc above for why it cannot move to @BeforeAll instead).
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the approval decision endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The sweep would otherwise race the lapsed-request fixture below and
        // decide the test's outcome for it.
        registry.add("horecaos.audit.approval.expiry.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "tenant-approval-decide-one");
        insertTenant(OTHER_TENANT, "tenant-approval-decide-two");
        grant(OWNER, TENANT, PlatformRole.TENANT_OWNER);
        grant(FINANCE, TENANT, PlatformRole.TENANT_FINANCE);
        grant(STAFF, TENANT, PlatformRole.SUPPORT_AGENT);
    }

    @Test
    void aCallerWithoutTheConsoleCapabilityNeverReachesTheDecision() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);

        MvcResult refused = mvc.perform(decision(requestId)
                        .with(tokenFor(STAFF))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.APPROVAL_DECIDE.code());
        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    /**
     * The console admits finance; the policy decides whether finance may sign
     * <em>this</em>. Here it names a capability only the owner holds, so a caller
     * who is past the annotation is still refused — which is the whole reason the
     * decide surface lives in the audit module rather than in payments.
     */
    @Test
    void reachingTheConsoleIsNotHoldingThePolicysApproverCapability() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.COURIER_PAYOUT_AUTHORISE);

        MvcResult refused = mvc.perform(decision(requestId)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.COURIER_PAYOUT_AUTHORISE.code());
        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    @Test
    void theRequesterIsRefusedAtTheEndpointToo() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);

        MvcResult refused = mvc.perform(decision(requestId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(status(requestId))
                .as("the owner holds refund.approve and raised this one; four eyes outranks that")
                .isEqualTo("PENDING");
    }

    @Test
    void aSecondPersonHoldingThePolicysCapabilityCanApprove() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);

        MvcResult approved = mvc.perform(decision(requestId)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(approved.getResponse().getContentAsString()).contains("\"status\":\"APPROVED\"");
        assertThat(status(requestId)).isEqualTo("APPROVED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.approve' AND actor_subject = :subject
                """).param("subject", FINANCE).query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void aRequestFromAnotherTenantIsNotFound() throws Exception {
        UUID requestId = pendingRequest(OTHER_TENANT, OWNER, Capability.REFUND_APPROVE);

        MvcResult refused = mvc.perform(post("/api/v1/control-plane/tenants/" + TENANT + "/approval-requests/"
                                + requestId + "/decision")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a query constrained on an identifier alone would have decided this one")
                .isEqualTo(404);
        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    @Test
    void aLapsedRequestIsRefused() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);
        jdbc.sql("UPDATE audit.approval_requests SET expires_at = :past WHERE id = :id")
                .param("id", requestId)
                .param("past", clock.instant().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();

        MvcResult refused = mvc.perform(decision(requestId)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    @Test
    void theQueueIsReadableByTheConsoleAndNobodyElse() throws Exception {
        pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);
        String queue = "/api/v1/control-plane/tenants/" + TENANT + "/approval-requests";

        assertThat(mvc.perform(get(queue).with(tokenFor(STAFF)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a support agent who cannot sign has no business reading what is waiting")
                .isEqualTo(403);

        MvcResult listed = mvc.perform(get(queue).with(tokenFor(FINANCE))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString())
                .contains(ACTION)
                .contains(PARAMETERS)
                .contains("\"mayDecide\":true")
                .as("ADR 0029: the maker's prose about a named customer does not travel here")
                .doesNotContain("Customer reported a missing item");
    }

    // --- the same two rules, reached through the operations-surface mirror
    // (Staff IA 9.4's approvals worklist) rather than control-plane. The
    // service underneath is identical; what is under test here is that the
    // operations mapping enforces four eyes exactly as strictly as the
    // control-plane one does, not a second, looser copy of the rule.

    @Test
    void theRequesterIsRefusedAtTheOperationsEndpointToo() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);

        MvcResult refused = mvc.perform(operationsDecision(requestId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
        assertThat(status(requestId))
                .as("the owner holds refund.approve and raised this one; four eyes outranks that "
                        + "on the operations mapping exactly as it does on control-plane")
                .isEqualTo("PENDING");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.decision.refused' AND actor_subject = :subject
                """).param("subject", OWNER).query(Long.class).single())
                .as("the refusal is recorded even though the decision call threw")
                .isEqualTo(1L);
    }

    @Test
    void aSecondPersonHoldingThePolicysCapabilityCanApproveViaOperations() throws Exception {
        UUID requestId = pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);

        MvcResult approved = mvc.perform(operationsDecision(requestId)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "decide-ops-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andReturn();

        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(approved.getResponse().getContentAsString()).contains("\"status\":\"APPROVED\"");
        assertThat(status(requestId)).isEqualTo("APPROVED");
    }

    @Test
    void theOperationsQueueIsReadableByTheConsoleAndNobodyElse() throws Exception {
        pendingRequest(TENANT, OWNER, Capability.REFUND_APPROVE);
        String queue = "/api/v1/operations/tenants/" + TENANT + "/approval-requests";

        assertThat(mvc.perform(get(queue).with(tokenFor(STAFF)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a support agent who cannot sign has no business reading what is waiting, "
                        + "on this mapping either")
                .isEqualTo(403);

        MvcResult listed = mvc.perform(get(queue).with(tokenFor(FINANCE))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString()).contains(ACTION).contains("\"mayDecide\":true");
    }

    // --- fixtures ---------------------------------------------------------

    private static MockHttpServletRequestBuilder decision(UUID requestId) {
        return post("/api/v1/control-plane/tenants/" + TENANT + "/approval-requests/" + requestId + "/decision");
    }

    private static MockHttpServletRequestBuilder operationsDecision(UUID requestId) {
        return post("/api/v1/operations/tenants/" + TENANT + "/approval-requests/" + requestId + "/decision");
    }

    private static String approveBody() {
        return """
                {"decision":"APPROVE","reason":"Checked the order and the customer's account"}""";
    }

    private String status(UUID requestId) {
        return jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
    }

    /**
     * A policy and the request it produced, written directly. The maker-checker
     * path that creates one is exercised by {@code JdbcApprovalServiceTests}; what
     * is under test here is who may decide it.
     */
    private UUID pendingRequest(UUID tenantId, String requestedBy, Capability approverCapability) {
        UUID policyId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :actionCode, 'TENANT',
                        CAST('{"description":"above 1,000,000 UZS"}' AS jsonb),
                        :approver, :now, 1, 'platform-admin')
                """)
                .param("id", policyId)
                .param("tenantId", tenantId)
                .param("actionCode", ACTION)
                .param("approver", approverCapability.code())
                .param("now", clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();

        UUID requestId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit.approval_requests (
                    id, tenant_id, action_code, parameters_hash, scope_type, scope_id,
                    policy_id, policy_is_platform, policy_version, threshold_description,
                    status, requested_by, requested_at, reason, expires_at)
                -- policy_is_platform is false because the policy above is a TENANT
                -- one in this same tenant. V0088 keys fk_approval_request_policy
                -- on the declaration, so a fixture that declared the wrong owner
                -- would be refused rather than quietly stored.
                VALUES (:id, :tenantId, :actionCode, :hash, 'TENANT', :tenantId,
                        :policyId, false, 1, 'above 1,000,000 UZS', 'PENDING',
                        :requestedBy, :now, 'Customer reported a missing item', :expiresAt)
                """)
                .param("id", requestId)
                .param("tenantId", tenantId)
                .param("actionCode", ACTION)
                .param("hash", PARAMETERS)
                .param("policyId", policyId)
                .param("requestedBy", requestedBy)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("expiresAt", clock.instant().plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
                .update();
        return requestId;
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(String subject, UUID tenantId, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'approval decision endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated rather than the column's own now(): a grant read
                // back through JdbcAuthorizationService.grantsFor compares
                // valid_from against this JVM's Clock.systemUTC(), and under
                // heavy concurrent fork load the container's own wall clock can
                // momentarily skew against it.
                .param("validFrom", clock.instant().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
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
