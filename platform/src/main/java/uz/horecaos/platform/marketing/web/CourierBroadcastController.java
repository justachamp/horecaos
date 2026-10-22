package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.application.CourierBroadcastService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Operations §6.4b: a dispatcher's own operational SMS to couriers — never a customer
 * campaign (V0307's own doc explains why this is not {@link OperationsMarketingController}'s
 * {@code /campaigns}).
 *
 * <p>ADR 0182, D15: a broadcast names an approved wording and gives its variables, and a send
 * answers per courier. {@code readiness} says, per brand and before anything is drafted, whether a
 * courier SMS can leave and if not why; {@code recipients} says what became of each courier's message.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/courier-broadcasts")
@Tag(name = "Courier broadcasts", description = "A dispatcher's operational SMS blast to couriers (operations 6.4b)")
public class CourierBroadcastController {

    private final CourierBroadcastService broadcasts;
    private final CurrentActor currentActor;

    public CourierBroadcastController(CourierBroadcastService broadcasts, CurrentActor currentActor) {
        this.broadcasts = broadcasts;
        this.currentActor = currentActor;
    }

    @GetMapping("/readiness")
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "Whether a courier SMS can leave for this brand, why not, and the wordings a dispatcher may name",
            description = "Read before drafting (ADR 0182). isWired is false with a stable code when the brand "
                    + "has no SMS account (NO_PROVIDER_BINDING, INSTALLATION_INACTIVE, SMS_ACCOUNT_MISCONFIGURED), "
                    + "when its account has not been cleared to carry courier traffic (SMS_PURPOSE_NOT_PERMITTED), "
                    + "or when the owner has not recorded that a courier's own number may be used for a "
                    + "dispatcher's message (COURIER_CONTACTS_NOT_WIRED). templates lists the active wordings "
                    + "of the class COURIER_DISPATCH with the variables each declares and whether the SMS "
                    + "gateway has yet to approve it.")
    public ResponseEntity<ReadinessResponse> readiness(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        CourierBroadcastService.Readiness readiness = broadcasts.readiness(tenantId, brandId);
        return ResponseEntity.ok(new ReadinessResponse(
                readiness.isWired(),
                readiness.reason(),
                readiness.templates().stream()
                        .map(template -> new TemplateResponse(
                                template.templateKey(),
                                template.variables(),
                                template.bodiesByLocale(),
                                template.awaitingGateway()))
                        .toList()));
    }

    @PostMapping
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a courier broadcast",
            description = "Targets every active courier of the tenant, or one courier group, and names an "
                    + "approved wording (an active SMS template of the class COURIER_DISPATCH) with a value "
                    + "for each variable it declares. Free text is not accepted: the SMS gateway approves "
                    + "wordings in advance (ADR 0091). Drafted, not sent: a separate call opens the send, "
                    + "the same two-step shape as authoring and launching a campaign, so a mistyped value "
                    + "can be caught before anyone reads it.")
    public ResponseEntity<CourierBroadcastResponse> draft(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody DraftRequest body) {

        UUID id = broadcasts.draft(
                tenantId,
                brandId,
                body.targetKind(),
                body.targetGroupId(),
                body.templateKey(),
                body.variables(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CourierBroadcastResponse.of(broadcasts.require(tenantId, id)));
    }

    @GetMapping
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND)
    @Operation(summary = "Every courier broadcast this brand's dispatchers have drafted or sent, newest first")
    public ResponseEntity<List<CourierBroadcastResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(broadcasts.list(tenantId, brandId).stream()
                .map(CourierBroadcastResponse::of)
                .toList());
    }

    @GetMapping("/{broadcastId}/recipients")
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "What became of each courier a broadcast targeted",
            description = "One row per targeted courier, by their non-personal reference (K-014): the state of "
                    + "the message the gateway reported (PENDING, HANDED_TO_OPERATOR, DELIVERED, FAILED, "
                    + "REJECTED, WITHHELD...) read from the message's own evidence, or NO_CONTACT for a "
                    + "courier the identity provider holds no usable number for. reason is a bounded code, "
                    + "never a number or a gateway's text.")
    public ResponseEntity<List<CourierResultResponse>> recipients(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID broadcastId) {

        CourierBroadcastRow broadcast = broadcasts.require(tenantId, broadcastId);
        if (!broadcast.brandId().equals(brandId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No courier broadcast " + broadcastId + " belongs to this brand");
        }
        return ResponseEntity.ok(broadcasts.results(tenantId, broadcastId).stream()
                .map(result -> new CourierResultResponse(
                        result.courierReference(),
                        result.state(),
                        result.reason(),
                        result.receiptState(),
                        result.segmentsBilled()))
                .toList());
    }

    @PostMapping("/{broadcastId}/sends")
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Send a drafted broadcast",
            description = "Asks the identity provider which targeted couriers have a number, creates one ADR 0020 "
                    + "message for each that does, and records the rest as NO_CONTACT. Refused, visibly, "
                    + "with the stable code in the sentence, when the brand's gateway account is not cleared "
                    + "for courier traffic (SMS_PURPOSE_NOT_PERMITTED), when the owner has not recorded that a "
                    + "courier's number may be used (COURIER_CONTACTS_NOT_WIRED), when the wording is missing "
                    + "or awaits the gateway's approval, when no courier is active or none has a number. "
                    + "A 503 (COURIER_CONTACTS_UNAVAILABLE) means the identity provider could not be asked: "
                    + "nothing was sent and the broadcast is still a draft.")
    public ResponseEntity<CourierBroadcastResponse> send(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID broadcastId) {

        CourierBroadcastRow broadcast = broadcasts.require(tenantId, broadcastId);
        if (!broadcast.brandId().equals(brandId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No courier broadcast " + broadcastId + " belongs to this brand");
        }
        return ResponseEntity.ok(CourierBroadcastResponse.of(broadcasts.send(tenantId, broadcastId, actorId())));
    }

    private UUID actorId() {
        String subject = currentActor.get().subject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This principal has no identifier that can be recorded as an author");
        }
    }

    public record DraftRequest(
            @NotBlank String targetKind,
            @Nullable UUID targetGroupId,
            @NotBlank @Size(max = 120) String templateKey,
            @Nullable Map<String, @Size(max = 80) String> variables) {}

    public record CourierBroadcastResponse(
            UUID broadcastId,
            String channel,
            String targetKind,
            @Nullable UUID targetGroupId,
            @Nullable String message,
            @Nullable String templateKey,
            Map<String, String> variables,
            String status,
            int recipientCount,
            @Nullable String refusalReason,
            UUID createdBy,
            Instant createdAt,
            @Nullable Instant sentAt) {

        static CourierBroadcastResponse of(CourierBroadcastRow row) {
            return new CourierBroadcastResponse(
                    row.id(),
                    row.channel(),
                    row.targetKind(),
                    row.targetGroupId(),
                    row.message(),
                    row.templateKey(),
                    row.variables(),
                    row.status(),
                    row.recipientCount(),
                    row.refusalReason(),
                    row.createdBy(),
                    row.createdAt(),
                    row.sentAt());
        }
    }

    /** @param reason a stable code when a courier SMS cannot leave for this brand, null when it can */
    public record ReadinessResponse(
            boolean isWired, @Nullable String reason, List<TemplateResponse> templates) {}

    /**
     * @param variables the variables the wording uses, each needing a value
     * @param bodiesByLocale the wording per language tag, placeholders unexpanded
     * @param awaitingGateway whether the SMS gateway has yet to approve the wording
     */
    public record TemplateResponse(
            String templateKey, List<String> variables, Map<String, String> bodiesByLocale, boolean awaitingGateway) {}

    /** @param courierReference the courier's non-personal handle, e.g. K-014 */
    public record CourierResultResponse(
            String courierReference,
            String state,
            @Nullable String reason,
            @Nullable String receiptState,
            @Nullable Integer segmentsBilled) {}
}
