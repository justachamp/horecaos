package uz.horecaos.platform.assistant.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code assistant.turns} (V0511): the spend ledger and the provenance of every
 * turn. Append-only; this class has no UPDATE.
 *
 * <p>Holds provenance and counts and never a word of anyone's message -- see the
 * migration's own comment for why, and {@code TurnRecord} for the one place a
 * caller could have tried to put one.
 */
@Repository
public class JdbcTurnStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcTurnStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(TurnRecord turn) {
        jdbc.sql("""
                INSERT INTO assistant.turns
                    (id, tenant_id, brand_id, conversation_id, occurred_at, locale, question_kinds, outcome,
                     refusal_reason, provider_type, model_id, input_tokens, output_tokens, cost_usd_micros,
                     latency_ms, served_from_cache, facts, cited_fact_ids, knowledge_versions, customer_pseudonym)
                VALUES (:id, :tenantId, :brandId, :conversationId, :occurredAt, :locale, :questionKinds, :outcome,
                        :refusalReason, :providerType, :modelId, :inputTokens, :outputTokens, :costMicros,
                        :latencyMs, :cached, CAST(:facts AS jsonb), CAST(:cited AS jsonb),
                        CAST(:knowledge AS jsonb), :pseudonym)
                """)
                .param("id", turn.id())
                .param("tenantId", turn.tenantId())
                .param("brandId", turn.brandId())
                .param("conversationId", turn.conversationId())
                .param("occurredAt", utc(turn.occurredAt()))
                .param("locale", turn.locale())
                .param("questionKinds", turn.questionKinds())
                .param("outcome", turn.outcome())
                .param("refusalReason", turn.refusalReason())
                .param("providerType", turn.providerType())
                .param("modelId", turn.modelId())
                .param("inputTokens", turn.inputTokens())
                .param("outputTokens", turn.outputTokens())
                .param("costMicros", turn.costUsdMicros())
                .param("latencyMs", turn.latencyMillis())
                .param("cached", turn.servedFromCache())
                .param("facts", objectMapper.writeValueAsString(turn.facts()))
                .param("cited", objectMapper.writeValueAsString(turn.citedFactIds()))
                .param("knowledge", objectMapper.writeValueAsString(turn.knowledgeVersions()))
                .param("pseudonym", turn.customerPseudonym())
                .update();
    }

    /** What the tenant has cost the platform in the half-open window, in millionths of a US dollar. */
    public long spendMicros(UUID tenantId, Instant from, Instant until) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(cost_usd_micros), 0)
                  FROM assistant.turns
                 WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :until
                """)
                .param("tenantId", tenantId)
                .param("from", utc(from))
                .param("until", utc(until))
                .query(Long.class)
                .single();
    }

    /** Turns the assistant took in one conversation since {@code since}, whatever their outcome but a dropped one. */
    public long turnsInConversationSince(UUID tenantId, UUID conversationId, Instant since) {
        return jdbc.sql("""
                SELECT count(*)
                  FROM assistant.turns
                 WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                   AND occurred_at >= :since AND outcome <> 'DECLINED'
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("since", utc(since))
                .query(Long.class)
                .single();
    }

    /** Whether the assistant has ever answered in this conversation -- what decides if the disclosure is due. */
    public boolean hasAnsweredIn(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM assistant.turns
                     WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND outcome = 'ANSWERED')
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(Boolean.class)
                .single();
    }

    /** The month's turns, grouped by outcome, for the usage screen. */
    public UsageRow usage(UUID tenantId, Instant from, Instant until) {
        return jdbc.sql("""
                SELECT count(*) AS turns,
                       count(*) FILTER (WHERE outcome = 'ANSWERED') AS answered,
                       count(*) FILTER (WHERE outcome = 'REFUSED') AS refused,
                       count(*) FILTER (WHERE outcome = 'ESCALATED') AS escalated,
                       count(*) FILTER (WHERE outcome = 'DECLINED') AS declined,
                       count(*) FILTER (WHERE served_from_cache) AS cached,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cost_usd_micros), 0) AS cost_micros
                  FROM assistant.turns
                 WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :until
                """)
                .param("tenantId", tenantId)
                .param("from", utc(from))
                .param("until", utc(until))
                .query((row, number) -> new UsageRow(
                        row.getLong("turns"),
                        row.getLong("answered"),
                        row.getLong("refused"),
                        row.getLong("escalated"),
                        row.getLong("declined"),
                        row.getLong("cached"),
                        row.getLong("input_tokens"),
                        row.getLong("output_tokens"),
                        row.getLong("cost_micros")))
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * One turn as it is written. There is deliberately no field for a message:
     * a customer's words and the reply live encrypted in the conversation, and a
     * ledger that could hold them would be a second, unencrypted copy.
     */
    public record TurnRecord(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID conversationId,
            Instant occurredAt,
            String locale,
            String questionKinds,
            String outcome,
            @Nullable String refusalReason,
            @Nullable String providerType,
            @Nullable String modelId,
            long inputTokens,
            long outputTokens,
            long costUsdMicros,
            int latencyMillis,
            boolean servedFromCache,
            java.util.List<Map<String, Object>> facts,
            java.util.List<String> citedFactIds,
            java.util.List<Map<String, Object>> knowledgeVersions,
            @Nullable String customerPseudonym) {}

    public record UsageRow(
            long turns,
            long answered,
            long refused,
            long escalated,
            long declined,
            long cached,
            long inputTokens,
            long outputTokens,
            long costUsdMicros) {}
}
