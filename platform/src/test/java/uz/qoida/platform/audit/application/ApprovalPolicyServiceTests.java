package uz.qoida.platform.audit.application;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.ApprovalOutcome;
import uz.qoida.platform.audit.api.ApprovalRequestCommand;
import uz.qoida.platform.audit.application.ApprovalPolicyService.NewPolicyVersion;
import uz.qoida.platform.audit.application.ApprovalPolicyService.PolicyView;
import uz.qoida.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.qoida.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * ADR 0027: the path that makes maker-checker reachable.
 *
 * <p>The failure these tests pin is not a bug in a branch. It is that
 * {@code audit.approval_policies} had no writer at all — no migration, no
 * service, no endpoint, and a database role holding {@code SELECT} — so
 * {@code requireApproval} answered {@code NotRequired} on every deployment and
 * recorded that as a decision. Every existing approval test passed because each
 * seeded its own policy row by hand, which is exactly what production could not
 * do.
 */
class ApprovalPolicyServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b02");
    private static final String ACTION = "payments.remedy.record";
    private static final String PARAMETERS = "e".repeat(64);
    private static final Instant START = Instant.parse("2026-08-20T10:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private ApprovalPolicyService authoring;
    private JdbcApprovalService approvals;
    private MutableClock clock;

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
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(START);
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        authoring = new ApprovalPolicyService(jdbc, recorder, clock);
        approvals = new JdbcApprovalService(jdbc, recorder, clock, new SimpleMeterRegistry());

        insertTenant(TENANT, "tenant-policy-one");
        insertTenant(OTHER_TENANT, "tenant-policy-two");
    }

    @Test
    void anOperatorCanAuthorAPolicyAndItTurnsAnUnapprovedActionIntoAPendingOne() {
        assertThat(approvals.requireApproval(refund()))
                .as("the state of every deployment today: nothing governs a refund")
                .isInstanceOf(ApprovalOutcome.NotRequired.class);

        authoring.author(newVersion("A refund above 1,000,000 UZS"));

        assertThat(approvals.requireApproval(refund()))
                .as("authoring the policy is what makes the ADR 0027 control apply")
                .isInstanceOf(ApprovalOutcome.Pending.class);
    }

    @Test
    void theAuthoredThresholdIsWhatTheApproverIsShown() {
        authoring.author(newVersion("A refund above 1,000,000 UZS"));

        approvals.requireApproval(refund());

        assertThat(jdbc.sql("""
                SELECT threshold_description FROM audit.approval_requests
                """).query(String.class).single())
                .isEqualTo("A refund above 1,000,000 UZS");
    }

    @Test
    void authoringIsAudited() {
        PolicyView published = authoring.author(newVersion("A refund above 1,000,000 UZS"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.policy.authored'
                   AND audit_class = 'SECURITY'
                   AND actor_subject = 'owner-1'
                   AND target_id = :id
                   AND capability_used = 'approval.policy.manage'
                """).param("id", published.id()).query(Long.class).single())
                .as("changing when a second signature is required is itself a security event")
                .isEqualTo(1L);
    }

    @Test
    void endDatingTheOnlyPolicyPutsTheActionBackToUnapproved() {
        PolicyView published = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        assertThat(approvals.requireApproval(refund())).isInstanceOf(ApprovalOutcome.Pending.class);

        authoring.endDate(TENANT, published.id(), null, owner(), "Threshold withdrawn for the pilot");
        clock.advance(Duration.ofSeconds(1));

        assertThat(approvals.requireApproval(refundWith("f".repeat(64))))
                .as("end-dating is the configuration choice that turns the control back off")
                .isInstanceOf(ApprovalOutcome.NotRequired.class);
    }

    @Test
    void aNewVersionSupersedesTheOneItReplacesRatherThanRunningBesideIt() {
        authoring.author(newVersion("A refund above 1,000,000 UZS"));
        clock.advance(Duration.ofMinutes(5));
        PolicyView second = authoring.author(newVersion("A refund above 100,000 UZS"));

        List<PolicyView> open = authoring.list(TENANT, ACTION, false, 50);

        assertThat(second.version()).isEqualTo(2);
        assertThat(open)
                .as("two open versions would mean end-dating the newest silently revives the oldest")
                .hasSize(1);
        assertThat(open.getFirst().thresholdDescription()).isEqualTo("A refund above 100,000 UZS");
        assertThat(overlappingVersionPairs()).isZero();
    }

    /**
     * The path {@code hasSize(1)} above never reached.
     *
     * <p>Both calls there leave {@code validFrom} null, so both versions start at
     * the same instant and the close matched on {@code valid_from <= :from}.
     * {@code author} accepts a start in the future by design, and for a version
     * scheduled ahead of time that predicate was false: the superseding version
     * closed nothing, {@code UPDATE 0}, and from the scheduled date onward two
     * versions were live for the same action code and scope. Resolution takes the
     * highest version, so nobody noticed — until the newest was end-dated and the
     * lax threshold underneath it came back into force with no
     * {@code approval.policy.authored} anywhere to say a policy had taken effect.
     */
    @Test
    void aVersionScheduledForTheFutureIsSupersededTooRatherThanWaitingToComeBackToLife() {
        Instant scheduled = START.plus(Duration.ofDays(7));
        PolicyView lax = authoring.author(newVersionFrom("A refund above 9,000,000 UZS", scheduled));
        PolicyView strict = authoring.author(newVersion("A refund above 100,000 UZS"));

        assertThat(read(lax.id()).validUntil())
                .as("a scheduled version superseded before it starts closes at its own start")
                .isEqualTo(lax.validFrom());
        assertThat(read(lax.id()).isOpenAt(scheduled.plus(Duration.ofDays(1))))
                .as("an empty window governs no instant, including instants after it was due")
                .isFalse();

        clock.advance(Duration.ofDays(8));
        assertThat(authoring.list(TENANT, ACTION, false, 50))
                .as("an operator looking at the live policies must see one, a week later too")
                .hasSize(1);
        assertThat(overlappingVersionPairs()).isZero();

        // The resurrection itself: retiring the version that superseded it must
        // leave the action ungoverned, not hand it back to the lax threshold.
        authoring.endDate(TENANT, strict.id(), null, owner(), "Pilot over");
        clock.advance(Duration.ofSeconds(1));

        assertThat(approvals.requireApproval(refundWith("a".repeat(64))))
                .as("a superseded threshold never governs an action again")
                .isInstanceOf(ApprovalOutcome.NotRequired.class);
    }

    /**
     * The invariant, rather than one route into breaking it: for one tenant,
     * action code and scope, <strong>exactly</strong> one version is in force at
     * every instant from the first publication until the operator retires the
     * control — including instants that have not happened yet.
     *
     * <p>This used to assert {@code isLessThanOrEqualTo(1)}, and that is why it
     * could not see the cancel-a-scheduled-version defect. Two live answers and
     * none are both failures of a control, and only one of them is a duplicate.
     * An ungoverned instant is the one that lets a refund of any size through on
     * one signature, and "at most one" is satisfied by zero.
     */
    @Test
    void exactlyOneVersionIsInForceAtEveryInstantUntilTheControlIsRetired() {
        Instant retiredFrom = START.plus(Duration.ofDays(60));

        authoring.author(newVersionFrom("Scheduled a month out", START.plus(Duration.ofDays(30))));
        authoring.author(newVersionFrom("Scheduled a week out", START.plus(Duration.ofDays(7))));
        authoring.author(newVersion("Effective immediately"));
        clock.advance(Duration.ofDays(1));
        authoring.author(newVersionFrom("Scheduled a fortnight out", START.plus(Duration.ofDays(14))));
        clock.advance(Duration.ofDays(2));
        PolicyView latest = authoring.author(newVersion("Effective immediately, again"));
        authoring.endDate(TENANT, latest.id(), retiredFrom, owner(), "Reviewed quarterly");

        assertThat(overlappingVersionPairs())
                .as("two live answers at one instant is how a superseded threshold returns")
                .isZero();

        List<PolicyView> everything = authoring.list(TENANT, ACTION, true, 50);
        for (long day = 0; day <= 90; day++) {
            Instant instant = START.plus(Duration.ofDays(day));
            long inForce = everything.stream().filter(version -> version.isOpenAt(instant)).count();
            if (instant.isBefore(retiredFrom)) {
                assertThat(inForce)
                        .as("versions in force on day %d, where the control is meant to be armed", day)
                        .isEqualTo(1L);
            } else {
                assertThat(inForce)
                        .as("versions in force on day %d, after the operator retired the control "
                                + "on purpose — the one operation allowed to leave a gap", day)
                        .isZero();
            }
        }
    }

    /**
     * The second defect, end to end.
     *
     * <p>Author v1 with no start, so it runs {@code [t0, ∞)}. Author v2 for a
     * week out: {@code closeOpenVersion} clamps v1 to {@code [t0, t0+7d)} and v2
     * takes {@code [t0+7d, ∞)}, which is correct. Then the operator changes their
     * mind and cancels the scheduled v2 — and the cancel used to close v2 at its
     * own {@code valid_from}, leaving v1 clamped and nothing after it. From day
     * seven no policy resolved, {@code requireApproval} answered
     * {@code NotRequired}, and every refund of any size went through on one
     * signature. The operator asked to call off a <em>change</em> and got "stop
     * requiring approval, in a week", with the live listing showing one governing
     * version right up until it happened.
     */
    @Test
    void cancellingAScheduledVersionIsRefusedRatherThanDisarmingTheControlAWeekLater() {
        Instant scheduled = START.plus(Duration.ofDays(7));
        PolicyView inForce = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        PolicyView change = authoring.author(newVersionFrom("A refund above 100,000 UZS", scheduled));

        assertThat(read(inForce.id()).validUntil())
                .as("publishing the change clamps the version in force; that much is right")
                .isEqualTo(scheduled);

        Throwable refusal = catchThrowable(() ->
                authoring.endDate(TENANT, change.id(), null, owner(), "Finance changed their mind"));

        // Asserted first because it is the defect itself. Cancelling the scheduled
        // version used to close it at its own valid_from, and from that instant
        // onwards resolvePolicy found nothing at all.
        clock.advance(Duration.ofDays(8));
        assertThat(approvals.requireApproval(refundWith("b".repeat(64))))
                .as("a week later, on the day the control used to fall silent, it is still "
                        + "asking for a second signature")
                .isInstanceOf(ApprovalOutcome.Pending.class);

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(refusal.getMessage())
                .as("a refusal that does not name the remedy is a dead end for the operator")
                .contains("Publish the threshold you want as a new version");

        assertThat(read(change.id()).validUntil())
                .as("the refusal wrote nothing at all")
                .isNull();
        assertThat(read(inForce.id()).validUntil()).isEqualTo(scheduled);
    }

    /**
     * The refusal has to name a version that exists.
     *
     * <p>It used to assert, unconditionally, that cancelling "would leave the
     * version it superseded closed with nothing to follow it". On the path where
     * a predecessor really was clamped that is true and this pins it — including
     * the version number, so an operator who goes looking finds the row rather
     * than a claim.
     */
    @Test
    void theRefusalToCancelNamesTheVersionThatWouldBeStranded() {
        PolicyView inForce = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        PolicyView change = authoring.author(
                newVersionFrom("A refund above 100,000 UZS", START.plus(Duration.ofDays(7))));

        assertThatThrownBy(() ->
                authoring.endDate(TENANT, change.id(), null, owner(), "Finance changed their mind"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("version %d, which it superseded".formatted(inForce.version()));
    }

    /**
     * The mistake the refusal had no answer for.
     *
     * <p>An operator schedules the very first policy for this action and gets the
     * threshold wrong. Nothing precedes it, so cancelling it strands nobody:
     * before it was published the action was ungoverned, and after it is called
     * off the action is ungoverned again, which is exactly where the operator was
     * standing. Refusing anyway left one escape — publish a version that takes
     * effect <em>now</em> — so the only way out of arming a control later was
     * arming it immediately, and the refusal named a superseded version that
     * never existed.
     */
    @Test
    void aMistakenlyScheduledFirstEverVersionIsCancelledRatherThanRefused() {
        Instant scheduled = START.plus(Duration.ofDays(7));
        PolicyView mistake = authoring.author(
                newVersionFrom("A refund above 10 UZS", scheduled));

        PolicyView cancelled = authoring.endDate(
                TENANT, mistake.id(), null, owner(), "Wrong threshold; called off before it started");

        assertThat(cancelled.validUntil())
                .as("closed at its own start: an empty window, still readable as evidence")
                .isEqualTo(mistake.validFrom());
        assertThat(cancelled.isOpenAt(scheduled.plus(Duration.ofDays(1)))).isFalse();
        assertThat(overlappingVersionPairs()).isZero();

        clock.advance(Duration.ofDays(8));
        assertThat(approvals.requireApproval(refundWith("1".repeat(64))))
                .as("the timeline is back to what it was before the mistake, not armed at 10 UZS")
                .isInstanceOf(ApprovalOutcome.NotRequired.class);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.policy.cancelled'
                   AND audit_class = 'SECURITY'
                   AND target_id = :id
                   AND change_document->>'neverTookEffect' = 'true'
                """).param("id", mistake.id()).query(Long.class).single())
                .as("calling off a control change is itself a security event")
                .isEqualTo(1L);
    }

    /**
     * Cancelling a scheduled version is a cancellation, not a scheduling.
     */
    @Test
    void aScheduledVersionCannotBeGivenAWindowItWouldNeverBeInForceFor() {
        Instant scheduled = START.plus(Duration.ofDays(7));
        PolicyView mistake = authoring.author(newVersionFrom("A refund above 10 UZS", scheduled));

        assertThatThrownBy(() -> authoring.endDate(TENANT, mistake.id(),
                scheduled.plus(Duration.ofDays(3)), owner(), "Run it for three days"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(read(mistake.id()).validUntil())
                .as("the refusal wrote nothing")
                .isNull();
    }

    /**
     * A version voided before it ever applied is a change, and changes are
     * recorded.
     *
     * <p>{@code author} closes every version the new one supersedes, and for one
     * scheduled ahead of time that close is not a shortening: the row is clamped
     * to its own {@code valid_from}, an empty window, and it will never govern a
     * single instant. Only {@code approval.policy.authored} was recorded — about
     * the new row — so the tightening an operator scheduled for the first of the
     * month disappeared silently while still sitting in the listing under
     * {@code includeEnded}, carrying the threshold they wrote.
     */
    @Test
    void supersedingAVersionIsRecorded() {
        Instant scheduled = START.plus(Duration.ofDays(7));
        PolicyView inForce = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        PolicyView tightening = authoring.author(
                newVersionFrom("A refund above 100,000 UZS", scheduled));
        clock.advance(Duration.ofMinutes(5));
        PolicyView replacement = authoring.author(newVersion("A refund above 500,000 UZS"));

        assertThat(read(tightening.id()).validUntil())
                .as("the scheduled tightening will now never apply")
                .isEqualTo(tightening.validFrom());

        assertThat(supersessionFacts("approval.policy.voided", tightening.id(), replacement.id()))
                .as("a control change that will never take effect is the one an operator has "
                        + "the least chance of noticing on their own")
                .isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT change_document->>'neverTookEffect' FROM audit.audit_events
                 WHERE action_code = 'approval.policy.voided' AND target_id = :id
                """).param("id", tightening.id()).query(String.class).single())
                .isEqualTo("true");

        assertThat(supersessionFacts("approval.policy.superseded", inForce.id(), tightening.id()))
                .as("shortening a window that did govern something is recorded too, and "
                        + "distinguishably")
                .isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'approval.policy.voided' AND target_id = :id
                """).param("id", inForce.id()).query(Long.class).single())
                .as("a version that governed instants was not voided; it was cut short")
                .isZero();
    }

    private long supersessionFacts(String actionCode, UUID target, UUID supersededBy) {
        return jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = :actionCode
                   AND audit_class = 'SECURITY'
                   AND actor_subject = 'owner-1'
                   AND target_id = :target
                   AND capability_used = 'approval.policy.manage'
                   AND change_document->>'supersededByPolicyId' = :supersededBy
                """)
                .param("actionCode", actionCode)
                .param("target", target)
                .param("supersededBy", supersededBy.toString())
                .query(Long.class)
                .single();
    }

    /**
     * The remedy the refusal names, followed through: it leaves no ungoverned
     * instant anywhere on the timeline, which is the property the cancel could
     * not offer.
     */
    @Test
    void callingOffAScheduledChangeByPublishingTheThresholdYouWantLeavesNoUngovernedInstant() {
        authoring.author(newVersion("A refund above 1,000,000 UZS"));
        authoring.author(newVersionFrom("A refund above 100,000 UZS", START.plus(Duration.ofDays(7))));

        authoring.author(newVersion("A refund above 1,000,000 UZS"));

        List<PolicyView> everything = authoring.list(TENANT, ACTION, true, 50);
        for (long day = 0; day <= 30; day++) {
            Instant instant = START.plus(Duration.ofDays(day));
            assertThat(everything.stream().filter(version -> version.isOpenAt(instant)).count())
                    .as("versions in force on day %d", day)
                    .isEqualTo(1L);
        }
        assertThat(overlappingVersionPairs()).isZero();

        clock.advance(Duration.ofDays(8));
        assertThat(authoring.list(TENANT, ACTION, false, 50))
                .as("and the operator sees the threshold they meant to keep, a week later")
                .singleElement()
                .extracting(PolicyView::thresholdDescription)
                .isEqualTo("A refund above 1,000,000 UZS");
        assertThat(approvals.requireApproval(refundWith("c".repeat(64))))
                .isInstanceOf(ApprovalOutcome.Pending.class);
    }

    /**
     * The general rule the two refusals above add up to: {@code endDate} can only
     * reach the newest version, and only while it is in force. Every other
     * version already carries a {@code valid_until} written by the version that
     * superseded it, so the timeline can only be disarmed from its tail, and only
     * by an operator who said "stop requiring approval" about the threshold
     * actually in force.
     */
    @Test
    void onlyTheVersionInForceCanBeRetiredSoNoOtherOperationCanDisarmTheControl() {
        PolicyView first = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        clock.advance(Duration.ofMinutes(5));
        PolicyView second = authoring.author(newVersion("A refund above 500,000 UZS"));
        PolicyView scheduled = authoring.author(
                newVersionFrom("A refund above 100,000 UZS", START.plus(Duration.ofDays(7))));

        assertThat(catchThrowable(() ->
                authoring.endDate(TENANT, first.id(), null, owner(), "Retiring the oldest")))
                .as("a superseded version is already closed; reopening the question is not an edit")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already ends at");
        assertThat(catchThrowable(() ->
                authoring.endDate(TENANT, second.id(), null, owner(), "Retiring the one I can see")))
                .as("the version in force was clamped by the scheduled one, so it is closed too")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already ends at");
        assertThat(catchThrowable(() ->
                authoring.endDate(TENANT, scheduled.id(), null, owner(), "Cancelling the change")))
                .as("and the only open version has not started, so cancelling it is refused")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not take effect until");

        clock.advance(Duration.ofDays(8));
        assertThat(approvals.requireApproval(refundWith("d".repeat(64))))
                .as("three attempts to retire something, and the control is still armed")
                .isInstanceOf(ApprovalOutcome.Pending.class);

        // Once the scheduled version is actually in force, retiring it is the
        // operator saying stop requiring approval, and that is allowed.
        authoring.endDate(TENANT, scheduled.id(), null, owner(), "Pilot over");
        clock.advance(Duration.ofSeconds(1));
        assertThat(approvals.requireApproval(refundWith("e".repeat(64))))
                .isInstanceOf(ApprovalOutcome.NotRequired.class);
    }

    /**
     * Counts pairs of versions of the same policy whose half-open windows
     * intersect. A version closed at its own {@code valid_from} has an empty
     * window and is excluded before the intersection is tested — the ordinary
     * two-interval test assumes both are non-empty and would otherwise report a
     * voided version as overlapping everything after it. That empty window is
     * what makes voiding a scheduled version legal under
     * {@code ck_approval_policy_validity}, which refuses {@code valid_until <
     * valid_from} outright.
     */
    private long overlappingVersionPairs() {
        return jdbc.sql("""
                SELECT count(*)
                  FROM audit.approval_policies a
                  JOIN audit.approval_policies b
                    ON a.action_code = b.action_code
                   AND a.scope_type = b.scope_type
                   AND a.tenant_id IS NOT DISTINCT FROM b.tenant_id
                   AND a.version < b.version
                 WHERE (a.valid_until IS NULL OR a.valid_until > a.valid_from)
                   AND (b.valid_until IS NULL OR b.valid_until > b.valid_from)
                   AND (a.valid_until IS NULL OR a.valid_until > b.valid_from)
                   AND (b.valid_until IS NULL OR b.valid_until > a.valid_from)
                """).query(Long.class).single();
    }

    private PolicyView read(UUID policyId) {
        return authoring.list(TENANT, null, true, 50).stream()
                .filter(version -> version.id().equals(policyId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anEndedVersionIsStillReadableAsEvidence() {
        PolicyView published = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        authoring.endDate(TENANT, published.id(), null, owner(), "Superseded by a manual process");

        assertThat(authoring.list(TENANT, ACTION, false, 50)).isEmpty();
        assertThat(authoring.list(TENANT, ACTION, true, 50))
                .as("what the threshold used to be is the answer to why a past refund was stopped")
                .hasSize(1);
    }

    @Test
    void aPolicyIsNeverReopened() {
        PolicyView published = authoring.author(newVersion("A refund above 1,000,000 UZS"));
        authoring.endDate(TENANT, published.id(), null, owner(), "Withdrawn");

        assertThatThrownBy(() ->
                authoring.endDate(TENANT, published.id(), null, owner(), "Withdrawn again"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    void aPolicyBelongingToAnotherTenantIsNotFound() {
        PolicyView published = authoring.author(newVersion("A refund above 1,000,000 UZS"));

        assertThatThrownBy(() ->
                authoring.endDate(OTHER_TENANT, published.id(), null, owner(), "Not mine"))
                .as("an identifier alone never authorises a write")
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(authoring.list(OTHER_TENANT, null, true, 50)).isEmpty();
    }

    @Test
    void aPolicyCannotNameACapabilityNobodyCanHold() {
        assertThatThrownBy(() -> authoring.author(new NewPolicyVersion(
                ResourceScope.tenant(TENANT), ACTION, "Above a million",
                "refund.teleport", null, owner(), "Typo")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("refund.teleport");
    }

    @Test
    void aPolicyCannotBeBackdated() {
        assertThatThrownBy(() -> authoring.author(new NewPolicyVersion(
                ResourceScope.tenant(TENANT), ACTION, "Above a million",
                "refund.approve", START.minus(Duration.ofDays(30)), owner(),
                "Making it look as though we always required this")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("before it was authored");
    }

    @Test
    void aTenantCannotAuthorAPlatformPolicy() {
        assertThatThrownBy(() -> authoring.author(new NewPolicyVersion(
                ResourceScope.platform(), ACTION, "Above a million",
                "refund.approve", null, owner(), "Overreach")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("TENANT, BRAND, or LOCATION");
    }

    /**
     * The half of this that lives in the database. V0007 granted the application
     * role {@code SELECT} on the policy table and nothing else, so no code could
     * have authored a policy however hard it tried; V0059 grants insert and the
     * one column-level update the timeline needs, and nothing more.
     */
    @Test
    void theApplicationRoleMayPublishAndRetireAPolicyButNeverRewriteOne() {
        PolicyView published = authoring.author(newVersion("A refund above 1,000,000 UZS"));

        // Cluster-wide, and the cluster now outlives this class. Without the drop
        // an assertion failure that skips the finally leaves the role behind and
        // the next run fails on "role already exists" instead of on the thing it
        // is about.
        jdbc.sql("DROP ROLE IF EXISTS policy_app_probe").update();
        jdbc.sql("CREATE ROLE policy_app_probe LOGIN PASSWORD 'probe'").update();
        jdbc.sql("GRANT qoida_application TO policy_app_probe").update();
        try {
            JdbcClient asApplication = JdbcClient.create(
                    db.dataSourceAs("policy_app_probe", "probe"));

            assertThat(asApplication.sql("""
                    INSERT INTO audit.approval_policies
                        (id, tenant_id, action_code, scope_type, threshold_json,
                         required_approver_capability, valid_from, version, approved_by)
                    VALUES (:id, :tenantId, 'loyalty.balance.adjust', 'TENANT',
                            jsonb_build_object('description', 'Above 50,000 points'),
                            'loyalty.adjust', :validFrom, 1, 'owner-1')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("validFrom", clock.instant().atOffset(ZoneOffset.UTC))
                    .update())
                    .as("without INSERT the control cannot be configured at all")
                    .isEqualTo(1);

            assertThat(asApplication.sql("""
                    UPDATE audit.approval_policies SET valid_until = :end WHERE id = :id
                    """)
                    .param("id", published.id())
                    .param("end", clock.instant().plus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                    .update())
                    .as("closing a version's window is the only way to retire one")
                    .isEqualTo(1);

            assertThatThrownBy(() -> asApplication.sql("""
                    UPDATE audit.approval_policies
                       SET threshold_json = jsonb_build_object('description', 'Above 999,999,999 UZS')
                     WHERE id = :id
                    """).param("id", published.id()).update())
                    .as("""
                            A threshold rewritten in place makes the policy_version snapshotted
                            onto every past request describe words that no longer exist.""")
                    .isInstanceOfAny(
                            org.springframework.dao.PermissionDeniedDataAccessException.class,
                            org.springframework.dao.InvalidDataAccessResourceUsageException.class,
                            org.springframework.jdbc.UncategorizedSQLException.class);

            assertThatThrownBy(() -> asApplication
                    .sql("DELETE FROM audit.approval_policies").update())
                    .as("a deleted policy takes the evidence for every decision it drove with it")
                    .isInstanceOfAny(
                            org.springframework.dao.PermissionDeniedDataAccessException.class,
                            org.springframework.dao.InvalidDataAccessResourceUsageException.class,
                            org.springframework.jdbc.UncategorizedSQLException.class);
        } finally {
            jdbc.sql("REVOKE qoida_application FROM policy_app_probe").update();
            jdbc.sql("DROP ROLE policy_app_probe").update();
        }
    }

    private NewPolicyVersion newVersion(String threshold) {
        return newVersionFrom(threshold, null);
    }

    private NewPolicyVersion newVersionFrom(String threshold, Instant validFrom) {
        return new NewPolicyVersion(
                ResourceScope.tenant(TENANT), ACTION, threshold, "refund.approve", validFrom,
                owner(), "Finance asked for a second signature on large refunds");
    }

    private static ActorRef owner() {
        return ActorRef.user("owner-1", "Owner One");
    }

    private ApprovalRequestCommand refund() {
        return refundWith(PARAMETERS);
    }

    private ApprovalRequestCommand refundWith(String parametersHash) {
        return new ApprovalRequestCommand(
                ACTION, parametersHash, ResourceScope.tenant(TENANT),
                ActorRef.user("agent-1", "Agent One"),
                "Customer reported a missing item",
                ApprovalRequestCommand.DEFAULT_VALIDITY);
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
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
