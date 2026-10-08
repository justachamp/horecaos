package uz.horecaos.platform.assistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.assistant.application.TurnProvenanceService;
import uz.horecaos.platform.assistant.application.TurnProvenanceService.Provenance;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * "Why did it say that" (ADR 0069), for the operator reading a conversation: one assistant turn's
 * outcome and the facts it stood on. The inbox's message list carries each assistant message's
 * {@code assistantTurnId}; this is where that id is looked up.
 *
 * <p>Read-only and brand-scoped under {@code assistant.read}. It never returns words: see
 * {@link TurnProvenanceService}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/assistant")
@Tag(name = "Assistant turns", description = "Why the assistant said what it said")
public class AssistantTurnController {

    private final TurnProvenanceService provenance;

    public AssistantTurnController(TurnProvenanceService provenance) {
        this.provenance = provenance;
    }

    @GetMapping("/turns/{turnId}")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One assistant turn: how it ended and which facts it stood on",
            description = "The turn a conversation message points at through assistantTurnId. Lists the "
                    + "kinds of fact retrieved and which of them the reply cited, and the tenant's "
                    + "knowledge entry versions among them. Carries no customer or assistant words: those "
                    + "stay in the conversation. Not found when the turn is in another brand.")
    TurnResponse turn(@PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID turnId) {
        return TurnResponse.of(provenance.of(tenantId, brandId, turnId));
    }

    public record TurnResponse(
            UUID turnId,
            Instant occurredAt,
            String locale,
            List<String> questionKinds,
            String outcome,
            @Nullable String refusalReason,
            @Nullable String modelId,
            boolean servedFromCache,
            List<FactResponse> facts,
            List<KnowledgeVersionResponse> knowledgeVersions) {

        static TurnResponse of(Provenance view) {
            return new TurnResponse(
                    view.turnId(),
                    view.occurredAt(),
                    view.locale(),
                    view.questionKinds(),
                    view.outcome(),
                    view.refusalReason(),
                    view.modelId(),
                    view.servedFromCache(),
                    view.facts().stream()
                            .map(fact -> new FactResponse(fact.id(), fact.kind(), fact.cited()))
                            .toList(),
                    view.knowledgeVersions().stream()
                            .map(version -> new KnowledgeVersionResponse(version.entryId(), version.version()))
                            .toList());
        }
    }

    public record FactResponse(String id, String kind, boolean cited) {}

    public record KnowledgeVersionResponse(UUID entryId, int version) {}
}
