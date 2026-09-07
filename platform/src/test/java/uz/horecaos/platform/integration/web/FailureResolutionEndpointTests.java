package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0006's maker-checker path, over the real HTTP surface end to end.
 *
 * <p>ADR 0006's own status line once claimed {@code SecondApproverRequiredException}
 * "still has no entry in the ADR 0031 error handler, so a resolve that a policy
 * sends to the maker-checker answers 500 instead of a problem document naming
 * the approval being waited on". That claim was never true — the exception has
 * extended {@code ApiException} since it was written, and {@code
 * GlobalApiErrorHandler}'s generic {@code ApiException} handler has always
 * caught every subtype of it — but nothing exercised the claim through a real
 * {@code MockMvc} round trip until this class, so nothing would have failed had
 * the claim become true. This is that proof: a real approval policy, a real
 * dead-lettered event, a real POST to {@code /resolve}, and the response an
 * operator actually receives.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FailureResolutionEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f21");

    private static final String OPERATOR = "failure-resolution-operator";

    private static final String FAILURES = "/api/v1/control-plane/integration/failures";

    private static TestDatabase.@Nullable Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the failure resolution endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        // Fixtures, not work in flight; a running relay or listener would move
        // the row this test resolves out from underneath its assertions.
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("horecaos.messaging.inbox.listener.enabled", () -> "false");
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
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        // CASCADE reaches iam.roles and iam.grants, both of which reference
        // tenants, so the registry is rebuilt through the production writer.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "tenant-failure-resolution");
        // platform-admin, not a narrower bundle: integration.failure.resolve is
        // not yet in any bundle but the superuser (ADR 0006's own "Open inputs"
        // still names the integration-operator role as undefined), and this test
        // is about the error mapping, not about which bundle should hold it.
        grantPlatformAdmin(OPERATOR);
    }

    @Test
    void aResolveThatNeedsASecondApproverAnswers422NamingTheRequestNotACrash() throws Exception {
        insertApprovalPolicy(TENANT);
        UUID eventId = deadLetteredOutboxEvent(TENANT);

        MvcResult result = mvc.perform(post(FAILURES + "/outbox/" + eventId + "/resolve")
                        .with(tokenFor(OPERATOR))
                        .header("Idempotency-Key", "resolve-" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"UNCERTAIN_EXTERNAL_OUTCOME",
                                 "reason":"provider says no charge",
                                 "evidenceReference":"recon-2026-09-07-01"}
                                """))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("the platform behaved correctly and recorded an approval request; "
                        + "that is not a server failure")
                .isEqualTo(422);
        assertThat(body)
                .as("a stable code a client branches on (ADR 0031), not the generic "
                        + "'current state refuses this' bucket every unrelated 422 in this "
                        + "platform shares")
                .contains("\"code\":\"SECOND_APPROVER_REQUIRED\"");
        assertThat(body)
                .as("naming which request a checker has to decide, so the operator has "
                        + "something to act on rather than a bare failure")
                .contains("\"approvalRequestId\"")
                .contains("\"approvalStatus\":\"PENDING\"");

        assertThat(outboxStatus(eventId))
                .as("the refusal is correct and nothing is resolved while approval is pending")
                .isEqualTo("DEAD_LETTER");
        assertThat(pendingApprovalRequestIds())
                .as("the request the response named is a real row a checker can open, "
                        + "not just words in a problem document")
                .hasSize(1);
    }

    private String outboxStatus(UUID eventId) {
        return jdbc.sql("SELECT status FROM integration.outbox_events WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private List<UUID> pendingApprovalRequestIds() {
        return jdbc.sql("SELECT id FROM audit.approval_requests WHERE status = 'PENDING'")
                .query(UUID.class)
                .list();
    }

    private UUID deadLetteredOutboxEvent(UUID tenantId) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.outbox_events (
                    event_id, event_type, event_version, tenant_id, aggregate_type, aggregate_id,
                    topic, partition_key, correlation_id, occurred_at, payload, status,
                    attempt_count, dead_lettered_at, error_code, last_error)
                VALUES (
                    :eventId, 'PaymentCaptured', 1, :tenantId, 'Payment', :aggregateId,
                    'payments.events', :aggregateId, 'correlation-1', now(),
                    CAST(:payload AS jsonb), 'DEAD_LETTER',
                    5, now(), 'UNCERTAIN_EXTERNAL_OUTCOME', 'gateway timed out mid-capture')
                """)
                .param("eventId", eventId)
                .param("tenantId", tenantId)
                .param("aggregateId", UUID.randomUUID())
                .param("payload", "{\"orderNumber\":\"A-91\"}")
                .update();
        return eventId;
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    /** Bound to the same action every {@code UNCERTAIN_EXTERNAL_OUTCOME} resolve routes through. */
    private void insertApprovalPolicy(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, 'integration.failure.resolve', 'TENANT',
                        '{"description":"any uncertain provider outcome"}'::jsonb,
                        'integration.failure.resolve', :validFrom, 1, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                // Backdated against the real clock rather than database now(), for
                // the same reason grantPlatformAdmin below backdates its grant.
                .param("validFrom", OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    private void grantPlatformAdmin(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'failure resolution endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes((subject + PlatformRole.PLATFORM_ADMIN.code())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                // Backdated rather than the column's own now(): a grant read back
                // through JdbcAuthorizationService.grantsFor compares valid_from
                // against this JVM's Clock.systemUTC(), and under heavy concurrent
                // fork load the container's own wall clock can momentarily skew
                // against it.
                .param("validFrom", OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC))
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
