package uz.horecaos.platform.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The second signature: who may give it, and to which request (ADR 0027).
 *
 * <p>{@code ApprovalService.decide} has existed since ADR 0027 shipped and no
 * production path ever called it. That was harmless only while
 * {@code audit.approval_policies} had no writer, because an unconfigured control
 * answers {@code NotRequired} to everything. The moment a policy could be
 * authored, the missing decide path turned a fail-open control into one that
 * blocks the action for the whole of its validity and then lets it through by
 * lapsing — with the only operator exit being to end-date the policy, which is
 * switching the control off. This is the way to say yes.
 *
 * <p>What it adds over the raw {@code decide} is the three checks a surface has
 * to make and a store cannot:
 *
 * <ol>
 *   <li><strong>Tenant.</strong> {@code decide} looks a request up by identifier
 *       alone. Every read here is constrained to the tenant the caller was
 *       authorised against, so a request identifier from another tenant is
 *       not-found rather than decidable.</li>
 *   <li><strong>The policy's approver capability.</strong> V0007 has stored
 *       {@code required_approver_capability} on every policy row since the schema
 *       was created and no code had ever read it. It is the column that says who
 *       is allowed to be the second signature, and it is read here.</li>
 *   <li><strong>Four eyes.</strong> The requester is refused, whatever they
 *       hold.</li>
 * </ol>
 *
 * <p><strong>On "as the policy stood at request time".</strong> The capability is
 * not a column on {@code audit.approval_requests} — the snapshot is
 * {@code policy_id}, which names one immutable policy <em>version</em> row, and
 * {@code policy_version} and {@code threshold_description} beside it. Every
 * version is its own row (V0007's key is action code, scope, tenant and version),
 * and V0059 grants the application role {@code INSERT} plus {@code UPDATE} on
 * {@code valid_until} and nothing else, so the row a request points at cannot be
 * rewritten. Joining through {@code policy_id} therefore reads the policy exactly
 * as it stood when the request was raised, which is the behaviour that matters:
 * tightening the approver capability tomorrow must not retroactively invalidate a
 * signature given today, and loosening it must not retroactively license one.
 * Resolving the capability by action code and scope <em>now</em> would do both,
 * which is why this joins rather than re-resolves.
 *
 * <p>Deliberately not transactional as a whole. A refusal has to leave evidence,
 * and evidence written inside the transaction that then throws is evidence that
 * rolls back — the audit trail would record every accepted decision and no
 * refused one, which is the wrong half. The reads here are advisory: the state
 * transition is {@code ApprovalService.decide}, which is transactional, re-reads
 * the row and applies the optimistic version guard, so two approvers pressing at
 * once still produce exactly one outcome.
 */
@Service
public class ApprovalDecisionService {

    /** The width of {@code audit.approval_requests.decision_reason}. */
    public static final int MAXIMUM_REASON_LENGTH = 1000;

    private static final String PENDING = "PENDING";

    private final JdbcClient jdbc;
    private final ApprovalService approvals;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final Clock clock;

    public ApprovalDecisionService(
            JdbcClient jdbc,
            ApprovalService approvals,
            AuthorizationService authorization,
            AuditRecorder audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What is waiting for a second signature in this tenant.
     *
     * <p>An approver cannot approve what they cannot see, and until this existed
     * a pending request was visible only as an {@code approval.requested} row in
     * the audit search. Lapsed-but-unswept requests are excluded by comparing
     * {@code expires_at} here rather than trusting {@code status}, so the queue
     * tells the truth in the window between a request lapsing and the sweeper
     * marking it.
     *
     * <p><strong>The maker's free-text reason is deliberately not returned.</strong>
     * It is a sentence a person typed about a named customer and nothing
     * classifies it (ADR 0029). What comes back is the action code, the frozen
     * threshold sentence, the parameters hash, who asked and when — enough to
     * find the action in the console that raised it, where its detail is already
     * behind its own capability, and not a second uncontrolled copy of it here.
     *
     * @param subject the caller, used only to answer {@code mayDecide} per row
     */
    public List<PendingApproval> pending(UUID tenantId, @Nullable String actionCode, int limit, String subject) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.tenant_id, r.action_code, r.parameters_hash,
                       r.scope_type, r.scope_id, r.threshold_description,
                       r.policy_version, r.requested_by, r.requested_at, r.expires_at,
                       p.required_approver_capability
                  FROM audit.approval_requests r
                  JOIN audit.approval_policies p ON p.id = r.policy_id
                 WHERE r.tenant_id = :tenantId
                   AND r.status = 'PENDING'
                   AND r.expires_at > :now
                """);
        if (actionCode != null && !actionCode.isBlank()) {
            sql.append(" AND r.action_code = :actionCode");
        }
        // Oldest first: a queue an approver reads newest-first quietly starves
        // the request closest to lapsing, which is the one that needed them.
        sql.append(" ORDER BY r.requested_at LIMIT :limit");

        var statement = jdbc.sql(sql.toString())
                .param("tenantId", tenantId)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("limit", limit);
        if (actionCode != null && !actionCode.isBlank()) {
            statement = statement.param("actionCode", actionCode);
        }

        return statement.query(ApprovalDecisionService::mapRequest).list().stream()
                .map(row -> PendingApproval.of(row, mayDecide(row, subject)))
                .toList();
    }

    /**
     * Approves or declines one pending request.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the request is not
     *         this tenant's, {@code INSUFFICIENT_CAPABILITY} when the caller is
     *         the requester or does not hold the policy's approver capability,
     *         {@code UNPROCESSABLE_STATE} when the request is already decided or
     *         has lapsed, and {@code RESOURCE_CONFLICT} when another approver
     *         decided it first
     */
    public DecidedApproval decide(
            UUID tenantId, UUID requestId, ApprovalService.Decision decision, ActorRef approver, String reason) {

        String decisionReason = requireReason(reason);
        RequestRow request = load(tenantId, requestId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No approval request %s in this tenant".formatted(requestId)));

        // Four eyes with one pair of eyes is not a control, so this is checked
        // before anything the caller holds can matter. The database repeats it in
        // ck_approval_request_four_eyes; a service check that could be bypassed
        // and a constraint that cannot are both wanted here, because only the
        // service can refuse the attempt with an answer and a record.
        if (request.requestedBy().equals(approver.subject())) {
            recordRefusal(request, approver, "SELF_APPROVAL", decisionReason);
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_CAPABILITY,
                    "The person who raised an approval request can never decide it. " + "A second person has to.");
        }

        Capability required = Capability.find(request.requiredApproverCapability())
                .orElseThrow(() -> {
                    // The authoring surface refuses an unknown capability, so this can
                    // only be a policy written before that check or a capability removed
                    // in a release. Either way nobody can be the second signature, and
                    // saying so beats a blanket refusal nobody can act on.
                    recordRefusal(request, approver, "UNKNOWN_APPROVER_CAPABILITY", decisionReason);
                    return new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE,
                            "The governing policy requires a capability this platform no longer declares; "
                                    + "publish a new policy version naming a current one");
                });

        ResourceScope judgedAt = scopeOf(request);
        if (!authorization.has(approver.subject(), required, judgedAt)) {
            recordRefusal(request, approver, "MISSING_APPROVER_CAPABILITY", decisionReason);
            throw ApiException.insufficientCapability(
                    required.code(), judgedAt.type().name());
        }

        if (!PENDING.equals(request.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "This approval request is already %s".formatted(request.status()));
        }
        if (!request.expiresAt().isAfter(clock.instant())) {
            // Lapsed requests are not decidable, and re-opening one would let a
            // signature be given against a threshold that has had a day to change
            // underneath it. The maker resubmits, which raises a fresh request
            // under whatever policy governs now.
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This approval request lapsed at %s; the requester has to raise it again"
                            .formatted(request.expiresAt()));
        }

        try {
            approvals.decide(requestId, decision, approver, decisionReason);
        } catch (ApprovalService.SelfApprovalException selfApproval) {
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_CAPABILITY,
                    "The person who raised an approval request can never decide it. " + "A second person has to.");
        } catch (IllegalStateException raced) {
            // The optimistic version guard in the store. Two approvers pressing
            // at once leave one winner and this answer for the other.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This approval request was decided by somebody else; re-read it");
        }

        return load(tenantId, requestId)
                .map(decided -> new DecidedApproval(
                        decided.id(),
                        decided.actionCode(),
                        decided.status(),
                        // decide() above just moved this request out of PENDING, so
                        // the row decided_by/decided_at are read back from is one
                        // whose decision has already been written; RequestRow's own
                        // type stays honestly @Nullable because a PENDING row (the
                        // shape mapRequest produces) has neither.
                        Objects.requireNonNull(decided.decidedBy(), "A decided request has a decider"),
                        Objects.requireNonNull(decided.decidedAt(), "A decided request has a decision time")))
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No approval request %s".formatted(requestId)));
    }

    /**
     * Whether this caller could decide this row, answered per row so a console
     * does not present a button that will 403.
     *
     * <p>Never true for the requester's own request, whatever they hold.
     */
    private boolean mayDecide(RequestRow row, String subject) {
        if (subject == null || row.requestedBy().equals(subject)) {
            return false;
        }
        return Capability.find(row.requiredApproverCapability())
                .map(capability -> authorization.has(subject, capability, scopeOf(row)))
                .orElse(false);
    }

    /**
     * Evidence that somebody tried and was refused.
     *
     * <p>Written outside any transaction on purpose: a refusal recorded inside
     * the call that then throws would roll back with it, leaving an audit trail
     * holding every decision that succeeded and no attempt that did not. On the
     * most security-sensitive endpoint in the platform, the refused attempts are
     * the half worth keeping.
     *
     * <p>The change document carries the refusal, the action code and the
     * capability the policy demanded. It carries no parameters and no part of the
     * request's own reason (ADR 0029).
     */
    private void recordRefusal(RequestRow request, ActorRef approver, String refusal, String reason) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("refusal", refusal);
        document.put("requestAction", request.actionCode());
        document.put("requiredApproverCapability", request.requiredApproverCapability());
        document.put("policyVersion", request.policyVersion());

        audit.record(AuditFact.of("approval.decision.refused", AuditClass.SECURITY)
                .by(approver)
                .at(scopeOf(request))
                .target("ApprovalRequest", request.id())
                .outcome(AuditFact.Outcome.REJECTED)
                .because(reason)
                .changed(document)
                .underApproval(request.id())
                .correlatedBy(request.id().toString())
                .occurredAt(clock.instant())
                .build());
    }

    private Optional<RequestRow> load(UUID tenantId, UUID requestId) {
        // Constrained on the tenant the caller was authorised against, never on
        // the identifier alone. A PLATFORM-scoped request carries a null tenant
        // and so cannot be reached from a tenant surface at all, which is right:
        // HorecaOS's own floor is not a tenant's to sign.
        return jdbc.sql("""
                SELECT r.id, r.tenant_id, r.action_code, r.parameters_hash,
                       r.scope_type, r.scope_id, r.threshold_description,
                       r.policy_version, r.status, r.requested_by, r.requested_at,
                       r.expires_at, r.decided_by, r.decided_at,
                       p.required_approver_capability
                  FROM audit.approval_requests r
                  JOIN audit.approval_policies p ON p.id = r.policy_id
                 WHERE r.id = :id AND r.tenant_id = :tenantId
                """)
                .param("id", requestId)
                .param("tenantId", tenantId)
                .query(ApprovalDecisionService::mapRequestWithDecision)
                .optional();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A decision requires a reason: an approval with no recorded why is a signature "
                            + "nobody can account for");
        }
        if (reason.length() > MAXIMUM_REASON_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A decision reason is at most %d characters".formatted(MAXIMUM_REASON_LENGTH));
        }
        return reason;
    }

    /**
     * The scope the approver's capability is judged at.
     *
     * <p>A {@code LOCATION}-scoped request falls back to its tenant, because
     * {@code audit.approval_requests} stores one scope identifier and a
     * {@link ResourceScope} at location level needs the brand above it too. The
     * fallback is deliberately the strict direction: requiring the capability at
     * tenant level refuses a location-scoped grant that might have been enough,
     * and never accepts one that was not. Widening it needs the brand on the row,
     * which is a migration and a change to what {@code requireApproval} records.
     */
    private static ResourceScope scopeOf(RequestRow request) {
        return switch (request.scopeType()) {
            case "TENANT" -> ResourceScope.tenant(request.tenantId());
            case "BRAND" -> ResourceScope.brand(request.tenantId(), request.scopeId());
            default -> ResourceScope.tenant(request.tenantId());
        };
    }

    private static RequestRow mapRequest(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        return new RequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("action_code"),
                rs.getString("parameters_hash"),
                rs.getString("scope_type"),
                rs.getObject("scope_id", UUID.class),
                rs.getString("threshold_description"),
                rs.getInt("policy_version"),
                rs.getString("required_approver_capability"),
                PENDING,
                rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                null,
                null);
    }

    private static RequestRow mapRequestWithDecision(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new RequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("action_code"),
                rs.getString("parameters_hash"),
                rs.getString("scope_type"),
                rs.getObject("scope_id", UUID.class),
                rs.getString("threshold_description"),
                rs.getInt("policy_version"),
                rs.getString("required_approver_capability"),
                rs.getString("status"),
                rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant());
    }

    private record RequestRow(
            UUID id,
            UUID tenantId,
            String actionCode,
            String parametersHash,
            String scopeType,
            UUID scopeId,
            String thresholdDescription,
            int policyVersion,
            String requiredApproverCapability,
            String status,
            String requestedBy,
            Instant requestedAt,
            Instant expiresAt,
            @Nullable String decidedBy,
            @Nullable Instant decidedAt) {}

    /**
     * One request waiting for a second signature.
     *
     * @param parametersHash            what the approval is bound to. An approval
     *                                  cannot be reused for different parameters,
     *                                  so this is also how a console finds the
     *                                  action that raised the request
     * @param thresholdDescription      the sentence frozen onto the request when
     *                                  it was raised, not whatever the policy says
     *                                  now
     * @param requiredApproverCapability what the second signature has to hold, as
     *                                  the policy stood at request time
     * @param mayDecide                 whether the caller reading this list could
     *                                  decide this row — false for their own
     *                                  requests, whatever they hold
     */
    public record PendingApproval(
            UUID id,
            String actionCode,
            String parametersHash,
            String scopeType,
            UUID scopeId,
            String thresholdDescription,
            int policyVersion,
            String requiredApproverCapability,
            String requestedBy,
            Instant requestedAt,
            Instant expiresAt,
            boolean mayDecide) {

        private static PendingApproval of(RequestRow row, boolean mayDecide) {
            return new PendingApproval(
                    row.id(),
                    row.actionCode(),
                    row.parametersHash(),
                    row.scopeType(),
                    row.scopeId(),
                    row.thresholdDescription(),
                    row.policyVersion(),
                    row.requiredApproverCapability(),
                    row.requestedBy(),
                    row.requestedAt(),
                    row.expiresAt(),
                    mayDecide);
        }
    }

    /** The outcome of a decision, as the approver's console sees it. */
    public record DecidedApproval(UUID id, String actionCode, String status, String decidedBy, Instant decidedAt) {}
}
