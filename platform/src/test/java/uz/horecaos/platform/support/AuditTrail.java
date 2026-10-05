package uz.horecaos.platform.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;

/**
 * What a test needs to put a real audit recorder behind a service and read the facts back
 * (ADR 0027): the recorder writes to {@code audit.audit_events} in the caller's transaction,
 * so a fact a test reads here is the row an operator's activity log would read.
 */
public final class AuditTrail {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private AuditTrail() {}

    public static AuditRecorder recorder(JdbcClient jdbc) {
        return new JdbcAuditRecorder(jdbc, JSON);
    }

    /**
     * A recorder that keeps nothing, for a test about something other than the audit trail whose
     * subject now needs one to be built. The facts themselves are asserted, against a real
     * recorder, in the test that is about them.
     */
    public static AuditRecorder discarding() {
        return fact -> {};
    }

    /** A signed-in staff member with the given subject, the shape {@code JwtCurrentActor} answers with. */
    public static CurrentActor actor(String subject) {
        return () -> new AuthenticatedActor(subject, Set.of("tenant-admin"), Map.of());
    }

    /** One stored fact, the change document parsed. */
    public record Fact(
            String actionCode,
            String actorType,
            String actorSubject,
            String scopeType,
            UUID scopeId,
            String targetType,
            UUID targetId,
            Long targetVersion,
            String reason,
            JsonNode change) {

        /** The {@code before} of one field of the change document; a missing field is missing, not null. */
        public JsonNode before(String field) {
            return change.path(field).path("before");
        }

        public JsonNode after(String field) {
            return change.path(field).path("after");
        }

        public boolean touches(String field) {
            return change.has(field);
        }
    }

    /** Every fact with exactly this action code, oldest first. */
    public static List<Fact> facts(JdbcClient jdbc, String actionCode) {
        return jdbc.sql("""
                        SELECT action_code, actor_type, actor_subject, scope_type, scope_id,
                               target_type, target_id, target_version, reason,
                               COALESCE(change_document::text, '{}') AS change_document
                          FROM audit.audit_events
                         WHERE action_code = :actionCode
                         ORDER BY recorded_at, occurred_at
                        """)
                .param("actionCode", actionCode)
                .query((rs, row) -> new Fact(
                        rs.getString("action_code"),
                        rs.getString("actor_type"),
                        rs.getString("actor_subject"),
                        rs.getString("scope_type"),
                        rs.getObject("scope_id", UUID.class),
                        rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class),
                        (Long) rs.getObject("target_version"),
                        rs.getString("reason"),
                        JSON.readTree(rs.getString("change_document"))))
                .list();
    }

    /** The one fact with this action code; fails the test when there are none or several. */
    public static Fact only(JdbcClient jdbc, String actionCode) {
        List<Fact> facts = facts(jdbc, actionCode);
        if (facts.size() != 1) {
            throw new AssertionError("Expected exactly one " + actionCode + " fact, found " + facts.size());
        }
        return facts.get(0);
    }

    public static long count(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events")
                .query(Long.class)
                .single();
    }

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
    }
}
