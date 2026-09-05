package uz.horecaos.platform.voice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.voice.application.OperatorPresenceService;
import uz.horecaos.platform.voice.domain.OperatorPresenceState;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.PresenceRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Operator presence, self-service (ADR 0064). The operator's identity always
 * comes from the authenticated token; nothing here accepts an operator id in
 * the body, the same discipline {@code CustomerController.resolve} uses.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/voice/presence")
@Tag(name = "Voice presence", description = "ONLINE/PAUSED/WRAP_UP/OFFLINE, channel-neutral (ADR 0064)")
public class OperatorPresenceController {

    private final OperatorPresenceService presence;
    private final CurrentActor currentActor;

    public OperatorPresenceController(OperatorPresenceService presence, CurrentActor currentActor) {
        this.presence = presence;
        this.currentActor = currentActor;
    }

    @PutMapping
    @RequiresCapability(value = Capability.VOICE_PRESENCE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Set your own presence at this branch",
            description = "A reason is required exactly on PAUSED. There is no separate open/close "
                    + "step: the first ONLINE a new hire ever sends is exactly as ordinary as their "
                    + "hundredth.")
    public ResponseEntity<PresenceResponse> setPresence(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody SetPresenceRequest body) {
        String operatorPrincipalId = currentActor.get().subject();
        OperatorPresenceState state = parseState(body.state());
        presence.setPresence(
                tenantId,
                brandId,
                locationId,
                operatorPrincipalId,
                state,
                body.reason(),
                ActorRef.user(operatorPrincipalId, null),
                Capability.VOICE_PRESENCE_MANAGE.code(),
                UUID.randomUUID().toString());
        return presence.mine(tenantId, locationId, operatorPrincipalId)
                .map(row -> ResponseEntity.ok(PresenceResponse.of(row)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_CONFLICT, "Presence could not be read back"));
    }

    @GetMapping("/me")
    @RequiresCapability(value = Capability.VOICE_PRESENCE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(summary = "Your own current presence at this branch")
    public ResponseEntity<PresenceResponse> mine(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        String operatorPrincipalId = currentActor.get().subject();
        return presence.mine(tenantId, locationId, operatorPrincipalId)
                .map(row -> ResponseEntity.ok(PresenceResponse.of(row)))
                .orElseGet(() -> ResponseEntity.ok(PresenceResponse.offlineDefault(operatorPrincipalId)));
    }

    @GetMapping
    @RequiresCapability(value = Capability.VOICE_PRESENCE_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every operator's presence at this branch",
            description = "The supervisor roster view, and the same read a future routing adapter "
                    + "uses to skip a paused operator (ADR 0064).")
    public ResponseEntity<List<PresenceResponse>> roster(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(presence.roster(tenantId, locationId).stream()
                .map(PresenceResponse::of)
                .toList());
    }

    private static OperatorPresenceState parseState(String state) {
        try {
            return OperatorPresenceState.valueOf(state);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown presence state: " + state);
        }
    }

    public record SetPresenceRequest(
            @NotNull String state,
            @Size(max = 255) @Nullable String reason) {}

    public record PresenceResponse(
            String operatorPrincipalId,
            String state,
            @Nullable String reason,
            Instant changedAt,
            int version) {

        static PresenceResponse of(PresenceRow row) {
            return new PresenceResponse(
                    row.operatorPrincipalId(), row.state(), row.reason(), row.changedAt(), row.version());
        }

        static PresenceResponse offlineDefault(String operatorPrincipalId) {
            return new PresenceResponse(
                    operatorPrincipalId, OperatorPresenceState.OFFLINE.name(), null, Instant.EPOCH, 0);
        }
    }
}
