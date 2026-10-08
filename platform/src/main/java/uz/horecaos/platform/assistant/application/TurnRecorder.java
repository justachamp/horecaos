package uz.horecaos.platform.assistant.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcTurnStore.TurnRecord;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Writing a turn to the ledger and, when it needs one, its ADR 0027 audit fact --
 * in one transaction, because "an action that succeeded without a record is
 * indistinguishable from one that never happened" (the audit module's own rule).
 *
 * <p>Its own bean, not a method of the turn service: {@code @Transactional} works
 * through a proxy and a call from inside the same object never reaches it.
 *
 * <p>What the audit fact holds is what ADR 0069 asks of it -- "the retrieved
 * inputs and the entry versions used, so 'why did it say that' has an answer": the
 * ids and amounts of every fact retrieved, which of them the reply cited, which
 * knowledge entry versions were among them, and how the turn ended. It holds no
 * message text of any kind, and none of its keys is one {@link ChangeDocuments}
 * would redact, which a test asserts so a rename cannot quietly turn the evidence
 * into {@code [redacted]}.
 */
@Component
public class TurnRecorder {

    static final String ACTOR = "assistant";

    private final JdbcTurnStore turns;
    private final AuditRecorder audit;

    TurnRecorder(JdbcTurnStore turns, AuditRecorder audit) {
        this.turns = turns;
        this.audit = audit;
    }

    /** Which audit fact a turn leaves, if any. */
    enum Evidence {
        /** Nothing worth a fact: an answer that stated nothing the platform is held to, or a dropped turn. */
        NONE,
        /** The turn quoted a price, asserted availability or coverage, or stated an order's state. */
        BINDING_ANSWER,
        /** The conversation became a person's. */
        HANDOFF
    }

    @Transactional
    void record(TurnRecord turn, Evidence evidence, List<Map<String, Object>> factProvenance) {
        turns.insert(turn);
        if (evidence == Evidence.NONE) {
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("outcome", turn.outcome());
        after.put("refusalReason", turn.refusalReason());
        after.put("questionKinds", turn.questionKinds());
        after.put("locale", turn.locale());
        after.put("factRefs", factProvenance);
        after.put("citedFacts", turn.citedFactIds());
        after.put("knowledgeVersions", turn.knowledgeVersions());
        after.put("servedFromCache", turn.servedFromCache());
        after.put("modelId", turn.modelId());

        audit.record(AuditFact.of(
                        evidence == Evidence.HANDOFF ? "assistant.turn.handed_off" : "assistant.turn.answered",
                        AuditClass.BUSINESS)
                .by(ActorRef.service(ACTOR))
                .at(ResourceScope.brand(turn.tenantId(), turn.brandId()))
                .target("assistant.turn", turn.id())
                .because(
                        evidence == Evidence.HANDOFF
                                ? "The assistant handed the conversation to a person"
                                : "The assistant answered from retrieved platform facts")
                .changed(ChangeDocuments.created(after))
                .correlatedBy(turn.conversationId().toString())
                .occurredAt(turn.occurredAt())
                .build());
    }
}
