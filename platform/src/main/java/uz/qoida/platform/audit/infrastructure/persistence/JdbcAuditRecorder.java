package uz.qoida.platform.audit.infrastructure.persistence;

import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.audit.domain.ChangeDocuments;

/**
 * Writes audit evidence in the caller's transaction (ADR 0027).
 *
 * <p>There is deliberately no {@code @Transactional} annotation and no new
 * transaction: joining the caller's transaction is what guarantees that a
 * committed change always has its fact, and that a rolled-back change leaves no
 * misleading evidence behind.
 */
@Repository
public class JdbcAuditRecorder implements AuditRecorder {

    private static final String INSERT = """
            INSERT INTO audit.audit_events (
                id, tenant_id, audit_class, action_code,
                actor_type, actor_subject, actor_display, on_behalf_of_subject,
                scope_type, scope_id, target_type, target_id, target_version,
                outcome, reason, change_document, evidence_reference,
                capability_used, approval_request_id,
                correlation_id, causation_id, request_id, occurred_at)
            VALUES (
                :id, :tenantId, :auditClass, :actionCode,
                :actorType, :actorSubject, :actorDisplay, :onBehalfOf,
                :scopeType, :scopeId, :targetType, :targetId, :targetVersion,
                :outcome, :reason, CAST(:changeDocument AS jsonb), :evidenceReference,
                :capabilityUsed, :approvalRequestId,
                :correlationId, :causationId, :requestId, :occurredAt)
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditFact fact) {
        Map<String, Object> sanitized = ChangeDocuments.sanitize(fact.changeDocument());

        jdbc.sql(INSERT)
                .param("id", fact.id())
                .param("tenantId", fact.scope().tenantId())
                .param("auditClass", fact.auditClass().name())
                .param("actionCode", fact.actionCode())
                .param("actorType", fact.actor().type().name())
                .param("actorSubject", fact.actor().subject())
                .param("actorDisplay", fact.actor().displayName())
                .param("onBehalfOf", fact.actor().onBehalfOfSubject())
                .param("scopeType", fact.scope().type().name())
                .param("scopeId", fact.scope().scopeId())
                .param("targetType", fact.targetType())
                .param("targetId", fact.targetId())
                .param("targetVersion", fact.targetVersion())
                .param("outcome", fact.outcome().name())
                .param("reason", fact.reason())
                .param("changeDocument", sanitized.isEmpty() ? null : toJson(sanitized))
                .param("evidenceReference", fact.evidenceReference())
                .param("capabilityUsed", fact.capabilityUsed())
                .param("approvalRequestId", fact.approvalRequestId())
                .param("correlationId", fact.correlationId())
                .param("causationId", fact.causationId())
                .param("requestId", MDC.get("requestId"))
                .param("occurredAt", fact.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    private String toJson(Map<String, Object> document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("The audit change document cannot be serialized", exception);
        }
    }
}
