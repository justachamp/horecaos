package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.conversations.domain.FlowRunStatus;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * {@code conversations.flow_runs} (V0108). A run's transition is a
 * compare-and-set on {@code version} — {@link #advance} — which is the whole
 * of this engine's idempotent-block-execution discipline (ADR 0059): two
 * concurrent (or duplicate-redelivered) attempts to leave the same state race
 * the same {@code UPDATE ... WHERE version = :expected}, and only one wins.
 * The loser sends nothing.
 */
@Repository
class FlowRunRepository {

    private static final String CAPTURED_FIELDS_TABLE = "conversations.flow_runs";
    private static final String CAPTURED_FIELDS_COLUMN = "captured_fields_protected";
    private static final TypeReference<Map<String, String>> CAPTURED_FIELDS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final Clock clock;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;

    FlowRunRepository(JdbcClient jdbc, Clock clock, FieldProtection protection, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    Optional<Row> findActive(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT id, tenant_id, conversation_id, flow_document_id, flow_version, current_state_id,
                       status, captured_fields_protected, resume_due_at, version
                FROM conversations.flow_runs
                WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(FlowRunRepository::map)
                .optional();
    }

    List<Row> dueForResume(Instant now, int batchSize) {
        return jdbc.sql("""
                SELECT id, tenant_id, conversation_id, flow_document_id, flow_version, current_state_id,
                       status, captured_fields_protected, resume_due_at, version
                FROM conversations.flow_runs
                WHERE status = 'ACTIVE' AND resume_due_at IS NOT NULL AND resume_due_at <= :now
                ORDER BY resume_due_at
                LIMIT :batchSize
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .query(FlowRunRepository::map)
                .list();
    }

    /** Starts a new run at {@code startStateId}. The caller has already ensured no ACTIVE run exists. */
    Row start(UUID tenantId, UUID conversationId, UUID flowDocumentId, int flowVersion, String startStateId) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO conversations.flow_runs (
                    id, tenant_id, conversation_id, flow_document_id, flow_version, current_state_id,
                    status, created_at, updated_at)
                VALUES (:id, :tenantId, :conversationId, :documentId, :flowVersion, :stateId,
                    'ACTIVE', :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("documentId", flowDocumentId)
                .param("flowVersion", flowVersion)
                .param("stateId", startStateId)
                .param("now", utc(now))
                .update();
        return new Row(
                id,
                tenantId,
                conversationId,
                flowDocumentId,
                flowVersion,
                startStateId,
                FlowRunStatus.ACTIVE,
                null,
                null,
                0);
    }

    /**
     * Moves an ACTIVE run to {@code newStateId}, optionally arming {@code
     * resumeDueAt} for a delay block and/or merging one newly captured field.
     * Guarded by {@code expectedVersion}: the row this run was read at.
     *
     * @return whether this call won the race — false means a concurrent (or
     *         redelivered) caller already advanced this run, and the caller
     *         must not act any further, in particular must not send anything
     */
    boolean advance(
            UUID tenantId,
            UUID runId,
            long expectedVersion,
            String newStateId,
            @Nullable Instant resumeDueAt,
            @Nullable String capturedFieldKey,
            @Nullable String capturedFieldValue,
            @Nullable Map<String, String> currentCapturedFields) {
        String capturedFieldsBlob = null;
        if (capturedFieldKey != null) {
            Map<String, String> merged =
                    currentCapturedFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(currentCapturedFields);
            merged.put(capturedFieldKey, capturedFieldValue);
            capturedFieldsBlob = protect(tenantId, runId, merged);
        }
        return jdbc.sql("""
                UPDATE conversations.flow_runs
                SET current_state_id = :stateId, resume_due_at = :resumeDueAt, version = version + 1,
                    updated_at = :now,
                    captured_fields_protected = COALESCE(:capturedFields, captured_fields_protected)
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion AND status = 'ACTIVE'
                """)
                        .param("stateId", newStateId)
                        .param("resumeDueAt", resumeDueAt == null ? null : utc(resumeDueAt))
                        .param("now", utc(clock.instant()))
                        .param("capturedFields", capturedFieldsBlob)
                        .param("tenantId", tenantId)
                        .param("id", runId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /** Ends a run in a terminal status (COMPLETED, HANDED_TO_OPERATOR, or ABANDONED). Same CAS discipline as {@link #advance}. */
    boolean end(UUID tenantId, UUID runId, long expectedVersion, FlowRunStatus terminalStatus) {
        return jdbc.sql("""
                UPDATE conversations.flow_runs
                SET status = :status, resume_due_at = NULL, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion AND status = 'ACTIVE'
                """)
                        .param("status", terminalStatus.name())
                        .param("now", utc(clock.instant()))
                        .param("tenantId", tenantId)
                        .param("id", runId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    Map<String, String> capturedFields(UUID tenantId, Row run) {
        if (run.capturedFieldsProtected() == null) {
            return Map.of();
        }
        String plaintext = protection.reveal(
                tenantId,
                ProtectedValue.deserialize(run.capturedFieldsProtected()),
                new RecordRef(CAPTURED_FIELDS_TABLE, CAPTURED_FIELDS_COLUMN, run.id()),
                "conversations.flow-run.captured-fields");
        return objectMapper.readValue(plaintext, CAPTURED_FIELDS_TYPE);
    }

    private String protect(UUID tenantId, UUID runId, Map<String, String> fields) {
        String plaintext = objectMapper.writeValueAsString(fields);
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef(CAPTURED_FIELDS_TABLE, CAPTURED_FIELDS_COLUMN, runId),
                        plaintext)
                .serialize();
    }

    private static Row map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        java.sql.Timestamp resumeDueAt = row.getTimestamp("resume_due_at");
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("flow_document_id", UUID.class),
                row.getInt("flow_version"),
                row.getString("current_state_id"),
                FlowRunStatus.valueOf(row.getString("status")),
                row.getString("captured_fields_protected"),
                resumeDueAt == null ? null : resumeDueAt.toInstant(),
                row.getLong("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record Row(
            UUID id,
            UUID tenantId,
            UUID conversationId,
            UUID flowDocumentId,
            int flowVersion,
            String currentStateId,
            FlowRunStatus status,
            @Nullable String capturedFieldsProtected,
            @Nullable Instant resumeDueAt,
            long version) {}
}
