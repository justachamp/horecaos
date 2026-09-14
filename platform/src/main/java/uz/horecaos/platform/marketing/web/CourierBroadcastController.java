package uz.horecaos.platform.marketing.web;

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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.application.CourierBroadcastService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Operations §6.4b: a dispatcher's own operational SMS blast to couriers —
 * never a customer campaign (V0307's own doc explains why this is not
 * {@link OperationsMarketingController}'s {@code /campaigns}).
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

    @PostMapping
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a courier broadcast",
            description = "Targets every active courier of the tenant, or one courier group. Drafted, "
                    + "not sent: a separate call opens the send, the same two-step shape as authoring "
                    + "and launching a campaign, so a mistyped message can be caught before anyone reads it.")
    public ResponseEntity<CourierBroadcastResponse> draft(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody DraftRequest body) {

        UUID id =
                broadcasts.draft(tenantId, brandId, body.targetKind(), body.targetGroupId(), body.message(), actorId());
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

    @PostMapping("/{broadcastId}/sends")
    @RequiresCapability(value = Capability.COURIER_BROADCAST_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Send a drafted broadcast",
            description = "Resolves the target to a recipient count and opens the send. Refused, "
                    + "visibly, when SMS has no wired ADR 0020 delivery path — a dispatcher who tried "
                    + "to send sees why, rather than believing a message went out that never did.")
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
            @NotBlank @Size(max = 480) String message) {}

    public record CourierBroadcastResponse(
            UUID broadcastId,
            String channel,
            String targetKind,
            @Nullable UUID targetGroupId,
            String message,
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
                    row.status(),
                    row.recipientCount(),
                    row.refusalReason(),
                    row.createdBy(),
                    row.createdAt(),
                    row.sentAt());
        }
    }
}
