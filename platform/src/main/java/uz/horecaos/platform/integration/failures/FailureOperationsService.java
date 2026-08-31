package uz.horecaos.platform.integration.failures;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
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
 * Governed inspection, retry, and resolution of failed messages (ADR 0006).
 *
 * <p>The practice this replaces is an engineer running UPDATE statements during
 * an incident. That leaves no audit trail, no idempotency guarantee, and no way
 * to prove afterwards that a refund was not sent twice.
 *
 * <p>Retry returns the same immutable work to a pending state. It never creates
 * a new event id, because the provider idempotency key is derived from it and a
 * new id would defeat the very deduplication the retry depends on.
 *
 * <p><strong>Resolution runs under a {@link TransactionTemplate} rather than
 * {@code @Transactional}, and that is a correctness decision rather than a
 * style one.</strong> Asking for a second approver INSERTs a PENDING row and
 * then has to tell the operator to go and find a checker. Reporting that by
 * throwing out of an annotated method rolled the INSERT back with the
 * exception that announced it: {@code SELECT count(*) FROM
 * audit.approval_requests} was zero afterwards, no checker ever saw a request,
 * and arming the control on {@code UNCERTAIN_EXTERNAL_OUTCOME} blocked the
 * action permanently with nothing for anyone to approve. The transaction now
 * <em>commits</em> the request and the refusal is raised on the way out, after
 * the boundary closes. Nothing is swallowed — the caller still gets an
 * exception, and it now names the request that is waiting.
 */
@Service
public class FailureOperationsService {

    /** One action code for both paths; the parameters hash is what tells them apart. */
    private static final String RESOLVE_ACTION = ApprovalAction.INTEGRATION_FAILURE_RESOLVE.code();

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final ApprovalService approvals;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    public FailureOperationsService(
            JdbcClient jdbc,
            AuditRecorder audit,
            ApprovalService approvals,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.approvals = approvals;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    /**
     * Whether a second approver is needed, and the request to wait on if so.
     *
     * <p>Returned rather than thrown so the transactional body can finish and
     * commit. A pending approval is a normal state in an incident, not a failure
     * of the request, and the row recording it has to outlive the call.
     *
     * @param parametersHash what identifies the row being resolved, on the path
     *                       resolving it. The two paths do not have the same
     *                       shape and must not produce the same hash
     */
    private ApprovalOutcome approvalFor(
            FailureCategory category, UUID tenantId, String parametersHash, ActorRef actor, String reason) {

        if (!category.requiresSecondApprover()) {
            return new ApprovalOutcome.NotRequired();
        }
        return approvals.requireApproval(new ApprovalRequestCommand(
                RESOLVE_ACTION,
                parametersHash,
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
    }

    /**
     * What identifies one outbox event's resolution.
     *
     * <p>An outbox event id is unique on its own — {@code outbox_events} is keyed
     * by it — so the id and the category are the whole of it. The {@code outbox}
     * discriminator is not decoration: without it this hash collides with the
     * inbox hash for the same event id, and one signature covered suppressing the
     * outbound event as well as discarding a consumer's copy of it.
     */
    private static String outboxParametersHash(UUID eventId, FailureCategory category) {
        return parametersHash("outbox", eventId.toString(), category.name());
    }

    /**
     * What identifies one inbox message's resolution.
     *
     * <p><strong>The consumer name is part of the identity, because the row's key
     * is {@code (consumer_name, event_id)}</strong> — {@code uq_inbox_consumer_event}
     * in V0009, and the reason {@link #findInboxFailure} refuses to look a row up
     * by event id alone. One event reaches several consumers; each has its own
     * attempt count, its own error, and its own irreversible decision to make.
     * Hashing the event id alone meant a checker who read consumer-alpha's dead
     * letter signed for every other consumer's copy of the same event too, and the
     * maker could spend that signature on whichever row they liked. Single use
     * bounds the count at one; only the hash binds it to the row that was read.
     */
    private static String inboxParametersHash(String consumerName, UUID eventId, FailureCategory category) {

        return parametersHash("inbox", consumerName, eventId.toString(), category.name());
    }

    /**
     * SHA-256 over unambiguously delimited segments.
     *
     * <p>Each segment carries its own length, so no consumer name containing the
     * separator can be split differently from how it was joined. A plain
     * {@code join} would let {@code ("a", "b:c")} and {@code ("a:b", "c")} hash
     * alike, which is the same class of defect one level down.
     */
    private static String parametersHash(String... segments) {
        StringBuilder canonical = new StringBuilder(RESOLVE_ACTION);
        for (String segment : segments) {
            canonical.append('|').append(segment.length()).append(':').append(segment);
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashed);
        } catch (java.security.NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required", unreachable);
        }
    }

    public List<FailureSummary> listOutboxFailures(UUID tenantId, String status, int limit) {
        return jdbc.sql("""
                SELECT event_id AS id, tenant_id, event_type, status, attempt_count,
                       error_code, last_error, dead_lettered_at, occurred_at
                  FROM integration.outbox_events
                 WHERE status = :status
                   AND (:tenantId::uuid IS NULL OR tenant_id = :tenantId)
                 ORDER BY dead_lettered_at DESC NULLS LAST, occurred_at DESC
                 LIMIT :limit
                """)
                .param("status", status)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query((rs, n) -> new FailureSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("error_code"),
                        rs.getString("last_error")))
                .list();
    }

    public List<FailureSummary> listInboxFailures(String consumerName, UUID tenantId, String status, int limit) {
        return jdbc.sql("""
                SELECT event_id AS id, tenant_id, event_type, status, attempt_count,
                       last_error_code AS error_code, last_error
                  FROM integration.inbox_messages
                 WHERE consumer_name = :consumerName
                   AND status = :status
                   AND (:tenantId::uuid IS NULL OR tenant_id = :tenantId)
                 ORDER BY dead_lettered_at DESC NULLS LAST, received_at DESC
                 LIMIT :limit
                """)
                .param("consumerName", consumerName)
                .param("status", status)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query((rs, n) -> new FailureSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("error_code"),
                        rs.getString("last_error")))
                .list();
    }

    /**
     * One outbox event, by the id the list returned.
     *
     * <p>Deliberately not filtered by status. An operator reaches this after
     * retrying or resolving, and a read that only answered for {@code
     * DEAD_LETTER} would go blank exactly when they wanted to confirm the
     * transition they had just made.
     *
     * <p>{@code tenantId} is the same optional narrowing the list takes, and a
     * row belonging to a different tenant is reported as absent rather than as
     * refused. See {@link OutboxFailureDetail} for what the projection does and
     * does not carry.
     */
    public Optional<OutboxFailureDetail> findOutboxFailure(UUID eventId, UUID tenantId) {
        return jdbc.sql("""
                SELECT event_id, tenant_id, event_type, event_version, status, attempt_count,
                       error_code, last_error, topic, partition_key,
                       aggregate_type, aggregate_id, correlation_id, causation_id,
                       occurred_at, next_attempt_at, dead_lettered_at, published_at,
                       resolved_at, resolved_by, resolution_reason, resolution_evidence,
                       created_at, updated_at
                  FROM integration.outbox_events
                 WHERE event_id = :eventId
                   AND (:tenantId::uuid IS NULL OR tenant_id = :tenantId)
                """)
                .param("eventId", eventId)
                .param("tenantId", tenantId)
                .query((rs, n) -> new OutboxFailureDetail(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("error_code"),
                        rs.getString("last_error"),
                        rs.getString("topic"),
                        rs.getString("partition_key"),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id"),
                        requiredInstant(rs, "occurred_at"),
                        requiredInstant(rs, "next_attempt_at"),
                        instant(rs, "dead_lettered_at"),
                        instant(rs, "published_at"),
                        instant(rs, "resolved_at"),
                        rs.getString("resolved_by"),
                        rs.getString("resolution_reason"),
                        rs.getString("resolution_evidence"),
                        requiredInstant(rs, "created_at"),
                        requiredInstant(rs, "updated_at")))
                .optional();
    }

    /**
     * One inbox message, by the composite key that actually identifies it.
     *
     * <p>The key is {@code (consumer_name, event_id)} and not the event id
     * alone: one event reaches several consumers, each with its own attempt
     * count, its own error and its own decision to make. Looking a row up by
     * event id would return whichever consumer's row the planner happened to
     * find, and an operator would retry the wrong one.
     */
    public Optional<InboxFailureDetail> findInboxFailure(String consumerName, UUID eventId, UUID tenantId) {
        return jdbc.sql("""
                SELECT id, consumer_name, event_id, tenant_id, event_type, event_version,
                       status, attempt_count, last_error_code, last_error,
                       topic, partition, record_offset,
                       aggregate_type, aggregate_id, correlation_id, causation_id,
                       payload_sha256, occurred_at, received_at, available_at,
                       processed_at, dead_lettered_at,
                       resolved_at, resolved_by, resolution_reason, resolution_evidence,
                       updated_at
                  FROM integration.inbox_messages
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                   AND (:tenantId::uuid IS NULL OR tenant_id = :tenantId)
                """)
                .param("consumerName", consumerName)
                .param("eventId", eventId)
                .param("tenantId", tenantId)
                .query((rs, n) -> new InboxFailureDetail(
                        rs.getObject("id", UUID.class),
                        rs.getString("consumer_name"),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error_code"),
                        rs.getString("last_error"),
                        rs.getString("topic"),
                        rs.getInt("partition"),
                        rs.getLong("record_offset"),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id"),
                        rs.getString("payload_sha256"),
                        requiredInstant(rs, "occurred_at"),
                        requiredInstant(rs, "received_at"),
                        requiredInstant(rs, "available_at"),
                        instant(rs, "processed_at"),
                        instant(rs, "dead_lettered_at"),
                        instant(rs, "resolved_at"),
                        rs.getString("resolved_by"),
                        rs.getString("resolution_reason"),
                        rs.getString("resolution_evidence"),
                        requiredInstant(rs, "updated_at")))
                .optional();
    }

    private static @Nullable Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** For a column the schema declares {@code NOT NULL}; a null here is a corrupt row, not a valid state. */
    private static Instant requiredInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return java.util.Objects.requireNonNull(instant(rs, column), () -> column + " is NOT NULL in the schema");
    }

    /**
     * Returns a dead-lettered outbox event to {@code PENDING}.
     *
     * <p>Uses compare-and-set from the expected terminal state, so two operators
     * clicking retry during an incident produce one state change and one audit
     * fact rather than two republished events.
     */
    @Transactional
    public boolean retryOutboxEvent(UUID eventId, ActorRef actor, String reason) {
        Optional<UUID> tenantId = outboxTenant(eventId);
        if (tenantId.isEmpty()) {
            return false;
        }

        int updated = jdbc.sql("""
                UPDATE integration.outbox_events
                   SET status = 'PENDING',
                       attempt_count = 0,
                       next_attempt_at = :now,
                       dead_lettered_at = NULL,
                       claim_token = NULL,
                       claimed_at = NULL,
                       updated_at = :now
                 WHERE event_id = :eventId AND status = 'DEAD_LETTER'
                """)
                .param("eventId", eventId)
                .param("now", at(clock.instant()))
                .update();

        if (updated == 1) {
            record("integration.outbox.retried", tenantId.get(), "OutboxEvent", eventId, actor, reason, Map.of());
        }
        return updated == 1;
    }

    @Transactional
    public boolean retryInboxMessage(String consumerName, UUID eventId, ActorRef actor, String reason) {
        Optional<UUID> tenantId = inboxTenant(consumerName, eventId);
        if (tenantId.isEmpty()) {
            return false;
        }

        int updated = jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'RETRY_PENDING',
                       available_at = :now,
                       dead_lettered_at = NULL,
                       processing_token = NULL,
                       processing_started_at = NULL,
                       updated_at = :now
                 WHERE consumer_name = :consumerName AND event_id = :eventId AND status = 'DEAD_LETTER'
                """)
                .param("consumerName", consumerName)
                .param("eventId", eventId)
                .param("now", at(clock.instant()))
                .update();

        if (updated == 1) {
            record(
                    "integration.inbox.retried",
                    tenantId.get(),
                    "InboxMessage",
                    eventId,
                    actor,
                    reason,
                    Map.of("consumer", consumerName));
        }
        return updated == 1;
    }

    /**
     * Marks work as deliberately not requiring further processing.
     *
     * <p>Requires a reason always, and reconciliation evidence when the failure
     * category means a provider may already have acted. Resolving an uncertain
     * provider outcome without evidence is exactly how a duplicate charge gets
     * declared fine.
     */
    public boolean resolveOutboxEvent(
            UUID eventId,
            FailureCategory category,
            ActorRef actor,
            String reason,
            @Nullable String evidenceReference) {

        requireResolutionInputs(category, reason, evidenceReference);
        return report(
                unitOfWork.execute(status -> resolveOutboxWithin(eventId, category, actor, reason, evidenceReference)));
    }

    private Resolution resolveOutboxWithin(
            UUID eventId,
            FailureCategory category,
            ActorRef actor,
            String reason,
            @Nullable String evidenceReference) {

        Optional<UUID> tenantId = outboxTenant(eventId);
        if (tenantId.isEmpty()) {
            return Resolution.unchanged();
        }

        ApprovalOutcome approval =
                approvalFor(category, tenantId.get(), outboxParametersHash(eventId, category), actor, reason);
        if (!approval.mayProceed()) {
            // Returned, not thrown: this transaction has to commit the PENDING
            // row it just wrote. The refusal is raised by report(), outside.
            return Resolution.awaitingApproval(approval);
        }
        // One signature resolves one dead letter. Spent in this transaction, so
        // a resolution that fails leaves the approval usable and one that lands
        // cannot be replayed against the same item under the same signature.
        approval.consume();

        int updated = jdbc.sql("""
                UPDATE integration.outbox_events
                   SET status = 'RESOLVED',
                       dead_lettered_at = NULL,
                       claim_token = NULL,
                       claimed_at = NULL,
                       resolved_at = :now,
                       resolved_by = :actor,
                       resolution_reason = :reason,
                       resolution_evidence = :evidence,
                       updated_at = :now
                 WHERE event_id = :eventId AND status = 'DEAD_LETTER'
                """)
                .param("eventId", eventId)
                .param("actor", actor.subject())
                .param("reason", reason)
                .param("evidence", evidenceReference)
                .param("now", at(clock.instant()))
                .update();

        if (updated == 1) {
            record(
                    "integration.outbox.resolved",
                    tenantId.get(),
                    "OutboxEvent",
                    eventId,
                    actor,
                    reason,
                    Map.of("category", category.name(), "evidence", String.valueOf(evidenceReference)));
        }
        return Resolution.applied(updated == 1);
    }

    public boolean resolveInboxMessage(
            String consumerName,
            UUID eventId,
            FailureCategory category,
            ActorRef actor,
            String reason,
            @Nullable String evidenceReference) {

        requireResolutionInputs(category, reason, evidenceReference);
        return report(unitOfWork.execute(
                status -> resolveInboxWithin(consumerName, eventId, category, actor, reason, evidenceReference)));
    }

    private Resolution resolveInboxWithin(
            String consumerName,
            UUID eventId,
            FailureCategory category,
            ActorRef actor,
            String reason,
            @Nullable String evidenceReference) {

        Optional<UUID> tenantId = inboxTenant(consumerName, eventId);
        if (tenantId.isEmpty()) {
            return Resolution.unchanged();
        }

        ApprovalOutcome approval = approvalFor(
                category, tenantId.get(), inboxParametersHash(consumerName, eventId, category), actor, reason);
        if (!approval.mayProceed()) {
            return Resolution.awaitingApproval(approval);
        }
        // One signature resolves one dead letter. Spent in this transaction, so
        // a resolution that fails leaves the approval usable and one that lands
        // cannot be replayed against the same item under the same signature.
        approval.consume();

        int updated = jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'RESOLVED',
                       dead_lettered_at = NULL,
                       processing_token = NULL,
                       processing_started_at = NULL,
                       resolved_at = :now,
                       resolved_by = :actor,
                       resolution_reason = :reason,
                       resolution_evidence = :evidence,
                       updated_at = :now
                 WHERE consumer_name = :consumerName AND event_id = :eventId AND status = 'DEAD_LETTER'
                """)
                .param("consumerName", consumerName)
                .param("eventId", eventId)
                .param("actor", actor.subject())
                .param("reason", reason)
                .param("evidence", evidenceReference)
                .param("now", at(clock.instant()))
                .update();

        if (updated == 1) {
            record(
                    "integration.inbox.resolved",
                    tenantId.get(),
                    "InboxMessage",
                    eventId,
                    actor,
                    reason,
                    Map.of("consumer", consumerName, "category", category.name()));
        }
        return Resolution.applied(updated == 1);
    }

    /**
     * Raises the refusal once the transaction that recorded it has committed.
     *
     * <p>The whole of defect one is the ordering here. Thrown from inside, the
     * exception took the approval request with it.
     */
    private static boolean report(Resolution resolution) {
        if (resolution.awaiting() != null) {
            throw new SecondApproverRequiredException(resolution.awaiting());
        }
        return resolution.changed();
    }

    /**
     * What the transactional body decided, carried out past the commit.
     *
     * @param awaiting non-null exactly when a second signature is needed and the
     *                 committed request is what the operator has to wait on
     */
    private record Resolution(boolean changed, @Nullable ApprovalOutcome awaiting) {

        static Resolution applied(boolean changed) {
            return new Resolution(changed, null);
        }

        static Resolution unchanged() {
            return new Resolution(false, null);
        }

        static Resolution awaitingApproval(ApprovalOutcome outcome) {
            return new Resolution(false, outcome);
        }
    }

    private static void requireResolutionInputs(
            FailureCategory category, String reason, @Nullable String evidenceReference) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Resolving a failure requires a reason");
        }
        if (category.requiresReconciliation() && (evidenceReference == null || evidenceReference.isBlank())) {
            throw new IllegalArgumentException(
                    "Resolving %s requires reconciliation evidence: the provider may already have acted"
                            .formatted(category));
        }
    }

    private Optional<UUID> outboxTenant(UUID eventId) {
        return jdbc.sql("SELECT tenant_id FROM integration.outbox_events WHERE event_id = :id")
                .param("id", eventId)
                .query(UUID.class)
                .optional();
    }

    private Optional<UUID> inboxTenant(String consumerName, UUID eventId) {
        return jdbc.sql("""
                SELECT tenant_id FROM integration.inbox_messages
                 WHERE consumer_name = :consumerName AND event_id = :id
                """)
                .param("consumerName", consumerName)
                .param("id", eventId)
                .query(UUID.class)
                .optional();
    }

    private void record(
            String actionCode,
            UUID tenantId,
            String targetType,
            UUID targetId,
            ActorRef actor,
            String reason,
            Map<String, Object> changes) {

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(targetType, targetId)
                .because(reason)
                .changed(changes)
                .correlatedBy(targetId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Raised when resolution needs a second pair of eyes and does not yet have
     * them. Carries the request so an operator can be told what to wait for.
     *
     * <p>An {@link ApiException} rather than a bare {@code RuntimeException}, so
     * the surface answers 422 with the request identifier in the problem
     * document instead of 500 with nothing. It is thrown after the transaction
     * that wrote the request has committed, so the identifier in the message
     * names a row a checker can actually open. A repeat of the same resolution
     * finds that row and reports the same identifier rather than opening a
     * second request.
     *
     * <p>The reason the operator typed is deliberately not echoed here: ADR 0029
     * keeps free text a person wrote about a customer out of error messages, and
     * a request identifier is all the caller needs to follow the approval.
     */
    public static final class SecondApproverRequiredException extends ApiException {

        private final transient ApprovalOutcome outcome;

        SecondApproverRequiredException(ApprovalOutcome outcome) {
            super(ErrorCode.UNPROCESSABLE_STATE, message(outcome), properties(outcome));
            this.outcome = outcome;
        }

        public ApprovalOutcome outcome() {
            return outcome;
        }

        /** The pending or declined request, when the outcome names one. */
        public @Nullable UUID approvalRequestId() {
            return switch (outcome) {
                case ApprovalOutcome.Pending pending -> pending.requestId();
                case ApprovalOutcome.Declined declined -> declined.requestId();
                default -> null;
            };
        }

        private static String message(ApprovalOutcome outcome) {
            return switch (outcome) {
                case ApprovalOutcome.Pending pending ->
                    ("Resolving this failure requires a second approver. Approval request %s is "
                                    + "pending; a checker has to decide it before this can be resolved.")
                            .formatted(pending.requestId());
                case ApprovalOutcome.Declined declined ->
                    "Approval request %s was declined, so this failure cannot be resolved"
                            .formatted(declined.requestId());
                default -> "Resolving this failure requires a second approver";
            };
        }

        private static Map<String, Object> properties(ApprovalOutcome outcome) {
            return switch (outcome) {
                case ApprovalOutcome.Pending pending ->
                    Map.of("approvalRequestId", pending.requestId().toString(), "approvalStatus", "PENDING");
                case ApprovalOutcome.Declined declined ->
                    Map.of("approvalRequestId", declined.requestId().toString(), "approvalStatus", "DECLINED");
                default -> Map.of();
            };
        }
    }

    /** A redacted view of failed work, safe to return to operations. */
    public record FailureSummary(
            UUID id,
            UUID tenantId,
            String eventType,
            String status,
            int attemptCount,
            String errorCode,
            String lastError) {}

    /**
     * One outbox event, in the detail a decision needs — and not the payload.
     *
     * <p><strong>The payload is deliberately absent, and this is the whole
     * design of the single-item read.</strong> An outbox payload is a domain
     * event and an inbox payload is whatever a producer sent; either can carry
     * a customer's name, phone, or delivery address. ADR 0029 says personal data
     * never reaches a dead-letter summary, and gives the reason that applies
     * exactly here: an operator's authority to work the failure queue is not
     * authority to read the customer record behind an item. {@code
     * integration.failure.read} is held by {@code platform-support}, which is
     * cross-tenant and deliberately holds no {@code customer.pii.reveal}.
     * Rendering the row "to help debugging" would hand every one of them the
     * personal data of every tenant, through a control-plane endpoint, with no
     * purpose recorded.
     *
     * <p>What is here instead is the aggregate's type and id. Every fact an
     * operator legitimately needs about the business object is reachable from
     * those through the API that owns it, where their own authorization is
     * checked and the read is audited. That is the ADR 0029 rule in its
     * positive form: carry an identifier, resolve it through an authorized
     * call.
     *
     * <p>Everything added beyond {@link FailureSummary} is a routing or
     * lifecycle fact that ADR 0029 already forbids from being personal, because
     * each already travels somewhere the rule covers:
     *
     * <ul>
     *   <li>{@code topic}, {@code partitionKey}, {@code eventVersion},
     *       {@code aggregateType} and {@code aggregateId} are in the Kafka
     *       envelope and its headers (ADR 0004), so they are event content.
     *       They are what answers the question the list cannot: <em>what else is
     *       stuck behind this</em> — the outbox blocks per
     *       {@code (topic, partition_key)}, and without the key an operator
     *       cannot tell whether one order or one tenant's whole stream is
     *       waiting.</li>
     *   <li>{@code correlationId} and {@code causationId} go in every log line,
     *       and the correlation id is length-bounded and character-restricted
     *       before it is ever accepted from a caller.</li>
     *   <li>The timestamps and {@code attemptCount} are the retry budget: they
     *       say whether the item was abandoned mid-flight or genuinely
     *       exhausted, which decides between waiting and acting.</li>
     *   <li>The four resolution fields are the ADR 0006 override itself.
     *       Without them a read after a resolve cannot show that a human
     *       decided, who, or on what evidence — and making the override visible
     *       is the reason {@code RESOLVED} exists as its own state. They are
     *       operator-authored free text, returned only to a holder of the same
     *       capability, and they reach no event, log, metric, or DLT summary.</li>
     * </ul>
     *
     * <p>{@code trace_context} is also absent: it is a debugging handle into the
     * tracing backend rather than a fact about the failure, and an operator who
     * has the correlation id can already find the trace.
     *
     * <p><strong>One inherited weakness, recorded rather than papered over.</strong>
     * {@code lastError} is not a new field here — the list has always returned
     * it — but it is the one field on either projection that ADR 0029 does not
     * actually guarantee. It is written as the exception class plus {@code
     * getMessage()}, truncated to the column width: stack-trace-free and
     * bounded, which is what ADR 0006 asks for, and nothing more. An exception
     * whose message quotes the value it rejected puts that value in this column,
     * and a rejected value can be a phone number. The single-item read carries
     * the field unchanged and shows no more of it than the list does, so it
     * widens nothing; closing the gap means classifying the message where it is
     * produced, in the outbox relay and the inbox executor, which is the
     * producers' change and not this projection's.
     */
    public record OutboxFailureDetail(
            UUID eventId,
            UUID tenantId,
            String eventType,
            int eventVersion,
            String status,
            int attemptCount,
            String errorCode,
            String lastError,
            String topic,
            String partitionKey,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            String causationId,
            Instant occurredAt,
            Instant nextAttemptAt,
            @Nullable Instant deadLetteredAt,
            @Nullable Instant publishedAt,
            @Nullable Instant resolvedAt,
            String resolvedBy,
            String resolutionReason,
            String resolutionEvidence,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * One consumer's copy of one event. The payload is absent for the reasons
     * given on {@link OutboxFailureDetail}, and more strongly: an inbox payload
     * was written by a producer this consumer does not control, so nothing about
     * it has been through HorecaOS's own classification.
     *
     * <p>{@code payloadSha256} stands in for it. It is a hash, so it discloses
     * nothing, and it is the one payload fact an operator actually needs: ADR
     * 0005 rejects the same event id arriving with a different hash as a
     * producer contract violation rather than a duplicate, and
     * {@code PAYLOAD_INVALID} on a hash collision is a different conversation
     * from {@code PAYLOAD_INVALID} on a missing field.
     *
     * <p>{@code partition} and {@code recordOffset} say which broker record
     * carried this, which is how an operator confirms whether the item is still
     * within Kafka's retention before assuming a redelivery could ever arrive.
     */
    public record InboxFailureDetail(
            UUID messageId,
            String consumerName,
            UUID eventId,
            UUID tenantId,
            String eventType,
            int eventVersion,
            String status,
            int attemptCount,
            String errorCode,
            String lastError,
            String topic,
            int partition,
            long recordOffset,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            String causationId,
            String payloadSha256,
            Instant occurredAt,
            Instant receivedAt,
            Instant availableAt,
            @Nullable Instant processedAt,
            @Nullable Instant deadLetteredAt,
            @Nullable Instant resolvedAt,
            String resolvedBy,
            String resolutionReason,
            String resolutionEvidence,
            Instant updatedAt) {}
}
