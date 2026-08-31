package uz.horecaos.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.PlatformGrantAuthorityAdapter;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap A of the 2026-08-30 proving run, the second half: a {@code PLATFORM}-scope
 * grant behind ADR 0027's maker-checker, with the real {@link JdbcApprovalService}
 * rather than a fake that always says yes — "the approval flow actually gates
 * it" is not provable any other way.
 */
class PlatformGrantServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PLATFORM_GRANTER = "platform-granter-1";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private PlatformGrantService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();

        new RoleRegistrySynchronizer(jdbc).synchronize();
        insertPlatformGrant(PLATFORM_GRANTER, PlatformRole.PLATFORM_ADMIN);

        JdbcAuthorizationService authorization = new JdbcAuthorizationService(jdbc, CLOCK, () -> null) {
            @Override
            public void evictGrants(String subject, UUID tenantId) {
                // no cache in this fixture
            }
        };
        GrantManagementService grantManagement =
                new GrantManagementService(jdbc, authorization, authorization, event -> {}, CLOCK);
        ApprovalService approvals = new JdbcApprovalService(
                jdbc, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()), CLOCK, new SimpleMeterRegistry());
        service = new PlatformGrantService(new PlatformGrantAuthorityAdapter(grantManagement), approvals);
    }

    @Test
    void grantSucceedsImmediatelyWithNoConfiguredApprovalPolicy() {
        var outcome = doGrant("new-support-1", "platform-support");

        assertThat(outcome.status()).isEqualTo(PlatformGrantService.Outcome.Status.GRANTED);
        assertThat(outcome.grantId()).isNotNull();
        assertThat(activeGrant("new-support-1")).isEqualTo("platform-support");
    }

    @Test
    void platformAdminIsStillNeverGrantableThroughThisSurface() {
        var outcome = assertThrowsIllegalArgument(() -> doGrant("someone", "platform-admin"));
        assertThat(outcome).contains("Keycloak");
    }

    /**
     * The point of Gap A's approval half: a deployment that configures a
     * second signature on this one action actually gets one. Authors the
     * {@code audit.approval_policies} row directly — the same shape the
     * runbook's own "exercising the two-principal path" section documents,
     * since no PLATFORM-scope policy-authoring endpoint exists and this task
     * does not add one.
     */
    @Test
    void aConfiguredApprovalPolicyActuallyGatesTheGrant() {
        authorApprovalPolicy();

        var requested = doGrant("new-support-2", "platform-support");

        assertThat(requested.status()).isEqualTo(PlatformGrantService.Outcome.Status.AWAITING_APPROVAL);
        assertThat(requested.approvalRequestId()).isNotNull();
        assertThat(activeGrant("new-support-2"))
                .as("nothing is granted until the second signature")
                .isNull();

        decide(requested.approvalRequestId());

        var granted = doGrant("new-support-2", "platform-support");

        assertThat(granted.status()).isEqualTo(PlatformGrantService.Outcome.Status.GRANTED);
        assertThat(activeGrant("new-support-2")).isEqualTo("platform-support");
    }

    @Test
    void revokingTakesEffectImmediatelyWithNoConfiguredApprovalPolicy() {
        var granted = doGrant("new-support-3", "platform-support");

        var revoked = doRevoke(granted.grantId());

        assertThat(revoked.status()).isEqualTo(PlatformGrantService.Outcome.Status.REVOKED);
        assertThat(activeGrant("new-support-3")).isNull();
    }

    @Test
    void aConfiguredApprovalPolicyGatesRevocationToo() {
        var granted = doGrant("new-support-4", "platform-support");
        authorApprovalPolicy();

        var requested = doRevoke(granted.grantId());

        assertThat(requested.status()).isEqualTo(PlatformGrantService.Outcome.Status.AWAITING_APPROVAL);
        assertThat(activeGrant("new-support-4"))
                .as("the grant must still be in force until the revoke itself is approved")
                .isEqualTo("platform-support");

        decide(requested.approvalRequestId());

        var revoked = doRevoke(granted.grantId());
        assertThat(revoked.status()).isEqualTo(PlatformGrantService.Outcome.Status.REVOKED);
        assertThat(activeGrant("new-support-4")).isNull();
    }

    @Test
    void listShowsOnlyActivePlatformGrants() {
        var granted = doGrant("new-support-5", "platform-support");
        doRevoke(granted.grantId());
        doGrant("new-support-6", "platform-support");

        assertThat(service.list())
                .extracting(
                        uz.horecaos.platform.iam.api.grants.PlatformGrantAuthority.PlatformGrantView::principalSubject)
                .contains("new-support-6")
                .doesNotContain("new-support-5");
    }

    /**
     * Spending an {@code Approved} outcome's grant asserts it is running
     * inside a transaction (ADR 0027: the spend must commit with the action
     * or roll back with it) — a check {@code @Transactional} satisfies in
     * production but does nothing about here, since this test builds {@link
     * #service} directly rather than through a Spring proxy. Wrapping every
     * call the same way {@code ApprovalDecisionServiceTests} does is what
     * makes the {@code Approved} path exercisable at all.
     */
    private PlatformGrantService.Outcome doGrant(String principalSubject, String roleCode) {
        return transactions.execute(status ->
                service.grant(principalSubject, roleCode, "onboarding a support agent", null, PLATFORM_GRANTER));
    }

    private PlatformGrantService.Outcome doRevoke(UUID grantId) {
        return transactions.execute(status -> service.revoke(grantId, PLATFORM_GRANTER, "role no longer needed"));
    }

    private void decide(UUID requestId) {
        var approvals = new JdbcApprovalService(
                jdbc, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()), CLOCK, new SimpleMeterRegistry());
        approvals.decide(
                requestId, ApprovalService.Decision.APPROVE, ActorRef.user("second-signer", null), "looks right");
    }

    /** Mirrors docs/runbooks/proving-run.md's own instructions for exercising the two-principal path. */
    private void authorApprovalPolicy() {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, brand_id, location_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by, created_at)
                VALUES (:id, NULL, NULL, NULL, :actionCode, 'PLATFORM',
                        jsonb_build_object('description', 'Every platform grant needs a second signature'),
                        :capability, :validFrom, 1, 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("actionCode", ApprovalAction.IAM_PLATFORM_GRANT_MANAGE.code())
                .param("capability", Capability.APPROVAL_DECIDE.code())
                .param("validFrom", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private @Nullable String activeGrant(String subject) {
        return jdbc.sql("""
                SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                 WHERE g.principal_subject = :subject AND g.scope_type = 'PLATFORM' AND g.status = 'ACTIVE'
                """)
                .param("subject", subject)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void insertPlatformGrant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'fixture', 'fixture', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String assertThrowsIllegalArgument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            return expected.getMessage();
        }
    }
}
