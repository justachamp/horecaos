package uz.horecaos.platform.audit.infrastructure.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalAction.MissingPolicyMode;
import uz.horecaos.platform.audit.api.ApprovalGrant;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The shared maker-checker implementation (ADR 0027).
 *
 * <p>The policy version is snapshotted onto the request, so changing a threshold
 * afterwards cannot alter what an already-decided request was permitted to do.
 */
@Service
public class JdbcApprovalService implements ApprovalService {

    /**
     * Counts every policy resolution, tagged with what came of it.
     *
     * <p>The reason maker-checker was inert for the whole life of the platform
     * without anything turning red is that an unconfigured control and a control
     * that decided "not required" are the same silence. This is the difference
     * between them: {@code outcome=unresolved} means no policy governed the
     * action and it proceeded with one pair of eyes. An operator alerts on a
     * non-zero rate of it, or on the absence of {@code outcome=resolved} for an
     * action they believe they configured.
     */
    public static final String RESOLUTION_METRIC = "horecaos.approval.policy.resolution";

    /**
     * How many warned signatures the process remembers before it starts over.
     *
     * <p>The signature carries the tenant, so on a deployment with many tenants
     * this set would otherwise grow without limit for the life of the process.
     * Emptying it at the bound costs a warning repeated later; leaving the tenant
     * out of the signature — which is what this did first — cost every tenant but
     * the first one its warning entirely, because one tenant per action code per
     * process claimed the key and every other unconfigured control fell silent.
     */
    private static final int WARNED_SIGNATURE_LIMIT = 2_048;

    /**
     * Transaction-scoped key for the grants handed out and not yet spent.
     *
     * <p>Bound through {@link TransactionSynchronizationManager} rather than kept
     * on the service, because the service is a singleton and two threads
     * executing under two approvals must not see each other's obligations.
     */
    private static final Object UNSPENT_GRANTS = new Object();

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovalService.class);

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final Clock clock;
    private final MeterRegistry meters;

    /**
     * Tenant, action code and scope combinations already reported unresolved in
     * this process.
     *
     * <p>The metric carries the rate; the log carries the tenant, which ADR 0023
     * keeps off metric labels because a tenant identifier is unbounded
     * cardinality on the metrics store. Logging every unconfigured refund would
     * bury the fact under its own repetition, so the warning is once per tenant,
     * action code and scope. The tenant belongs in the key precisely because it
     * is in the line: a key without it makes the line a claim about one
     * arbitrary tenant and silence about the rest.
     */
    private final Set<String> alreadyWarned = ConcurrentHashMap.newKeySet();

    public JdbcApprovalService(JdbcClient jdbc, AuditRecorder audit, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    @Transactional
    public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
        Instant now = clock.instant();

        Optional<PolicyRow> policy = resolvePolicy(command, now);
        if (policy.isEmpty()) {
            MissingPolicyMode missingPolicyMode =
                    ApprovalAction.require(command.actionCode()).missingPolicyMode();
            reportUnresolved(command, missingPolicyMode);
            if (missingPolicyMode == MissingPolicyMode.REQUIRE_CONFIGURED_POLICY) {
                throw new ApiException(
                        ErrorCode.APPROVAL_POLICY_REQUIRED,
                        "A configured approval policy is required for " + command.actionCode(),
                        java.util.Map.of(
                                "actionCode", command.actionCode(),
                                "scope", command.scope().type().name()));
            }
            return new ApprovalOutcome.NotRequired();
        }
        countResolution(command, "resolved");

        // An approval is bound to exact parameters, so it cannot be reused for a
        // larger refund than the one a manager actually saw. It is also bound to
        // one execution: findRequest ignores a CONSUMED row, so the maker's next
        // identical submission lands here as a new request needing a new
        // signature rather than as the old one answering again.
        Optional<RequestRow> existing = findRequest(command, now);
        if (existing.isPresent()) {
            RequestRow request = existing.get();
            if (!"PENDING".equals(request.status())
                    && !"APPROVED".equals(request.status())
                    && !"DECLINED".equals(request.status())) {
                return createRequest(command, policy.get(), now);
            }
            return outcomeOf(request, command);
        }
        return createRequest(command, policy.get(), now);
    }

    /**
     * What a live request means to the caller that found it.
     *
     * <p>Extracted so the caller that <em>lost</em> the insert race reads its
     * winner exactly the way the caller that found it first does. Two spellings
     * of this mapping is how one of them ends up handing out a grant the other
     * would have refused.
     */
    private ApprovalOutcome outcomeOf(RequestRow request, ApprovalRequestCommand command) {
        // decided_by/decision_reason are @Nullable on RequestRow because a PENDING
        // row (mapRequest's own SELECT fetches the column regardless of status)
        // has neither; a row whose own status reads APPROVED or DECLINED always
        // has the one decide() writes together with that status.
        return switch (request.status()) {
            case "APPROVED" ->
                new ApprovalOutcome.Approved(
                        request.id(),
                        Objects.requireNonNull(request.decidedBy(), "An approved request has a decider"),
                        grantFor(request, command));
            case "DECLINED" ->
                new ApprovalOutcome.Declined(
                        request.id(),
                        Objects.requireNonNull(request.decisionReason(), "A declined request has a reason"));
            default -> new ApprovalOutcome.Pending(request.id());
        };
    }

    @Override
    @Transactional
    public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {
        RequestRow request = jdbc.sql("""
                SELECT id, status, requested_by, decided_by, decision_reason, tenant_id,
                       scope_type, scope_id, action_code, expires_at, version
                  FROM audit.approval_requests WHERE id = :id
                """)
                .param("id", requestId)
                .query(JdbcApprovalService::mapRequest)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown approval request: " + requestId));

        if (request.requestedBy().equals(approver.subject())) {
            throw new SelfApprovalException("The requester of %s cannot approve it".formatted(request.actionCode()));
        }
        if (!"PENDING".equals(request.status())) {
            throw new IllegalStateException("Approval request %s is already %s".formatted(requestId, request.status()));
        }
        if (!request.expiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("Approval request %s has expired".formatted(requestId));
        }

        int updated = jdbc.sql("""
                UPDATE audit.approval_requests
                   SET status = :status, decided_by = :decidedBy, decided_at = :now,
                       decision_reason = :reason, version = version + 1
                 WHERE id = :id AND status = 'PENDING' AND version = :expectedVersion
                """)
                .param("id", requestId)
                .param("status", decision == Decision.APPROVE ? "APPROVED" : "DECLINED")
                .param("decidedBy", approver.subject())
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("reason", reason)
                .param("expectedVersion", request.version())
                .update();

        if (updated != 1) {
            throw new IllegalStateException("Approval request %s was decided concurrently".formatted(requestId));
        }

        audit.record(AuditFact.of("approval." + decision.name().toLowerCase(Locale.ROOT), AuditClass.SECURITY)
                .by(approver)
                .at(scopeOf(request))
                .target("ApprovalRequest", requestId)
                .because(reason)
                .underApproval(requestId)
                .correlatedBy(requestId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    @Override
    @Transactional
    public int expireOverdue() {
        return jdbc.sql("""
                UPDATE audit.approval_requests
                   SET status = 'EXPIRED', version = version + 1
                 WHERE status = 'PENDING' AND expires_at <= :now
                """)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Settles a lapsed request for exactly this action before raising a new one.
     *
     * <p>V0081's index cannot mention {@code expires_at}, because an index
     * predicate has to be immutable and {@code now()} is not. So a request that
     * lapsed without a second signature still occupies the key even though
     * {@link #findRequest} rightly refuses to answer with it, and the same action
     * requested again next week — which is legitimate — would be refused by the
     * index rather than raised. Moving the lapsed row to EXPIRED is what frees
     * it, and EXPIRED is simply what it already is.
     *
     * <p>Doing it here does not make this the sweeper. {@link #expireOverdue} is
     * a bulk update over every tenant's stale rows and is deliberately off the
     * request path; this is three equality predicates against a unique index, on
     * the one action the caller is asking about, and it runs only when there was
     * nothing live to answer with. Correctness therefore stops depending on a
     * background job being healthy, which is the property the sweeper's own
     * documentation claims and could not have had while a lapsed row could block
     * its own successor.
     */
    private void lapseOverdueRequestFor(ApprovalRequestCommand command, Instant now) {
        jdbc.sql("""
                UPDATE audit.approval_requests
                   SET status = 'EXPIRED', version = version + 1
                 WHERE tenant_id IS NOT DISTINCT FROM :tenantId
                   AND action_code = :actionCode
                   AND parameters_hash = :hash
                   AND status = 'PENDING'
                   AND expires_at <= :now
                """)
                .param("tenantId", command.scope().tenantId())
                .param("actionCode", command.actionCode())
                .param("hash", command.parametersHash())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Raises the request for this action, or joins the one that already exists.
     *
     * <p><strong>One intended action is one request, and the database is what
     * says so.</strong> The check above is a SELECT and this is an INSERT, and
     * under READ COMMITTED nothing joins them: four threads issuing the identical
     * command all read "nothing live" and all inserted, producing four PENDING
     * rows a checker could not tell apart and could approve twice. V0081's
     * partial unique index on {@code (tenant_id, action_code, parameters_hash)
     * WHERE status = 'PENDING'} makes that impossible.
     *
     * <p>The race the constraint creates is handled here rather than pushed at
     * the caller. {@code ON CONFLICT DO NOTHING} rather than a caught
     * {@code DuplicateKeyException}, because in PostgreSQL a constraint violation
     * aborts the transaction and the recovery has to read the winner — which the
     * aborted transaction can no longer do without a savepoint. {@code DO
     * NOTHING} does not raise, and it waits on an uncommitted conflicting row and
     * then either finds it committed, in which case this insert affects nothing
     * and the re-read below finds the winner, or finds it rolled back, in which
     * case this insert succeeds. Losing the race is not a failure of the
     * caller's action: they asked for an approval and there is one.
     */
    private ApprovalOutcome createRequest(ApprovalRequestCommand command, PolicyRow policy, Instant now) {
        lapseOverdueRequestFor(command, now);
        UUID requestId = UUID.randomUUID();

        int inserted = jdbc.sql("""
                INSERT INTO audit.approval_requests (
                    id, tenant_id, action_code, parameters_hash, scope_type, scope_id,
                    policy_id, policy_is_platform, policy_version, threshold_description,
                    status, requested_by, requested_at, reason, expires_at)
                VALUES (
                    :id, :tenantId, :actionCode, :hash, :scopeType, :scopeId,
                    :policyId, :policyIsPlatform, :policyVersion, :threshold,
                    'PENDING', :requestedBy, :now, :reason, :expiresAt)
                ON CONFLICT (tenant_id, action_code, parameters_hash)
                    WHERE status = 'PENDING'
                    DO NOTHING
                """)
                .param("id", requestId)
                .param("tenantId", command.scope().tenantId())
                .param("actionCode", command.actionCode())
                .param("hash", command.parametersHash())
                .param("scopeType", command.scope().type().name())
                .param("scopeId", command.scope().scopeId())
                .param("policyId", policy.id())
                // The resolution that found the policy is what says whose it is:
                // resolvePolicy matched `tenant_id IS NOT DISTINCT FROM :tenantId`
                // at each level of the chain, so this is a record of the level it
                // stopped at and not a second, independent guess.
                .param("policyIsPlatform", policy.platformOwned())
                .param("policyVersion", policy.version())
                .param("threshold", policy.thresholdDescription())
                .param("requestedBy", command.requester().subject())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("reason", command.reason())
                .param("expiresAt", now.plus(command.validity()).atOffset(ZoneOffset.UTC))
                .update();

        if (inserted == 0) {
            // Another transaction raised the request for this action first. No
            // approval.requested fact, because none was: this caller joined a
            // request rather than making one, and an audit trail that says four
            // people asked for one refund is the console confusion this closes.
            return findRequest(command, now)
                    .map(winner -> outcomeOf(winner, command))
                    .orElseThrow(() ->
                            new IllegalStateException("An approval request for %s was raised concurrently and then "
                                            .formatted(command.actionCode())
                                    + "could not be read back"));
        }

        audit.record(AuditFact.of("approval.requested", AuditClass.SECURITY)
                .by(command.requester())
                .at(command.scope())
                .target("ApprovalRequest", requestId)
                .because(command.reason())
                .underApproval(requestId)
                .correlatedBy(requestId.toString())
                .occurredAt(now)
                .build());

        return new ApprovalOutcome.Pending(requestId);
    }

    /**
     * Makes an unconfigured control observable.
     *
     * <p>Carries the action code, the scope level, and nothing else. The
     * parameters, the parameters hash, the requester and the operator's reason
     * are all absent by construction: a reason is free text a person typed about
     * a customer, and ADR 0029 keeps that out of metrics and logs alike. The
     * tenant is in the log line and not on the metric, under the ADR 0023 rule
     * that tenant identifiers are unbounded cardinality on a metrics store
     * sharing a disk with PostgreSQL. It is in the deduplication key too, so
     * every tenant whose control is unconfigured is named once rather than only
     * whichever tenant reached this first.
     */
    private void reportUnresolved(ApprovalRequestCommand command, MissingPolicyMode missingPolicyMode) {
        countResolution(command, "unresolved", missingPolicyMode);

        String signature = command.scope().tenantId() + "|" + command.actionCode() + "|"
                + command.scope().type();
        if (alreadyWarned.size() >= WARNED_SIGNATURE_LIMIT) {
            alreadyWarned.clear();
        }
        if (alreadyWarned.add(signature)) {
            String consequence = missingPolicyMode == MissingPolicyMode.REQUIRE_CONFIGURED_POLICY
                    ? "The action was refused until an operator authors one."
                    : "The action proceeded on one signature; author a policy to require a second.";
            log.warn(
                    "ADR 0050: no approval policy governs {} at {} scope (tenant {}). {}",
                    command.actionCode(),
                    command.scope().type(),
                    command.scope().tenantId(),
                    consequence);
        }
    }

    private void countResolution(ApprovalRequestCommand command, String outcome) {
        countResolution(command, outcome, null);
    }

    private void countResolution(
            ApprovalRequestCommand command, String outcome, @Nullable MissingPolicyMode missingPolicyMode) {
        Counter.Builder counter = Counter.builder(RESOLUTION_METRIC)
                .description(
                        "ADR 0027 maker-checker policy resolution; unresolved means no policy " + "governs the action")
                .tag("action", command.actionCode())
                .tag("scope", command.scope().type().name())
                .tag("outcome", outcome);
        if (missingPolicyMode != null) {
            counter.tag("missing_policy_mode", missingPolicyMode.name());
        }
        counter.register(meters).increment();
    }

    private Optional<PolicyRow> resolvePolicy(ApprovalRequestCommand command, Instant now) {
        // Most specific scope first, matching the ADR 0030 precedence rule rather
        // than introducing a second one.
        for (ResourceScope level : command.scope().chain()) {
            Optional<PolicyRow> found = jdbc.sql("""
                    SELECT id, version, tenant_id IS NULL AS platform_owned,
                           coalesce(threshold_json->>'description', threshold_json::text) AS threshold
                     FROM audit.approval_policies
                     WHERE action_code = :actionCode
                       AND scope_type = :scopeType
                       AND tenant_id IS NOT DISTINCT FROM :tenantId
                       AND (
                           (NOT legacy_scope_wide
                               AND brand_id IS NOT DISTINCT FROM :brandId
                               AND location_id IS NOT DISTINCT FROM :locationId)
                           OR (legacy_scope_wide AND :scopeType IN ('BRAND', 'LOCATION'))
                       )
                       AND valid_from <= :now
                       AND (valid_until IS NULL OR valid_until > :now)
                     ORDER BY legacy_scope_wide, version DESC
                     LIMIT 1
                    """)
                    .param("actionCode", command.actionCode())
                    .param("scopeType", level.type().name())
                    .param("tenantId", level.tenantId())
                    .param("brandId", level.brandId())
                    .param("locationId", level.locationId())
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .query((resultSet, rowNumber) -> new PolicyRow(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getInt("version"),
                            resultSet.getString("threshold"),
                            resultSet.getBoolean("platform_owned")))
                    .optional();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Hands the caller the right to execute this approved action once.
     *
     * <p>The spend is a compare-and-set from {@code APPROVED} to {@code CONSUMED}
     * issued in the caller's transaction, which gives three properties at once
     * and none of them by accident.
     *
     * <p>It is atomic with the action: the update commits with the effect or
     * rolls back with it, so an action that failed after taking its approval
     * leaves the approval answerable. Marking the request spent here, on the way
     * out of the check, would have been simpler and wrong — the check runs
     * before the action and cannot know whether it succeeded, and an approval
     * destroyed by a rolled-back refund is a control that punishes the operator
     * for a database error.
     *
     * <p>It is exclusive: {@code WHERE status = 'APPROVED'} takes the row lock,
     * so of two transactions racing under one signature the second blocks, then
     * sees the settled row and updates nothing. It is told so rather than
     * proceeding, and its half of the work goes back with it.
     *
     * <p>And it is not forgettable. A grant is refused outright when there is no
     * transaction to bind it to, and one handed out and not spent by commit time
     * fails the commit. Both are loud, because the quiet version of this failure
     * is the defect being closed: an approval that stays answerable while the
     * action it authorised has already happened.
     */
    private ApprovalGrant grantFor(RequestRow request, ApprovalRequestCommand command) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "An approved action must run inside a transaction, so spending the approval "
                            + "commits with the action or rolls back with it. Approval request "
                            + request.id());
        }
        Set<UUID> holding = unspentGrants();
        holding.add(request.id());
        return () -> {
            spend(request, command);
            holding.remove(request.id());
        };
    }

    private void spend(RequestRow request, ApprovalRequestCommand command) {
        Instant now = clock.instant();
        int spent = jdbc.sql("""
                UPDATE audit.approval_requests
                   SET status = 'CONSUMED', consumed_at = :now, consumed_by = :by,
                       version = version + 1
                 WHERE id = :id
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND status = 'APPROVED'
                """)
                .param("id", request.id())
                .param("tenantId", command.scope().tenantId())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("by", command.requester().subject())
                .update();

        if (spent != 1) {
            // Either a concurrent execution won the row, or the caller spent the
            // same grant twice. Both mean this transaction must not keep whatever
            // it has written under an approval it does not hold.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This approval has already been used; the action needs a new approval");
        }

        audit.record(AuditFact.of("approval.consumed", AuditClass.SECURITY)
                .by(command.requester())
                .at(command.scope())
                .target("ApprovalRequest", request.id())
                // The maker's own words are already on the request and on the
                // decision; what this fact adds is that the signature was spent,
                // so it says that and nothing about a customer.
                .because("The approved action was executed under this approval")
                .underApproval(request.id())
                .correlatedBy(request.id().toString())
                .occurredAt(now)
                .build());
    }

    /**
     * The grants this transaction is holding, with the commit guard attached.
     *
     * <p>The set is bound to the transaction and the synchronization registered
     * once, the first time this transaction is handed a grant.
     */
    @SuppressWarnings("unchecked")
    private static Set<UUID> unspentGrants() {
        Set<UUID> holding = (Set<UUID>) TransactionSynchronizationManager.getResource(UNSPENT_GRANTS);
        if (holding != null) {
            return holding;
        }
        Set<UUID> fresh = new LinkedHashSet<>();
        TransactionSynchronizationManager.bindResource(UNSPENT_GRANTS, fresh);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!fresh.isEmpty()) {
                    throw new ApprovalNotConsumedException(fresh.iterator().next());
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(UNSPENT_GRANTS);
            }
        });
        return fresh;
    }

    private Optional<RequestRow> findRequest(ApprovalRequestCommand command, Instant now) {
        return jdbc.sql("""
                SELECT id, status, requested_by, decided_by, decision_reason, tenant_id,
                       scope_type, scope_id, action_code, expires_at, version
                  FROM audit.approval_requests
                 WHERE action_code = :actionCode
                   AND parameters_hash = :hash
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND status NOT IN ('EXPIRED', 'CONSUMED')
                   AND expires_at > :now
                 ORDER BY requested_at DESC
                 LIMIT 1
                """)
                .param("actionCode", command.actionCode())
                .param("hash", command.parametersHash())
                .param("tenantId", command.scope().tenantId())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(JdbcApprovalService::mapRequest)
                .optional();
    }

    private static ResourceScope scopeOf(RequestRow request) {
        return switch (request.scopeType()) {
            case "PLATFORM" -> ResourceScope.platform();
            case "TENANT" -> ResourceScope.tenant(request.tenantId());
            case "BRAND" -> ResourceScope.brand(request.tenantId(), request.scopeId());
            default -> ResourceScope.tenant(request.tenantId());
        };
    }

    private static RequestRow mapRequest(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new RequestRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("status"),
                resultSet.getString("requested_by"),
                resultSet.getString("decided_by"),
                resultSet.getString("decision_reason"),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("scope_type"),
                resultSet.getObject("scope_id", UUID.class),
                resultSet.getString("action_code"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("version"));
    }

    /**
     * The policy row a request is raised against.
     *
     * @param platformOwned whether this is the PLATFORM-scope policy the ADR 0030
     *                      chain ends at, rather than one of the caller's own.
     *                      Recorded onto the request because {@code
     *                      audit.approval_policies.tenant_id} is nullable, so the
     *                      policy id alone does not say whose policy it is —
     *                      V0088's {@code fk_approval_request_policy} is keyed on
     *                      the answer
     */
    private record PolicyRow(UUID id, int version, String thresholdDescription, boolean platformOwned) {}

    private record RequestRow(
            UUID id,
            String status,
            String requestedBy,
            @Nullable String decidedBy,
            @Nullable String decisionReason,
            UUID tenantId,
            String scopeType,
            UUID scopeId,
            String actionCode,
            Instant expiresAt,
            long version) {}
}
