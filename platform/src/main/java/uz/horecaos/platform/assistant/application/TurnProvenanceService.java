package uz.horecaos.platform.assistant.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore.TurnView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Why the assistant said what it said, for the operator looking at the message (ADR 0069:
 * "the audit trail showing which facts and which knowledge-entry versions produced each
 * answer").
 *
 * <p>A message in the inbox carries the id of the turn that produced it; this reads that turn
 * from the ledger. It returns how the turn ended and which kinds of fact it stood on, never any
 * words: the customer's question and the reply live encrypted in the conversation, and the ledger
 * has no copy of them to read.
 */
@Service
public class TurnProvenanceService {

    private final JdbcTurnStore turns;

    TurnProvenanceService(JdbcTurnStore turns) {
        this.turns = turns;
    }

    /**
     * @throws ApiException {@link ErrorCode#RESOURCE_NOT_FOUND} when no such turn exists in this
     *     brand, whichever of the tenant, the brand or the id is wrong -- the three look the same
     *     from outside
     */
    @Transactional(readOnly = true)
    public Provenance of(UUID tenantId, UUID brandId, UUID turnId) {
        TurnView turn = turns.find(tenantId, brandId, turnId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such assistant turn"));
        Set<String> cited = Set.copyOf(turn.citedFactIds());
        List<FactRef> facts = turn.facts().stream()
                .map(fact -> {
                    String id = String.valueOf(fact.get("id"));
                    return new FactRef(id, String.valueOf(fact.get("kind")), cited.contains(id));
                })
                .toList();
        List<KnowledgeRef> knowledge = turn.knowledgeVersions().stream()
                .map(version -> new KnowledgeRef(
                        UUID.fromString(String.valueOf(version.get("entryId"))),
                        Integer.parseInt(String.valueOf(version.get("version")))))
                .toList();
        return new Provenance(
                turn.id(),
                turn.occurredAt(),
                turn.locale(),
                List.of(turn.questionKinds().split(",")),
                turn.outcome(),
                turn.refusalReason(),
                turn.modelId(),
                turn.servedFromCache(),
                facts,
                knowledge);
    }

    /**
     * @param questionKinds what the question was classified as: PRICE, AVAILABILITY, BRANCHES, HOURS, COVERAGE,
     *                      ORDER_STATUS, KNOWLEDGE
     * @param outcome ANSWERED, REFUSED, ESCALATED or DECLINED
     * @param refusalReason why a refused or declined turn was, or null
     * @param facts every fact retrieved for the turn, in the order retrieved, with whether the reply cited it
     * @param knowledgeVersions the tenant's own knowledge entry versions among them
     */
    public record Provenance(
            UUID turnId,
            java.time.Instant occurredAt,
            String locale,
            List<String> questionKinds,
            String outcome,
            @Nullable String refusalReason,
            @Nullable String modelId,
            boolean servedFromCache,
            List<FactRef> facts,
            List<KnowledgeRef> knowledgeVersions) {}

    public record FactRef(String id, String kind, boolean cited) {}

    public record KnowledgeRef(UUID entryId, int version) {}
}
