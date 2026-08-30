package uz.horecaos.platform.audit.infrastructure.persistence;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0027 maker-checker: the model ADRs 0006, 0012, 0013, 0021, and 0024 all
 * consume instead of each building their own.
 */
class JdbcApprovalServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120a01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120a02");
    private static final String ACTION = "payments.remedy.record";
    private static final String PARAMETERS = "c".repeat(64);
    private static final String OTHER_PARAMETERS = "d".repeat(64);

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private JdbcApprovalService approvals;
    private MutableClock clock;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
        // An approved outcome carries a grant that has to be spent in the
        // transaction performing the action, so exercising that path needs a real
        // transaction manager over the same DataSource.
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        meters = new SimpleMeterRegistry();
        approvals = new JdbcApprovalService(
                jdbc, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()), clock, meters);
        insertTenant(TENANT, "tenant-approval");
        insertTenant(OTHER_TENANT, "tenant-approval-two");
    }

    @Test
    void anActionWithNoPolicyProceedsWithoutApproval() {
        assertThat(approvals.requireApproval(command(PARAMETERS)))
                .isInstanceOf(ApprovalOutcome.NotRequired.class);
    }

    @Test
    void anActionRegisteredFailClosedRefusesWhenNoPolicyResolves() {
        ApprovalRequestCommand manualPenalty = new ApprovalRequestCommand(
                ApprovalAction.COURIER_MANUAL_PENALTY.code(), PARAMETERS,
                ResourceScope.tenant(TENANT), ActorRef.user("operator-1", "Operator One"),
                "Penalty requires a second signature", ApprovalRequestCommand.DEFAULT_VALIDITY);

        assertThatThrownBy(() -> approvals.requireApproval(manualPenalty))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.APPROVAL_POLICY_REQUIRED);
        assertThat(meters.find(JdbcApprovalService.RESOLUTION_METRIC)
                .tags("action", ApprovalAction.COURIER_MANUAL_PENALTY.code(),
                        "outcome", "unresolved",
                        "missing_policy_mode", "REQUIRE_CONFIGURED_POLICY")
                .counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aBrandPolicyGovernsOnlyTheBrandItNames() {
        UUID coveredBrand = UUID.randomUUID();
        UUID otherBrand = UUID.randomUUID();
        insertBrand(coveredBrand, "COVERED");
        insertBrand(otherBrand, "OTHER");
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, brand_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :brandId, :actionCode, 'BRAND',
                        jsonb_build_object('description', 'Covered brand only'),
                        'refund.approve', :validFrom, 1, 'platform-admin')
                """)
                .param("id", UUID.randomUUID()).param("tenantId", TENANT)
                .param("brandId", coveredBrand).param("actionCode", ACTION)
                .param("validFrom", clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();

        assertThat(approvals.requireApproval(command(ResourceScope.brand(TENANT, coveredBrand))))
                .isInstanceOf(ApprovalOutcome.Pending.class);
        assertThat(approvals.requireApproval(command(ResourceScope.brand(TENANT, otherBrand))))
                .isInstanceOf(ApprovalOutcome.NotRequired.class);
    }

    /**
     * The reason this survived a year unnoticed: an unconfigured control and a
     * control that decided "not required" produce the same silence, the same
     * green tests, and the same audit entry. This is the one place they differ.
     */
    @Test
    void anActionThatNoPolicyGovernsIsCounted() {
        approvals.requireApproval(command(PARAMETERS));

        assertThat(resolutions("unresolved"))
                .as("an operator has to be able to alert on a control nothing reaches")
                .isEqualTo(1.0);
        assertThat(meters.find(JdbcApprovalService.RESOLUTION_METRIC)
                .tag("outcome", "unresolved").counter().getId().getTags().toString())
                .as("the signal names the action and the scope, never the parameters or the reason")
                .contains("action", ACTION)
                .doesNotContain(PARAMETERS);
    }

    @Test
    void aGovernedActionIsCountedAsResolved() {
        insertPolicy(1, "amount above 1,000,000 UZS");

        approvals.requireApproval(command(PARAMETERS));

        assertThat(resolutions("resolved"))
                .as("a configured action has to be distinguishable from an unconfigured one")
                .isEqualTo(1.0);
        assertThat(meters.find(JdbcApprovalService.RESOLUTION_METRIC)
                .tag("outcome", "unresolved").counter())
                .isNull();
    }

    /**
     * The warning is deduplicated, and what it is deduplicated on decides who
     * hears it.
     *
     * <p>The key was {@code actionCode + "|" + scope.type()} with no tenant in
     * it, while the tenant appeared only in the line. On a multi-tenant
     * deployment that means exactly one tenant is ever named per action code per
     * process — whichever one happened to run a refund first — and every other
     * tenant's unconfigured control is silent for the life of the process. An
     * operator reading "author a policy to require a second signature" for tenant
     * A has no way to learn that tenants B through Z are in the same state.
     */
    @Test
    void everyTenantWithAnUnconfiguredControlIsNamedNotJustTheFirstOne() {
        ListAppender<ILoggingEvent> lines = captureApprovalLog();
        try {
            approvals.requireApproval(command(TENANT, PARAMETERS));
            approvals.requireApproval(command(OTHER_TENANT, PARAMETERS));
            approvals.requireApproval(command(OTHER_TENANT, OTHER_PARAMETERS));

            assertThat(warnings(lines))
                    .as("one warning per tenant, and the repeat for a tenant already named is dropped")
                    .hasSize(2);
            assertThat(warnings(lines))
                    .as("the claim that the log carries the tenant has to hold for every tenant")
                    .anySatisfy(line -> assertThat(line).contains(TENANT.toString()))
                    .anySatisfy(line -> assertThat(line).contains(OTHER_TENANT.toString()));
            assertThat(warnings(lines))
                    .as("ADR 0029: the requester and their free-text reason stay out of the log")
                    .allSatisfy(line -> assertThat(line)
                            .doesNotContain("Customer reported a missing item")
                            .doesNotContain("operator-1")
                            .doesNotContain(PARAMETERS));
        } finally {
            releaseApprovalLog(lines);
        }
    }

    /**
     * A tenant that configures its policy afterwards stops being counted and
     * stops being warned about; the metric keeps both tenants apart by outcome
     * even though the tenant itself is not a metric label (ADR 0023).
     */
    @Test
    void aTenantThatConfiguresItsPolicyIsNoLongerReportedUnresolved() {
        approvals.requireApproval(command(OTHER_TENANT, PARAMETERS));
        insertPolicy(1, "amount above 1,000,000 UZS");

        approvals.requireApproval(command(TENANT, PARAMETERS));

        assertThat(resolutions("unresolved")).isEqualTo(1.0);
        assertThat(resolutions("resolved")).isEqualTo(1.0);
    }

    private ListAppender<ILoggingEvent> captureApprovalLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        approvalLogger().addAppender(appender);
        return appender;
    }

    private void releaseApprovalLog(ListAppender<ILoggingEvent> appender) {
        approvalLogger().detachAppender(appender);
        appender.stop();
    }

    private static ch.qos.logback.classic.Logger approvalLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(JdbcApprovalService.class);
    }

    private static java.util.List<String> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private double resolutions(String outcome) {
        var counter = meters.find(JdbcApprovalService.RESOLUTION_METRIC)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void anActionUnderAPolicyBecomesPendingAndIsAudited() {
        insertPolicy(1, "amount above 1,000,000 UZS");

        ApprovalOutcome outcome = approvals.requireApproval(command(PARAMETERS));

        assertThat(outcome).isInstanceOf(ApprovalOutcome.Pending.class);
        assertThat(outcome.mayProceed())
                .as("a pending approval must never let the side effect run")
                .isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'approval.requested'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void requestingTwiceForTheSameParametersReusesTheSameRequest() {
        insertPolicy(1, "amount above 1,000,000 UZS");

        ApprovalOutcome first = approvals.requireApproval(command(PARAMETERS));
        ApprovalOutcome second = approvals.requireApproval(command(PARAMETERS));

        assertThat(((ApprovalOutcome.Pending) second).requestId())
                .isEqualTo(((ApprovalOutcome.Pending) first).requestId());
    }

    @Test
    void anApprovalCannotBeReusedForDifferentParameters() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();
        approvals.decide(requestId, ApprovalService.Decision.APPROVE, ActorRef.user("manager-1", "M"), "ok");

        ApprovalOutcome other = approvals.requireApproval(command(OTHER_PARAMETERS));

        assertThat(other)
                .as("an approval for one refund amount must not authorize a larger one")
                .isInstanceOf(ApprovalOutcome.Pending.class);
    }

    @Test
    void anApprovedRequestLetsTheActionProceed() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();

        approvals.decide(requestId, ApprovalService.Decision.APPROVE,
                ActorRef.user("manager-1", "Manager One"), "Verified with the customer");

        ApprovalOutcome resumed = transactions.execute(status -> {
            ApprovalOutcome outcome = approvals.requireApproval(command(PARAMETERS));
            outcome.consume();
            return outcome;
        });
        assertThat(resumed).isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(resumed.mayProceed()).isTrue();
        assertThat(((ApprovalOutcome.Approved) resumed).approvedBy()).isEqualTo("manager-1");
        assertThat(jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId).query(String.class).single())
                .as("and the signature is spent, so the identical resubmission has to ask again")
                .isEqualTo("CONSUMED");
    }

    @Test
    void theRequesterCannotApproveTheirOwnRequest() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();

        assertThatThrownBy(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE,
                ActorRef.user("operator-1", "Operator One"), "approving my own"))
                .isInstanceOf(ApprovalService.SelfApprovalException.class);
    }

    @Test
    void selfApprovalIsAlsoRejectedByTheDatabase() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE audit.approval_requests
                   SET status = 'APPROVED', decided_by = requested_by, decided_at = now()
                 WHERE id = :id
                """).param("id", requestId).update())
                .as("four eyes must not depend on a service that could be bypassed")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anExpiredRequestCannotBeApproved() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();

        clock.advance(ApprovalRequestCommand.DEFAULT_VALIDITY.plusHours(1));

        assertThatThrownBy(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE,
                ActorRef.user("manager-1", "M"), "late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aDeclinedRequestIsReportedAsDeclined() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();

        approvals.decide(requestId, ApprovalService.Decision.DECLINE,
                ActorRef.user("manager-1", "M"), "Not a service failure");

        ApprovalOutcome outcome = approvals.requireApproval(command(PARAMETERS));
        assertThat(outcome).isInstanceOf(ApprovalOutcome.Declined.class);
        assertThat(outcome.mayProceed()).isFalse();
    }

    @Test
    void aLaterPolicyChangeDoesNotAlterWhatWasAlreadyApproved() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();
        approvals.decide(requestId, ApprovalService.Decision.APPROVE, ActorRef.user("manager-1", "M"), "ok");

        // The tenant tightens the threshold afterwards.
        insertPolicy(2, "amount above 100,000 UZS");

        assertThat(jdbc.sql("SELECT policy_version, threshold_description FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query((rs, n) -> rs.getInt("policy_version") + "/" + rs.getString("threshold_description"))
                .single())
                .as("the snapshotted policy is what the approver actually saw")
                .isEqualTo("1/amount above 1,000,000 UZS");
    }

    @Test
    void overdueRequestsExpire() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        approvals.requireApproval(command(PARAMETERS));

        clock.advance(ApprovalRequestCommand.DEFAULT_VALIDITY.plusHours(1));

        assertThat(approvals.expireOverdue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM audit.approval_requests").query(String.class).single())
                .isEqualTo("EXPIRED");
    }

    @Test
    void everyDecisionIsAudited() {
        insertPolicy(1, "amount above 1,000,000 UZS");
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command(PARAMETERS))).requestId();

        approvals.decide(requestId, ApprovalService.Decision.APPROVE,
                ActorRef.user("manager-1", "Manager One"), "Verified");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.approve' AND actor_subject = 'manager-1'
                   AND approval_request_id = :id
                """).param("id", requestId).query(Long.class).single()).isEqualTo(1L);
    }

    private ApprovalRequestCommand command(String parametersHash) {
        return command(TENANT, parametersHash);
    }

    private ApprovalRequestCommand command(UUID tenantId, String parametersHash) {
        return new ApprovalRequestCommand(
                ACTION, parametersHash,
                ResourceScope.tenant(tenantId),
                ActorRef.user("operator-1", "Operator One"),
                "Customer reported a missing item",
                ApprovalRequestCommand.DEFAULT_VALIDITY);
    }

    private ApprovalRequestCommand command(ResourceScope scope) {
        return new ApprovalRequestCommand(
                ACTION, PARAMETERS, scope,
                ActorRef.user("operator-1", "Operator One"),
                "Customer reported a missing item", ApprovalRequestCommand.DEFAULT_VALIDITY);
    }

    private void insertPolicy(int version, String threshold) {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :actionCode, 'TENANT', CAST(:threshold AS jsonb),
                        'refund.approve', :validFrom, :version, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("actionCode", ACTION)
                .param("threshold", "{\"description\":\"%s\"}".formatted(threshold))
                .param("validFrom", clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .param("version", version)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", id).param("tenantId", TENANT).param("code", code)
                .param("slug", code.toLowerCase()).update();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
