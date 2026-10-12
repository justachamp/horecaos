package uz.horecaos.platform.audit.infrastructure.persistence;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.terminals.TerminalAttribution;
import uz.horecaos.platform.web.authorization.TerminalAttributionContext;

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

    /** The change-document field that names the screen an action was taken at (ADR 0155). */
    public static final String TERMINAL_DEVICE_FIELD = "terminalDevice";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditFact fact) {
        Map<String, Object> sanitized = ChangeDocuments.sanitize(fact.changeDocument());
        String evidence = fact.evidenceReference();

        // ADR 0155: an action attributed to a person at a shared terminal names the screen too. The actor is the person
        // (that is what the caller recorded); the device rides in the evidence reference as terminal:<sessionId> and in
        // the change document, so the activity log's «Кто» column shows a name and the device is one click away. Only a
        // fact whose actor IS the unlocked person is decorated -- a fact about someone else, raised during the same
        // request, is not an action taken at the screen.
        Optional<TerminalAttribution> via = TerminalAttributionContext.current();
        if (via.isPresent()
                && fact.actor().type() == uz.horecaos.platform.audit.api.ActorRef.Type.USER
                && fact.actor().subject().equals(via.get().personSubject())) {
            if (evidence == null) {
                evidence = via.get().evidenceReference();
            }
            if (!sanitized.containsKey(TERMINAL_DEVICE_FIELD)) {
                sanitized = new LinkedHashMap<>(sanitized);
                sanitized.putAll(ChangeDocuments.change(
                        TERMINAL_DEVICE_FIELD, null, via.get().deviceId().toString()));
            }
        }

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
                .param("evidenceReference", evidence)
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
