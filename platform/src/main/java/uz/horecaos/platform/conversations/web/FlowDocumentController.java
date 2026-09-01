package uz.horecaos.platform.conversations.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.conversations.application.FlowDocumentService;
import uz.horecaos.platform.conversations.application.FlowDocumentView;
import uz.horecaos.platform.conversations.domain.FlowDocumentException;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Where a brand's conversation flows are published (ADR 0059). YAML is the
 * only authoring surface — decided, not deferred; there will never be a
 * visual builder here.
 *
 * <p>Behind {@code conversation.flow.manage}. A document that fails to parse
 * or validate is refused whole: {@link FlowDocumentException} carries every
 * problem found, and none of it reaches {@code conversations.flow_documents}
 * — "a broken flow must never be discoverable only at runtime."
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/conversations/flows")
@Tag(name = "Conversation flows", description = "The YAML flow documents the conversations engine executes")
public class FlowDocumentController {

    private final FlowDocumentService flows;
    private final CurrentActor currentActor;

    public FlowDocumentController(FlowDocumentService flows, CurrentActor currentActor) {
        this.flows = flows;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CONVERSATION_FLOW_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "List a brand's flow documents",
            description = "Every version of every flow key, newest first. At most one version per "
                    + "flow key is ever active at once.")
    List<FlowDocumentResponse> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return flows.list(tenantId, brandId).stream()
                .map(FlowDocumentResponse::of)
                .toList();
    }

    @PostMapping("/{flowKey}")
    @RequiresCapability(value = Capability.CONVERSATION_FLOW_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish the next version of a flow document",
            description = "Never an edit — always the next version. When activate is true, the "
                    + "previously active version for this flowKey is deactivated in the same "
                    + "transaction the new one activates in, so a reader never observes zero or "
                    + "two active versions. A malformed or invalid document (unknown block type, "
                    + "a transition naming a state that does not exist, a cycle with no exit) is "
                    + "refused whole, with every problem named.")
    ResponseEntity<FlowDocumentResponse> author(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String flowKey,
            @Valid @RequestBody AuthorFlowDocumentRequest body) {
        try {
            FlowDocumentView published = flows.author(
                    tenantId,
                    brandId,
                    flowKey,
                    body.documentYaml(),
                    body.description(),
                    body.activate(),
                    currentActor.get().subject(),
                    body.reason());
            return ResponseEntity.status(HttpStatus.CREATED).body(FlowDocumentResponse.of(published));
        } catch (FlowDocumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", invalid.problems()));
        } catch (IllegalArgumentException mismatchedKey) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, mismatchedKey.getMessage());
        }
    }

    /**
     * @param documentYaml the flow document, verbatim — its own {@code flowKey} field must match the path
     * @param description a short human label for the authoring console, or null
     * @param activate whether this version becomes the active one immediately
     * @param reason why this version is being published (ADR 0027)
     */
    public record AuthorFlowDocumentRequest(
            @NotBlank String documentYaml,
            @Nullable @Size(max = 500) String description,
            boolean activate,
            @NotBlank @Size(max = 1000) String reason) {}

    public record FlowDocumentResponse(
            UUID id,
            String flowKey,
            int version,
            String documentYaml,
            boolean active,
            @Nullable String description,
            String authoredBy,
            Instant createdAt) {

        static FlowDocumentResponse of(FlowDocumentView row) {
            return new FlowDocumentResponse(
                    row.id(),
                    row.flowKey(),
                    row.version(),
                    row.documentYaml(),
                    row.active(),
                    row.description(),
                    row.authoredBy(),
                    row.createdAt());
        }
    }
}
