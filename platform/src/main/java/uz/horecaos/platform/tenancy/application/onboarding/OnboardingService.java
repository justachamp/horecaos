package uz.horecaos.platform.tenancy.application.onboarding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.OnboardingHealth;
import uz.horecaos.platform.tenancy.api.OnboardingHealthQuery;
import uz.horecaos.platform.tenancy.api.TenantActivated;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.api.TenantOnboardingFailed;
import uz.horecaos.platform.tenancy.api.TenantOnboardingStarted;
import uz.horecaos.platform.tenancy.api.TenantOnboardingStepCompleted;
import uz.horecaos.platform.tenancy.api.TenantReady;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;

/**
 * The resumable onboarding workflow (ADR 0008).
 *
 * <p>Steps are claimed with a lease and a token. The token is what makes a
 * process restart safe: a worker that died mid-step cannot complete it later,
 * because its token no longer matches.
 *
 * <p>Activation is deliberately not automatic. A platform administrator approves
 * it through the ADR 0027 model, so nothing goes live without someone seeing the
 * readiness evidence.
 */
@Service
public class OnboardingService implements OnboardingHealthQuery {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    /** Bounds a claim so a dead worker cannot hold a step forever. */
    static final Duration STEP_LEASE = Duration.ofMinutes(5);

    static final int MAXIMUM_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Map<OnboardingStep, OnboardingStepHandler> handlers;
    private final AuditRecorder audit;
    private final ApprovalService approvals;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // A template rather than @Transactional on the pieces of runNextStep, for the
    // reason the scheduler used to get wrong: a bean calling its own annotated
    // method skips the proxy, so the annotation would be decoration and the
    // claim would commit or not commit by accident.
    public OnboardingService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            List<OnboardingStepHandler> handlers,
            AuditRecorder audit,
            ApprovalService approvals,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.handlers = handlers.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(OnboardingStepHandler::step, h -> h));
        this.audit = audit;
        this.approvals = approvals;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Creates a run with every step materialised, blocked ones included. */
    @Transactional
    public UUID startRun(
            UUID tenantId, UUID templateId, int templateVersion, Map<String, Object> input, ActorRef startedBy) {

        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();

        jdbc.sql("""
                INSERT INTO tenant.onboarding_runs
                    (id, tenant_id, template_id, template_version, status, current_phase,
                     started_by, started_at)
                VALUES (:id, :tenantId, :templateId, :templateVersion, 'PROVISIONING', 'PROVISIONING',
                        :startedBy, :now)
                """)
                .param("id", runId)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .param("templateVersion", templateVersion)
                .param("startedBy", startedBy.subject())
                .param("now", at(now))
                .update();

        // `updated_at` is written from the injected clock rather than left to the
        // column's `now()` default. The stalled-run gauge measures
        // `clock.instant() - updated_at`, and a row whose age is set by the
        // database while the numerator comes from the application is an age
        // measured across two clocks — harmless on one host, meaningless in a
        // test, and exactly the kind of thing that makes an alert unarguable
        // only until someone argues with it.
        for (OnboardingStep step : OnboardingStep.values()) {
            // A blocked step is created in that state rather than omitted. A
            // template that silently skips a check reads exactly like one that
            // passed it, which is the confusion this avoids.
            String status = step.isBlocked() ? "BLOCKED" : "PENDING";

            jdbc.sql("""
                    INSERT INTO tenant.onboarding_steps
                        (id, tenant_id, run_id, step_key, phase, sequence_number,
                         status, required, input_snapshot, last_error_code, last_error,
                         available_at, updated_at)
                    VALUES (:id, :tenantId, :runId, :stepKey, :phase, :sequence,
                            :status, :required, CAST(:input AS jsonb), :errorCode, :error,
                            :now, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("runId", runId)
                    .param("stepKey", step.name())
                    .param("phase", step.phase().name())
                    .param("sequence", step.sequence())
                    .param("status", status)
                    .param("required", step.requiredInV1())
                    .param("input", toJson(input))
                    .param("errorCode", step.isBlocked() ? "CAPABILITY_ABSENT" : null)
                    .param(
                            "error",
                            step.blockedUntil()
                                    .map("Blocked until %s ships"::formatted)
                                    .orElse(null))
                    .param("now", at(now))
                    .update();
        }

        audit.record(AuditFact.of("tenant.onboarding_started", AuditClass.BUSINESS)
                .by(startedBy)
                .at(ResourceScope.tenant(tenantId))
                .target("OnboardingRun", runId)
                .because("Tenant onboarding")
                .correlatedBy(runId.toString())
                .occurredAt(now)
                .build());

        // ADR 0004: never a direct Kafka publish inside a business transaction.
        // The application event is turned into an outbox row before this
        // transaction commits, so the run and the fact are one write.
        events.publishEvent(new TenantOnboardingStarted(
                UUID.randomUUID(), new TenantId(tenantId), runId, templateId, templateVersion, now));

        return runId;
    }

    /**
     * Runs the next due step, if any.
     *
     * <p>Three transactions, and the middle one is deliberately not a
     * transaction at all. A step handler talks to Keycloak; the previous shape
     * held a pooled connection — and this step's row lock — for the whole of
     * that round-trip, so a Keycloak having a bad minute took the database's
     * connection pool with it and stalled every other module.
     *
     * <p>The claim is what makes the split safe, and it was already here for the
     * other half of the same problem. A worker that dies between the two
     * transactions now leaves a durable {@code RUNNING} row rather than rolling
     * its claim back; {@link OnboardingScheduler} returns it to {@code PENDING}
     * after {@link #STEP_LEASE}, and the claim token means the dead worker
     * cannot complete it if it ever comes back. The attempt count survives the
     * same way, which it did not before — so a handler that kills its worker
     * every time is now bounded by {@link #MAXIMUM_ATTEMPTS} instead of being
     * retried forever.
     *
     * @return whether a step was executed, so a caller can drain a run
     */
    public boolean runNextStep(UUID runId) {
        Claim claim = transactions.execute(ignored -> claimNextStep(runId, clock.instant()));

        OnboardingStepHandler handler = claim.handler();
        if (handler == null) {
            return claim.advanced();
        }
        // Claim's own invariant (see its javadoc): step and token are non-null
        // exactly when handler is.
        DueStep step = Objects.requireNonNull(claim.step());
        UUID token = Objects.requireNonNull(claim.token());

        OnboardingStepHandler.StepResult result;
        try {
            result = handler.execute(new OnboardingStepHandler.StepContext(
                    runId, step.tenantId(), inputFor(step), step.externalReference(), step.attemptCount() + 1));
        } catch (RuntimeException failure) {
            log.warn("Onboarding step {} threw", step.step(), failure);
            result = OnboardingStepHandler.StepResult.retry(
                    "TRANSIENT_INFRASTRUCTURE", failure.getClass().getSimpleName());
        }

        // Read after the handler rather than before it, so a retry's backoff is
        // measured from when the attempt finished and not from when it started.
        Instant finished = clock.instant();
        OnboardingStepHandler.StepResult outcome = result;
        transactions.executeWithoutResult(ignored -> {
            applyResult(runId, step, token, outcome, finished);
            refreshRunStatus(runId);
        });
        return true;
    }

    /**
     * Takes the next due step for this run, or reports that there is nothing to
     * take.
     *
     * <p>The steps that end here rather than in a handler — activation, and a
     * step with no registered handler — are released inside this transaction,
     * because neither involves anything outside the database.
     */
    private Claim claimNextStep(UUID runId, Instant now) {
        Optional<DueStep> due = jdbc.sql("""
                SELECT s.id, s.tenant_id, s.step_key, s.attempt_count, s.external_reference,
                       s.input_snapshot::text AS input
                  FROM tenant.onboarding_steps s
                 WHERE s.run_id = :runId
                   AND s.status IN ('PENDING', 'FAILED')
                   AND s.available_at <= :now
                 ORDER BY s.sequence_number
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
                """)
                .param("runId", runId)
                .param("now", at(now))
                .query((rs, n) -> new DueStep(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        OnboardingStep.valueOf(rs.getString("step_key")),
                        rs.getInt("attempt_count"),
                        rs.getString("external_reference"),
                        rs.getString("input")))
                .optional();

        if (due.isEmpty()) {
            refreshRunStatus(runId);
            return Claim.none();
        }
        DueStep step = due.get();
        UUID claimToken = UUID.randomUUID();

        int claimed = jdbc.sql("""
                UPDATE tenant.onboarding_steps
                   SET status = 'RUNNING', claim_token = :token, claimed_at = :now,
                       attempt_count = attempt_count + 1, started_at = coalesce(started_at, :now),
                       updated_at = :now
                 WHERE id = :id AND status IN ('PENDING', 'FAILED')
                """)
                .param("id", step.id())
                .param("token", claimToken)
                .param("now", at(now))
                .update();

        if (claimed != 1) {
            return Claim.none();
        }

        // TENANT_ACTIVATE is never executed by a worker. It waits for a platform
        // administrator, because ADR 0008 puts the go-live decision with someone
        // who has seen the readiness evidence.
        if (step.step() == OnboardingStep.TENANT_ACTIVATE) {
            release(
                    step.id(),
                    claimToken,
                    "PENDING",
                    "AWAITING_APPROVAL",
                    "Activation requires platform approval",
                    now,
                    Duration.ofDays(3650));
            refreshRunStatus(runId);
            return Claim.none();
        }

        OnboardingStepHandler handler = handlers.get(step.step());
        if (handler == null) {
            release(
                    step.id(),
                    claimToken,
                    "BLOCKED",
                    "CAPABILITY_ABSENT",
                    "No handler is registered for this step",
                    now,
                    Duration.ZERO);
            return Claim.handled();
        }

        return new Claim(step, claimToken, handler, true);
    }

    /**
     * Runs with a step that is due now.
     *
     * <p>Here rather than on the scheduler, which used to declare it
     * {@code REQUIRES_NEW} and then call it on itself — bypassing the proxy, so
     * the annotation described a transaction that never existed and the lock
     * this query takes did not outlive the statement. A call from the scheduler
     * to this bean goes through the proxy, so the declaration is now true.
     *
     * <p>{@code SKIP LOCKED} is what makes several replicas cooperate instead of
     * contend: each takes runs the others have not.
     *
     * <p>{@code EXISTS} rather than a join and {@code DISTINCT}: PostgreSQL
     * rejects {@code FOR UPDATE} with {@code DISTINCT} outright — {@code 0A000},
     * every time, on every row count — so the join form was not a slow query but
     * a query that never ran. It threw on every scheduler tick, which is to say
     * no onboarding run has ever advanced outside a test that called
     * {@link #runNextStep(UUID)} directly. The subquery asks the same question
     * with one row per run and no locking restriction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> dueRuns(int limit) {
        return jdbc.sql("""
                SELECT r.id
                  FROM tenant.onboarding_runs r
                 WHERE r.status NOT IN ('ACTIVE', 'CANCELLED', 'READY')
                   AND EXISTS (
                       SELECT 1 FROM tenant.onboarding_steps s
                        WHERE s.run_id = r.id
                          AND s.status IN ('PENDING', 'FAILED')
                          AND s.available_at <= :now)
                 ORDER BY r.id
                 FOR UPDATE OF r SKIP LOCKED
                 LIMIT :limit
                """)
                .param("now", at(clock.instant()))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Returns steps abandoned by a dead worker.
     *
     * <p>Load-bearing rather than defensive since {@link #runNextStep} stopped
     * running the handler inside the claim's transaction: a worker that dies
     * mid-handler now leaves its claim committed, and this is the only thing
     * that gives the step back. The claim token still protects correctness — the
     * original worker, if it somehow returns, cannot complete a step it no
     * longer holds.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimStaleClaims() {
        Instant now = clock.instant();
        int reclaimed = jdbc.sql("""
                UPDATE tenant.onboarding_steps
                   SET status = 'PENDING', claim_token = NULL, claimed_at = NULL,
                       available_at = :now, updated_at = :now
                 WHERE status = 'RUNNING' AND claimed_at <= :cutoff
                """)
                .param("now", at(now))
                .param("cutoff", at(now.minus(STEP_LEASE)))
                .update();

        if (reclaimed > 0) {
            log.info("Reclaimed {} onboarding steps from expired claims", reclaimed);
        }
        return reclaimed;
    }

    /** Resumes a failed run. Never resets a completed step. */
    @Transactional
    public int resume(UUID runId, ActorRef actor, String reason) {
        int reopened = jdbc.sql("""
                UPDATE tenant.onboarding_steps
                   SET status = 'PENDING', available_at = :now, attempt_count = 0,
                       claim_token = NULL, claimed_at = NULL, updated_at = :now
                 WHERE run_id = :runId AND status = 'FAILED'
                """)
                .param("runId", runId)
                .param("now", at(clock.instant()))
                .update();

        jdbc.sql("""
                UPDATE tenant.onboarding_runs
                   SET status = 'PROVISIONING', failed_at = NULL, version = version + 1, updated_at = :now
                 WHERE id = :runId AND status = 'FAILED'
                """).param("runId", runId).param("now", at(clock.instant())).update();

        audit.record(AuditFact.of("tenant.onboarding_resumed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantOf(runId)))
                .target("OnboardingRun", runId)
                .because(reason)
                .changed(Map.of("reopenedSteps", reopened))
                .correlatedBy(runId.toString())
                .occurredAt(clock.instant())
                .build());

        return reopened;
    }

    /**
     * Activates a tenant, once a platform administrator approves.
     *
     * <p>Compare-and-set on the run version, so two simultaneous activations
     * produce one transition rather than two.
     */
    @Transactional
    public ActivationOutcome activate(UUID runId, ActorRef actor, String reason) {
        UUID tenantId = tenantOf(runId);

        List<String> outstanding = outstandingRequiredSteps(runId);
        if (!outstanding.isEmpty()) {
            return new ActivationOutcome(false, "READINESS_INCOMPLETE", outstanding, null);
        }

        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.TENANT_ACTIVATE.code(),
                parametersHash(runId),
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

        if (!approval.mayProceed()) {
            UUID requestId = approval instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null;
            return new ActivationOutcome(false, "AWAITING_APPROVAL", List.of(), requestId);
        }
        // Spent before the compare-and-set, in the same transaction. An
        // activation that throws takes the spend back with it; one that loses the
        // compare-and-set commits the spend, which is the conservative way round
        // — the maker exercised the signature, and asking for another is cheaper
        // than an activation approval that stays live for a day.
        approval.consume();

        Instant now = clock.instant();
        int activated =
                jdbc.sql("""
                UPDATE tenant.onboarding_runs
                   SET status = 'ACTIVE', current_phase = 'ACTIVATING', completed_at = :now,
                       version = version + 1, updated_at = :now
                 WHERE id = :runId AND status IN ('READY', 'ACTIVATING')
                """).param("runId", runId).param("now", at(now)).update();

        if (activated != 1) {
            return new ActivationOutcome(false, "NOT_READY", outstandingRequiredSteps(runId), null);
        }

        jdbc.sql("""
                UPDATE tenant.onboarding_steps
                   SET status = 'COMPLETED', completed_at = :now, claim_token = NULL, updated_at = :now
                 WHERE run_id = :runId AND step_key = 'TENANT_ACTIVATE'
                """).param("runId", runId).param("now", at(now)).update();

        jdbc.sql("UPDATE tenant.tenants SET status = 'ACTIVE', version = version + 1 WHERE id = :id")
                .param("id", tenantId)
                .update();

        audit.record(AuditFact.of("tenant.activated", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("OnboardingRun", runId)
                .because(reason)
                .correlatedBy(runId.toString())
                .occurredAt(now)
                .build());

        // Behind the compare-and-set, so two simultaneous activations produce one
        // fact as well as one transition.
        events.publishEvent(new TenantActivated(UUID.randomUUID(), new TenantId(tenantId), runId, "ACTIVE", now));

        return new ActivationOutcome(true, "ACTIVATED", List.of(), null);
    }

    /** {@link OnboardingHealthQuery}: the same counts {@code OnboardingScheduler}'s own gauges read. */
    @Override
    public OnboardingHealth onboardingHealth() {
        long waiting = jdbc.sql(
                        "SELECT count(*) FROM tenant.onboarding_runs WHERE status NOT IN ('ACTIVE', 'CANCELLED', 'FAILED')")
                .query(Long.class)
                .single();
        long failed = jdbc.sql("SELECT count(*) FROM tenant.onboarding_runs WHERE status = 'FAILED'")
                .query(Long.class)
                .single();
        return new OnboardingHealth(waiting, failed);
    }

    /** Required steps that have not completed, which is what blocks READY. */
    public List<String> outstandingRequiredSteps(UUID runId) {
        return jdbc.sql("""
                SELECT step_key FROM tenant.onboarding_steps
                 WHERE run_id = :runId AND required AND status <> 'COMPLETED'
                   AND step_key <> 'TENANT_ACTIVATE'
                 ORDER BY sequence_number
                """).param("runId", runId).query(String.class).list();
    }

    private void applyResult(
            UUID runId, DueStep step, UUID claimToken, OnboardingStepHandler.StepResult result, Instant now) {

        switch (result.outcome()) {
            case COMPLETED -> {
                int recorded = jdbc.sql("""
                        UPDATE tenant.onboarding_steps
                           SET status = 'COMPLETED', completed_at = :now, claim_token = NULL,
                               claimed_at = NULL, result_snapshot = CAST(:result AS jsonb),
                               external_reference = coalesce(:reference, external_reference),
                               last_error_code = NULL, last_error = NULL, updated_at = :now
                         WHERE id = :id AND claim_token = :token
                        """)
                        .param("id", step.id())
                        .param("token", claimToken)
                        .param("result", toJson(result.result()))
                        .param("reference", result.externalReference())
                        .param("now", at(now))
                        .update();
                if (recorded != 1) {
                    // The lease expired while the handler was running and another
                    // worker has the step. Said out loud rather than swallowed:
                    // the external work happened and its result is being thrown
                    // away, which is only survivable because every handler here
                    // reconciles rather than creates blindly.
                    log.warn(
                            "Onboarding step {} completed after its claim was reclaimed; "
                                    + "the result is discarded and the step will run again",
                            step.step());
                    return;
                }
                propagate(runId, step.step(), result.result());

                // After the compare-and-set, never before it. A step whose claim
                // was reclaimed returns above without a fact, because publishing
                // one for work whose result was discarded would tell consumers a
                // step is done that is about to run again.
                events.publishEvent(new TenantOnboardingStepCompleted(
                        UUID.randomUUID(),
                        new TenantId(step.tenantId()),
                        runId,
                        step.step().name(),
                        stepVersionOf(step.step()),
                        step.attemptCount() + 1,
                        now));
            }
            case RETRY -> {
                boolean exhausted = step.attemptCount() + 1 >= MAXIMUM_ATTEMPTS;
                release(
                        step.id(),
                        claimToken,
                        exhausted ? "FAILED" : "PENDING",
                        result.errorCode(),
                        result.detail(),
                        now,
                        exhausted ? Duration.ZERO : backoff(step.attemptCount() + 1));
            }
            case FAILED ->
                release(step.id(), claimToken, "FAILED", result.errorCode(), result.detail(), now, Duration.ZERO);
            case BLOCKED ->
                release(step.id(), claimToken, "BLOCKED", result.errorCode(), result.detail(), now, Duration.ZERO);
        }
    }

    /**
     * Carries a completed step's output into later steps' input, so the owner
     * step can see the organization the previous step created.
     */
    private void propagate(UUID runId, OnboardingStep completed, Map<String, Object> result) {
        if (completed != OnboardingStep.KEYCLOAK_ORGANIZATION_RECONCILE) {
            return;
        }
        Object organizationId = result.get("organizationId");
        if (organizationId == null) {
            return;
        }
        jdbc.sql("""
                UPDATE tenant.onboarding_steps
                   SET input_snapshot = input_snapshot || CAST(:patch AS jsonb)
                 WHERE run_id = :runId AND status IN ('PENDING', 'FAILED')
                """)
                .param("runId", runId)
                .param("patch", toJson(Map.of("organizationId", organizationId)))
                .update();
    }

    private void release(
            UUID stepId,
            UUID claimToken,
            String status,
            @Nullable String errorCode,
            @Nullable String detail,
            Instant now,
            Duration delay) {

        int released = jdbc.sql("""
                UPDATE tenant.onboarding_steps
                   SET status = :status, claim_token = NULL, claimed_at = NULL,
                       available_at = :availableAt, last_error_code = :errorCode,
                       last_error = :error, updated_at = :now
                 WHERE id = :id AND claim_token = :token
                """)
                .param("id", stepId)
                .param("token", claimToken)
                .param("status", status)
                .param("availableAt", at(now.plus(delay)))
                .param("errorCode", errorCode)
                .param("error", detail)
                .param("now", at(now))
                .update();

        if (released != 1) {
            // Another worker reclaimed the step after its lease ran out. The
            // token is what stops this one writing over the new claim.
            log.warn("Onboarding step {} was reclaimed before it could be released as {}", stepId, status);
        }
    }

    /** READY only when every required step has completed. */
    private void refreshRunStatus(UUID runId) {
        List<String> outstanding = outstandingRequiredSteps(runId);
        boolean anyFailed =
                jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tenant.onboarding_steps
                                WHERE run_id = :runId AND required AND status = 'FAILED')
                """).param("runId", runId).query(Boolean.class).single();

        String status = anyFailed ? "FAILED" : outstanding.isEmpty() ? "READY" : "PROVISIONING";
        Instant now = clock.instant();

        // `status <> :status` is what turns this from a write into a transition.
        // The scheduler calls this on every pass, and without the predicate a run
        // that has not moved for an hour still has a fresh updated_at and a
        // version several hundred higher — so neither the stalled-run gauge nor a
        // reader can tell a run that is progressing from one that is stuck, and
        // READY would emit its fact on every poll.
        int transitioned = jdbc.sql("""
                UPDATE tenant.onboarding_runs
                   SET status = :status,
                       failed_at = CASE WHEN :status = 'FAILED' THEN :now ELSE NULL END,
                       version = version + 1, updated_at = :now
                 WHERE id = :runId AND status NOT IN ('ACTIVE', 'CANCELLED') AND status <> :status
                """)
                .param("runId", runId)
                .param("status", status)
                .param("now", at(now))
                .update();

        if (transitioned != 1) {
            return;
        }
        TenantId tenantId = new TenantId(tenantOf(runId));
        if ("READY".equals(status)) {
            events.publishEvent(new TenantReady(UUID.randomUUID(), tenantId, runId, now));
        } else if ("FAILED".equals(status)) {
            FailedStep failure = firstFailedRequiredStep(runId);
            events.publishEvent(new TenantOnboardingFailed(
                    UUID.randomUUID(),
                    tenantId,
                    runId,
                    failure == null ? null : failure.stepKey(),
                    failure == null ? null : failure.errorCode(),
                    now));
        }
    }

    /**
     * The step a reader should look at first, which is the earliest failed one.
     *
     * <p>Its {@code last_error} is deliberately left behind: ADR 0008 forbids a
     * raw error on a topic, and the detail is whatever Keycloak or a provider
     * said about a named person.
     */
    private @Nullable FailedStep firstFailedRequiredStep(UUID runId) {
        return jdbc.sql("""
                SELECT step_key, last_error_code FROM tenant.onboarding_steps
                 WHERE run_id = :runId AND required AND status = 'FAILED'
                 ORDER BY sequence_number
                 LIMIT 1
                """)
                .param("runId", runId)
                .query((rs, n) -> new FailedStep(rs.getString("step_key"), rs.getString("last_error_code")))
                .optional()
                .orElse(null);
    }

    private int stepVersionOf(OnboardingStep step) {
        OnboardingStepHandler handler = handlers.get(step);
        return handler == null ? 1 : handler.stepVersion();
    }

    private Map<String, Object> inputFor(DueStep step) {
        Map<String, Object> input = new HashMap<>();
        if (step.input() != null && !step.input().isBlank()) {
            input.putAll(objectMapper.readValue(step.input(), Map.class));
        }
        return input;
    }

    private UUID tenantOf(UUID runId) {
        return jdbc.sql("SELECT tenant_id FROM tenant.onboarding_runs WHERE id = :id")
                .param("id", runId)
                .query(UUID.class)
                .single();
    }

    private String toJson(Map<String, Object> value) {
        return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    }

    private static String parametersHash(UUID runId) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(runId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required", unreachable);
        }
    }

    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(Math.min(300, (long) Math.pow(2, Math.min(attempt, 8))));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * What {@link #activate} decided.
     *
     * @param outstandingRequired what still blocks activation, so the caller can
     *                            say why rather than only that it refused
     * @param approvalRequestId   the pending ADR 0027 approval's id, present only
     *                            when the outcome is AWAITING_APPROVAL
     */
    public record ActivationOutcome(
            boolean activated,
            String outcome,
            List<String> outstandingRequired,
            @Nullable UUID approvalRequestId) {}

    private record FailedStep(String stepKey, String errorCode) {}

    private record DueStep(
            UUID id, UUID tenantId, OnboardingStep step, int attemptCount, String externalReference, String input) {}

    /**
     * What the claim transaction decided.
     *
     * @param step     null exactly when {@link #handler} is, in the no-work cases
     *                 {@link #none()} and {@link #handled()}
     * @param token    the claim token a handler must present back, null with
     *                 {@link #step}
     * @param handler  null when there is nothing for a handler to do, which is
     *                 the only case the caller has to distinguish
     * @param advanced whether the run moved, so a drain loop knows to come back
     */
    private record Claim(
            @Nullable DueStep step,
            @Nullable UUID token,
            @Nullable OnboardingStepHandler handler,
            boolean advanced) {

        static Claim none() {
            return new Claim(null, null, null, false);
        }

        /** Settled inside the claim transaction; the run moved without a handler. */
        static Claim handled() {
            return new Claim(null, null, null, true);
        }
    }
}
