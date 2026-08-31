package uz.horecaos.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
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
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0027: the way to say yes.
 *
 * <p>Everything below was unreachable until this service existed. No production
 * path called {@code ApprovalService.decide}, so from the moment an operator
 * could author a policy — which they could as of the approval-policy surface —
 * a governed refund, courier payout, settlement close, loyalty adjustment or
 * onboarding step went {@code PENDING} and stayed there for its whole validity,
 * with the only operator exit being to end-date the policy that required it. The
 * assertions here are the four the control is worth nothing without: the
 * approver is judged against the policy's own
 * {@code required_approver_capability}, the approver is never the requester, a
 * decision cannot cross a tenant boundary, and two approvers pressing at once
 * produce one outcome.
 */
class ApprovalDecisionServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d02");
    private static final String ACTION = "payments.remedy.record";
    private static final String PARAMETERS = "a".repeat(64);

    private static final String MAKER = "operator-1";
    private static final String CHECKER = "manager-1";
    private static final String BYSTANDER = "cashier-1";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private JdbcApprovalService approvals;
    private ApprovalDecisionService decisions;
    private StubAuthorization authorization;
    private MutableClock clock;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        // A real transaction manager over the same DataSource, because an approval
        // is only single-use if spending it commits with the action and rolls back
        // with it. A suite that called requireApproval outside a transaction cannot
        // tell those two apart, which is part of how the defect survived one.
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        approvals = new JdbcApprovalService(
                jdbc, new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                clock, new SimpleMeterRegistry());
        authorization = new StubAuthorization();
        decisions = new ApprovalDecisionService(
                jdbc,
                approvals,
                authorization,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                clock);

        insertTenant(TENANT, "tenant-decide-one");
        insertTenant(OTHER_TENANT, "tenant-decide-two");
        authorization.grant(CHECKER, Capability.REFUND_APPROVE, ResourceScope.tenant(TENANT));
        authorization.grant(MAKER, Capability.REFUND_APPROVE, ResourceScope.tenant(TENANT));
    }

    @Test
    void approvingLetsThePreviouslyPendingActionProceed() {
        UUID requestId = raise();
        assertThat(approvals.requireApproval(command()))
                .as("the maker is blocked until somebody signs")
                .isInstanceOf(ApprovalOutcome.Pending.class);

        var decided = decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(CHECKER, null),
                "Spoke to the customer; the item was missing");

        assertThat(decided.status()).isEqualTo("APPROVED");
        assertThat(decided.decidedBy()).isEqualTo(CHECKER);

        ApprovalOutcome resumed = execute();
        assertThat(resumed)
                .as("the maker's identical resubmission is now the same code path as one that never asked")
                .isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(resumed.mayProceed()).isTrue();
    }

    @Test
    void decliningBlocksTheActionAndCarriesTheReasonBack() {
        UUID requestId = raise();

        decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.DECLINE,
                ActorRef.user(CHECKER, null),
                "The delivery photo shows the order complete");

        ApprovalOutcome resumed = approvals.requireApproval(command());
        assertThat(resumed).isInstanceOf(ApprovalOutcome.Declined.class);
        assertThat(resumed.mayProceed()).isFalse();
        assertThat(((ApprovalOutcome.Declined) resumed).reason())
                .as("the maker has to be told why, or the decline is indistinguishable from a fault")
                .isEqualTo("The delivery photo shows the order complete");
    }

    /**
     * The single assertion this whole surface is worth having. Four eyes with one
     * pair of eyes is not a control, and the requester here deliberately holds
     * the policy's approver capability — so the refusal can only come from the
     * four-eyes rule and not from a permission they happened to lack.
     */
    @Test
    void theRequesterCannotDecideTheirOwnRequest() {
        UUID requestId = raise();
        assertThat(authorization.has(MAKER, Capability.REFUND_APPROVE, ResourceScope.tenant(TENANT)))
                .as("the point of this test is that holding the capability is not enough")
                .isTrue();

        Throwable refusal = catchThrowable(() -> decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(MAKER, null),
                "I am confident about my own refund"));

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY);
        assertThat(status(requestId))
                .as("a refused self-approval must leave the request exactly as it was")
                .isEqualTo("PENDING");
    }

    @Test
    void aSelfApprovalRefusalIsRecordedEvenThoughItThrows() {
        UUID requestId = raise();

        Throwable refusal = catchThrowable(() -> decisions.decide(
                TENANT, requestId, ApprovalService.Decision.APPROVE, ActorRef.user(MAKER, null), "trying anyway"));
        assertThat(refusal).isInstanceOf(ApiException.class);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.decision.refused'
                   AND actor_subject = :subject AND outcome = 'REJECTED'
                """).param("subject", MAKER).query(Long.class).single())
                .as("evidence written inside the failing call would roll back with it, leaving "
                        + "an audit trail of every decision that succeeded and no attempt that did not")
                .isEqualTo(1L);
    }

    /**
     * {@code required_approver_capability} has been on every policy row since
     * V0007 and no code had ever read it. This is the test that says it is read.
     */
    @Test
    void anApproverWithoutThePolicysCapabilityIsRefused() {
        UUID requestId = raise();

        Throwable refusal = catchThrowable(() -> decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(BYSTANDER, null),
                "looks fine to me"));

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY);
        assertThat(refusal.getMessage())
                .as("the answer names what the policy demanded, so an operator knows what to grant")
                .contains(Capability.REFUND_APPROVE.code());
        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    /**
     * The capability is not a column on the request; the snapshot is
     * {@code policy_id}, which names one immutable version row. Tightening the
     * policy afterwards must not retroactively disqualify the approver the
     * request was raised against.
     */
    @Test
    void theApproverIsJudgedAgainstThePolicyAsItStoodWhenTheRequestWasMade() {
        insertPolicy(1, "above 1,000,000 UZS", Capability.REFUND_APPROVE);
        UUID requestId = ((ApprovalOutcome.Pending) approvals.requireApproval(command())).requestId();

        // The tenant tightens the control: from tomorrow only the owner signs.
        endDateOpenPolicies();
        insertPolicy(2, "above 100,000 UZS", Capability.COURIER_PAYOUT_AUTHORISE);

        var decided = decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(CHECKER, null),
                "Verified against the order");

        assertThat(decided.status())
                .as("the approver saw version 1 and holds what version 1 demanded; a policy "
                        + "published after the fact cannot retroactively invalidate that")
                .isEqualTo("APPROVED");
    }

    @Test
    void aDecisionOnALapsedRequestIsRefused() {
        UUID requestId = raise();

        clock.advance(ApprovalRequestCommand.DEFAULT_VALIDITY.plusHours(1));

        Throwable refusal = catchThrowable(() -> decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(CHECKER, null),
                "getting to it late"));

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(status(requestId))
                .as("a lapsed request is never signed; the maker raises it again under whatever "
                        + "policy governs now")
                .isEqualTo("PENDING");
    }

    @Test
    void aDecisionFromAnotherTenantIsRefused() {
        UUID requestId = raise();
        authorization.grant(CHECKER, Capability.REFUND_APPROVE, ResourceScope.tenant(OTHER_TENANT));

        Throwable refusal = catchThrowable(() -> decisions.decide(
                OTHER_TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(CHECKER, null),
                "signing from next door"));

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode())
                .as("a request identifier from another tenant is not-found, never decidable; "
                        + "the underlying decide looks a request up by identifier alone")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    @Test
    void twoConcurrentDecisionsProduceOneOutcome() throws InterruptedException {
        UUID requestId = raise();
        authorization.grant("manager-2", Capability.REFUND_APPROVE, ResourceScope.tenant(TENANT));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> approveFailure = new AtomicReference<>();
        AtomicReference<Throwable> declineFailure = new AtomicReference<>();

        Thread approve = new Thread(() -> {
            ready.countDown();
            await(go);
            approveFailure.set(catchThrowable(() -> decisions.decide(
                    TENANT, requestId, ApprovalService.Decision.APPROVE, ActorRef.user(CHECKER, null), "approving")));
        });
        Thread decline = new Thread(() -> {
            ready.countDown();
            await(go);
            declineFailure.set(catchThrowable(() -> decisions.decide(
                    TENANT,
                    requestId,
                    ApprovalService.Decision.DECLINE,
                    ActorRef.user("manager-2", null),
                    "declining")));
        });

        approve.start();
        decline.start();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        approve.join(20_000);
        decline.join(20_000);

        List<Throwable> failures = new ArrayList<>();
        if (approveFailure.get() != null) {
            failures.add(approveFailure.get());
        }
        if (declineFailure.get() != null) {
            failures.add(declineFailure.get());
        }

        assertThat(failures)
                .as("the optimistic version guard has to leave exactly one winner, or a request "
                        + "can be both approved and declined depending on which write lands last")
                .hasSize(1);
        assertThat(((ApiException) failures.getFirst()).errorCode())
                .as("the loser is told the request moved under them, whether they lost the "
                        + "version guard or read the settled row before they tried")
                .isIn(ErrorCode.RESOURCE_CONFLICT, ErrorCode.UNPROCESSABLE_STATE);
        assertThat(status(requestId)).isIn("APPROVED", "DECLINED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code IN ('approval.approve', 'approval.decline')
                """).query(Long.class).single())
                .as("one outcome means one decision fact, not two")
                .isEqualTo(1L);
    }

    @Test
    void theScheduledSweepMovesAnOverdueRequestToExpired() {
        raise();
        clock.advance(ApprovalRequestCommand.DEFAULT_VALIDITY.plusMinutes(1));

        new ApprovalExpirySweeper(approvals).sweep();

        assertThat(jdbc.sql("SELECT status FROM audit.approval_requests")
                        .query(String.class)
                        .single())
                .as("expireOverdue existed from the day ADR 0027 shipped and nothing called it")
                .isEqualTo("EXPIRED");
    }

    @Test
    void thePendingQueueShowsThisTenantsRequestsAndNobodyElses() {
        UUID mine = raise();
        UUID theirs = raiseIn(OTHER_TENANT);

        List<ApprovalDecisionService.PendingApproval> waiting = decisions.pending(TENANT, null, 50, CHECKER);

        assertThat(waiting)
                .extracting(ApprovalDecisionService.PendingApproval::id)
                .containsExactly(mine)
                .doesNotContain(theirs);
        assertThat(waiting.getFirst().requiredApproverCapability()).isEqualTo(Capability.REFUND_APPROVE.code());
        assertThat(waiting.getFirst().mayDecide())
                .as("the checker holds what the policy demanded and did not raise it")
                .isTrue();
    }

    @Test
    void theQueueNeverOffersSomebodyTheirOwnRequestToSign() {
        raise();

        assertThat(decisions.pending(TENANT, null, 50, MAKER))
                .as("the maker still sees their own request, so they can tell it is waiting")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.mayDecide())
                        .as("but never as one they could sign, however much they hold")
                        .isFalse());
    }

    /**
     * ADR 0029. The maker's reason is a sentence a person typed about a named
     * customer and nothing classifies it, so it does not travel to a console that
     * only needs to know what is waiting.
     */
    @Test
    void theQueueDoesNotCarryTheMakersFreeTextReason() {
        raise();

        assertThat(decisions.pending(TENANT, null, 50, CHECKER).getFirst().toString())
                .doesNotContain("Customer says the kebab never arrived");
    }

    @Test
    void aLapsedRequestLeavesTheQueueBeforeTheSweeperReachesIt() {
        raise();
        clock.advance(ApprovalRequestCommand.DEFAULT_VALIDITY.plusMinutes(1));

        assertThat(decisions.pending(TENANT, null, 50, CHECKER))
                .as("a worklist that lists items nobody can act on teaches its readers to skim")
                .isEmpty();
        assertThat(status(only()))
                .as("and it says so without having written anything")
                .isEqualTo("PENDING");
    }

    @Test
    void aDecisionWithoutAReasonIsRefused() {
        UUID requestId = raise();

        assertThatThrownBy(() -> decisions.decide(
                        TENANT, requestId, ApprovalService.Decision.APPROVE, ActorRef.user(CHECKER, null), "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reason");
    }

    // --- one signature, one execution -------------------------------------

    /**
     * The defect: an approved request answered {@code Approved} for its whole
     * validity and nothing ever marked it spent.
     *
     * <p>The parameters hash is over the business parameters and deliberately
     * excludes the idempotency key, so a maker resubmitting with a fresh key
     * produced the identical hash and the identical approval answered again. A
     * 500 000-point goodwill credit signed once was as many 500 000-point credits
     * as the maker cared to submit, for twenty-four hours.
     */
    @Test
    void anApprovalIsSpentByTheActionItAuthorisedAndTheNextIdenticalSubmissionMustAskAgain() {
        UUID requestId = raise();
        approve(requestId);

        ApprovalOutcome first = execute();

        assertThat(first).isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(status(requestId))
                .as("the missing state: the request the action ran under is now spent")
                .isEqualTo("CONSUMED");

        // Nearly a day later, which is where the reproduction ran it: still no.
        clock.advance(ApprovalRequestCommand.DEFAULT_VALIDITY.minusMinutes(1));
        ApprovalOutcome second = execute();

        assertThat(second)
                .as("the identical resubmission raises a new request rather than riding the old "
                        + "signature; before this it answered APPROVED every time until expiry")
                .isInstanceOf(ApprovalOutcome.Pending.class);
        assertThat(((ApprovalOutcome.Pending) second).requestId()).isNotEqualTo(requestId);
        assertThat(status(requestId)).isEqualTo("CONSUMED");
    }

    /**
     * The half that makes spending it safe. An approval consumed by the check
     * rather than by the action would be destroyed by any failure after the
     * check, which is a worse control than the one being fixed: the operator
     * loses the signature for a refund that never happened.
     */
    @Test
    void anActionThatRolledBackLeavesItsApprovalUsable() {
        UUID requestId = raise();
        approve(requestId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    ApprovalOutcome outcome = approvals.requireApproval(command());
                    outcome.consume();
                    throw new IllegalStateException("the refund failed after taking its approval");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(status(requestId))
                .as("the spend is an ordinary write in the action's transaction, so it went back "
                        + "with everything else")
                .isEqualTo("APPROVED");
        assertThat(execute())
                .as("and the maker may retry on the signature they already have")
                .isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(status(requestId)).isEqualTo("CONSUMED");
    }

    /**
     * Two executions racing under one signature. Both read the approved row
     * before either writes, which is exactly the window a status column alone
     * does not close; the compare-and-set on {@code status = 'APPROVED'} does.
     */
    @Test
    void twoConcurrentExecutionsUnderOneApprovalProduceOneEffect() throws InterruptedException {
        UUID requestId = raise();
        approve(requestId);

        CountDownLatch read = new CountDownLatch(2);
        CountDownLatch spend = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Runnable execution = () -> transactions.executeWithoutResult(status -> {
            ApprovalOutcome outcome = approvals.requireApproval(command());
            assertThat(outcome).isInstanceOf(ApprovalOutcome.Approved.class);
            read.countDown();
            await(spend);
            outcome.consume();
        });

        Thread one = new Thread(() -> firstFailure.set(catchThrowable(execution::run)));
        Thread two = new Thread(() -> secondFailure.set(catchThrowable(execution::run)));
        one.start();
        two.start();
        assertThat(read.await(20, TimeUnit.SECONDS))
                .as("both have to hold an Approved outcome before either spends it")
                .isTrue();
        spend.countDown();
        one.join(30_000);
        two.join(30_000);

        List<Throwable> failures = new ArrayList<>();
        if (firstFailure.get() != null) {
            failures.add(firstFailure.get());
        }
        if (secondFailure.get() != null) {
            failures.add(secondFailure.get());
        }

        assertThat(failures)
                .as("one signature authorises one execution, whichever thread gets the row first")
                .hasSize(1);
        assertThat(((ApiException) failures.getFirst()).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(status(requestId)).isEqualTo("CONSUMED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events WHERE action_code = 'approval.consumed'
                """).query(Long.class).single())
                .as("one execution means one consumption fact; the loser's rolled back with it")
                .isEqualTo(1L);
    }

    /**
     * The obligation is not advisory. A caller that acts on an approval and never
     * spends it has reproduced the defect, so its transaction does not commit.
     */
    @Test
    void aTransactionThatCommitsStillHoldingAnApprovalIsRefused() {
        UUID requestId = raise();
        approve(requestId);

        assertThatThrownBy(() -> transactions.execute(status -> approvals.requireApproval(command())))
                .isInstanceOf(ApprovalService.ApprovalNotConsumedException.class)
                .hasMessageContaining(requestId.toString());

        assertThat(status(requestId))
                .as("and nothing that transaction wrote survives, approval included")
                .isEqualTo("APPROVED");
    }

    /**
     * Outside a transaction there is nothing to bind the spend to, so the answer
     * is a refusal rather than an approval nobody can account for.
     */
    @Test
    void anApprovedOutcomeIsNotHandedOutWhereTheSpendCannotBeBoundToTheAction() {
        UUID requestId = raise();
        approve(requestId);

        assertThatThrownBy(() -> approvals.requireApproval(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
        assertThat(status(requestId)).isEqualTo("APPROVED");
    }

    /** The spend names who executed and when, which is what lines an action up with its signature. */
    @Test
    void spendingAnApprovalRecordsWhoExercisedItAndIsAudited() {
        UUID requestId = raise();
        approve(requestId);

        execute();

        assertThat(jdbc.sql("""
                SELECT consumed_by FROM audit.approval_requests WHERE id = :id
                """).param("id", requestId).query(String.class).single())
                .isEqualTo(MAKER);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.consumed'
                   AND audit_class = 'SECURITY'
                   AND actor_subject = :maker
                   AND approval_request_id = :id
                """)
                        .param("maker", MAKER)
                        .param("id", requestId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT reason FROM audit.audit_events WHERE action_code = 'approval.consumed'
                """).query(String.class).single())
                .as("ADR 0029: the maker's sentence about a customer is already on the request; "
                        + "this fact says the signature was spent and nothing more")
                .doesNotContain("kebab");
    }

    // --- fixtures ---------------------------------------------------------

    /** One execution of the approved action: check, spend, commit. */
    private ApprovalOutcome execute() {
        return transactions.execute(status -> {
            ApprovalOutcome outcome = approvals.requireApproval(command());
            outcome.consume();
            return outcome;
        });
    }

    private void approve(UUID requestId) {
        decisions.decide(
                TENANT,
                requestId,
                ApprovalService.Decision.APPROVE,
                ActorRef.user(CHECKER, null),
                "Spoke to the customer; the item was missing");
    }

    private UUID raise() {
        insertPolicy(1, "above 1,000,000 UZS", Capability.REFUND_APPROVE);
        return ((ApprovalOutcome.Pending) approvals.requireApproval(command())).requestId();
    }

    private UUID raiseIn(UUID tenantId) {
        insertPolicy(tenantId, 1, "above 1,000,000 UZS", Capability.REFUND_APPROVE);
        return ((ApprovalOutcome.Pending) approvals.requireApproval(new ApprovalRequestCommand(
                        ACTION,
                        PARAMETERS,
                        ResourceScope.tenant(tenantId),
                        ActorRef.user(MAKER, null),
                        "Customer says the kebab never arrived",
                        ApprovalRequestCommand.DEFAULT_VALIDITY)))
                .requestId();
    }

    private ApprovalRequestCommand command() {
        return new ApprovalRequestCommand(
                ACTION,
                PARAMETERS,
                ResourceScope.tenant(TENANT),
                ActorRef.user(MAKER, null),
                "Customer says the kebab never arrived",
                ApprovalRequestCommand.DEFAULT_VALIDITY);
    }

    private String status(UUID requestId) {
        return jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
    }

    private UUID only() {
        return jdbc.sql("SELECT id FROM audit.approval_requests")
                .query(UUID.class)
                .single();
    }

    private void insertPolicy(int version, String threshold, Capability approver) {
        insertPolicy(TENANT, version, threshold, approver);
    }

    private void insertPolicy(UUID tenantId, int version, String threshold, Capability approver) {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :actionCode, 'TENANT', CAST(:threshold AS jsonb),
                        :approver, :validFrom, :version, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("actionCode", ACTION)
                .param("threshold", "{\"description\":\"%s\"}".formatted(threshold))
                .param("approver", approver.code())
                .param("validFrom", clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .param("version", version)
                .update();
    }

    private void endDateOpenPolicies() {
        jdbc.sql("""
                UPDATE audit.approval_policies SET valid_until = :now
                 WHERE tenant_id = :tenantId AND valid_until IS NULL
                """)
                .param("tenantId", TENANT)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Grants held in memory, keyed by subject and capability.
     *
     * <p>The real {@code JdbcAuthorizationService} answers the same question from
     * {@code iam.grants}; what this test is about is whether the policy's column
     * is consulted at all, so the grant store is deliberately trivial.
     */
    private static final class StubAuthorization implements AuthorizationService {

        private final Map<String, Map<Capability, List<ResourceScope>>> grants = new HashMap<>();

        void grant(String subject, Capability capability, ResourceScope scope) {
            grants.computeIfAbsent(subject, ignored -> new HashMap<>())
                    .computeIfAbsent(capability, ignored -> new ArrayList<>())
                    .add(scope);
        }

        @Override
        public boolean has(String subject, Capability capability, ResourceScope scope) {
            return grants.getOrDefault(subject, Map.of()).getOrDefault(capability, List.of()).stream()
                    .anyMatch(held -> held.covers(scope));
        }

        @Override
        public void require(String subject, Capability capability, ResourceScope scope) {
            if (!has(subject, capability, scope)) {
                throw new AccessDeniedException(capability, scope);
            }
        }

        @Override
        public CapabilityView viewFor(String subject, UUID tenantId) {
            Set<Capability> held = EnumSet.noneOf(Capability.class);
            held.addAll(grants.getOrDefault(subject, Map.of()).keySet());
            return new CapabilityView(subject, String.valueOf(tenantId), held, List.of(), 0L);
        }
    }

    /** A fixture's clock is the test's clock. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
