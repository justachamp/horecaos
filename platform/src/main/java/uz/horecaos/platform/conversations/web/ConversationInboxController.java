package uz.horecaos.platform.conversations.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.conversations.application.ConversationInboxService;
import uz.horecaos.platform.conversations.application.ConversationMessageView;
import uz.horecaos.platform.conversations.application.ConversationSummaryView;
import uz.horecaos.platform.conversations.application.ConversationView;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The operator inbox (ADR 0059 stage 2): a brand's conversations, a
 * conversation's decrypted history, and the four actions an operator takes
 * on one — reply, take over, return to the flow, and close.
 *
 * <p>Every endpoint here is behind {@code conversation.inbox.manage} at
 * {@code BRAND} scope — see that capability's own doc for exactly which
 * tenant roles hold it and why. Polling, not push: ADR 0059's alternatives
 * table deferred a real-time gateway, so the operations client refreshes
 * this the same way {@code OperationsOrderController}'s board does.
 *
 * <p>Mirrors {@code OperationsOrderController}'s shape deliberately: a thin
 * summary for the list, a full detail with its own {@code ETag}, and every
 * mutation guarded by {@code If-Match} against that version — the same ADR
 * 0031 discipline, applied to a conversation's aggregate {@code version}
 * instead of an order's.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/conversations")
@Tag(name = "Operator inbox", description = "Conversation list, history, and takeover/return/close actions")
public class ConversationInboxController {

    private final ConversationInboxService inbox;
    private final CurrentActor currentActor;

    public ConversationInboxController(ConversationInboxService inbox, CurrentActor currentActor) {
        this.inbox = inbox;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CONVERSATION_INBOX_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "A brand's conversations, needs-attention first",
            description = "HANDED_TO_OPERATOR conversations first, then a FLOW_ACTIVE conversation "
                    + "whose newest message nobody has answered yet, then everything else by "
                    + "last activity. No message bodies here — open a conversation for its "
                    + "decrypted history.")
    public List<ConversationSummaryResponse> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(defaultValue = "100") @jakarta.validation.constraints.Max(500) int limit) {
        return inbox.list(tenantId, brandId, limit).stream()
                .map(ConversationSummaryResponse::of)
                .toList();
    }

    @GetMapping("/{conversationId}")
    @RequiresCapability(value = Capability.CONVERSATION_INBOX_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "One conversation's full decrypted history",
            description = "The envelope-decrypted free text is this screen's whole purpose (ADR "
                    + "0059). The first time a given operator opens a given conversation this "
                    + "way, it writes a conversation.history.read audit fact (ADR 0027); a later "
                    + "poll of an already-open thread by the same operator does not repeat it.")
    public ResponseEntity<ConversationDetailResponse> detail(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID conversationId) {
        ConversationInboxService.ConversationHistory history = inbox.history(
                tenantId, brandId, conversationId, currentActor.get().subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(history.conversation().version()))
                .body(ConversationDetailResponse.of(history));
    }

    @PostMapping("/{conversationId}/replies")
    @RequiresCapability(value = Capability.CONVERSATION_INBOX_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Send a reply as the operator currently holding this conversation",
            description = "Only a HANDED_TO_OPERATOR conversation may be replied to — take it over "
                    + "first if the flow engine is still answering it. Sent through the same "
                    + "channel gateway the flow engine uses, recorded with an OPERATOR direction "
                    + "and the acting principal, and audited.")
    public ResponseEntity<ConversationMessageResponse> reply(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendReplyRequest body) {
        ConversationMessageView sent = inbox.reply(
                tenantId, brandId, conversationId, currentActor.get().subject(), body.body());
        return ResponseEntity.ok(ConversationMessageResponse.of(sent));
    }

    @PostMapping("/{conversationId}/takeover")
    @RequiresCapability(value = Capability.CONVERSATION_INBOX_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Take a FLOW_ACTIVE conversation over from the flow engine",
            description = "FLOW_ACTIVE -> HANDED_TO_OPERATOR by explicit operator action, before "
                    + "the flow document itself ever reaches an operator-handoff block. The "
                    + "engine stops answering this conversation from this call onward. Requires "
                    + "If-Match against the conversation's current version.")
    public ResponseEntity<ConversationResponse> takeover(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) @Nullable @Size(max = 500) String reason,
            HttpServletRequest request) {
        long expectedVersion = AggregateVersion.requireIfMatch(request);
        ConversationView updated = inbox.takeover(
                tenantId,
                brandId,
                conversationId,
                expectedVersion,
                currentActor.get().subject(),
                reason);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(ConversationResponse.of(updated));
    }

    @PostMapping("/{conversationId}/return-to-flow")
    @RequiresCapability(value = Capability.CONVERSATION_INBOX_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Return a HANDED_TO_OPERATOR conversation to the flow engine",
            description = "Resumes at the parked handoff block's own next state when the flow "
                    + "document declared one, goes idle rather than replaying the handoff when it "
                    + "did not, or simply resumes waiting for whatever a mid-flow takeover left "
                    + "the customer answering. Requires If-Match against the conversation's "
                    + "current version.")
    public ResponseEntity<ConversationResponse> returnToFlow(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID conversationId,
            HttpServletRequest request) {
        long expectedVersion = AggregateVersion.requireIfMatch(request);
        ConversationView updated = inbox.returnToFlow(
                tenantId,
                brandId,
                conversationId,
                expectedVersion,
                currentActor.get().subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(ConversationResponse.of(updated));
    }

    @PostMapping("/{conversationId}/close")
    @RequiresCapability(value = Capability.CONVERSATION_INBOX_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Close a conversation",
            description = "Any non-CLOSED state may close. A new inbound message afterward reopens "
                    + "it to HANDED_TO_OPERATOR rather than being answered with silence. Requires "
                    + "If-Match against the conversation's current version.")
    public ResponseEntity<ConversationResponse> close(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) @Nullable @Size(max = 500) String reason,
            HttpServletRequest request) {
        long expectedVersion = AggregateVersion.requireIfMatch(request);
        ConversationView updated = inbox.close(
                tenantId,
                brandId,
                conversationId,
                expectedVersion,
                currentActor.get().subject(),
                reason);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(ConversationResponse.of(updated));
    }

    // ------------------------------------------------------------------ DTOs

    public record SendReplyRequest(
            @NotBlank @Size(max = 4000) String body) {}

    public record ConversationSummaryResponse(
            UUID conversationId,
            String channel,
            @Nullable UUID customerAccountId,
            String state,
            boolean needsReply,
            Instant lastActivityAt) {

        static ConversationSummaryResponse of(ConversationSummaryView view) {
            return new ConversationSummaryResponse(
                    view.id(),
                    view.channel(),
                    view.customerAccountId(),
                    view.state(),
                    view.needsReply(),
                    view.lastActivityAt());
        }
    }

    public record ConversationResponse(
            UUID conversationId,
            UUID brandId,
            String channel,
            @Nullable UUID customerAccountId,
            String state,
            @Nullable String assignedTo,
            Instant updatedAt,
            long version) {

        static ConversationResponse of(ConversationView view) {
            return new ConversationResponse(
                    view.id(),
                    view.brandId(),
                    view.channel(),
                    view.customerAccountId(),
                    view.state(),
                    view.assignedTo(),
                    view.updatedAt(),
                    view.version());
        }
    }

    public record ConversationMessageResponse(
            UUID messageId,
            String direction,
            @Nullable String blockId,
            @Nullable String actorPrincipalId,
            String body,
            Instant occurredAt) {

        static ConversationMessageResponse of(ConversationMessageView view) {
            return new ConversationMessageResponse(
                    view.id(),
                    view.direction(),
                    view.blockId(),
                    view.actorPrincipalId(),
                    view.body(),
                    view.occurredAt());
        }
    }

    public record ConversationDetailResponse(
            ConversationResponse conversation, List<ConversationMessageResponse> messages) {

        static ConversationDetailResponse of(ConversationInboxService.ConversationHistory history) {
            return new ConversationDetailResponse(
                    ConversationResponse.of(history.conversation()),
                    history.messages().stream()
                            .map(ConversationMessageResponse::of)
                            .toList());
        }
    }
}
