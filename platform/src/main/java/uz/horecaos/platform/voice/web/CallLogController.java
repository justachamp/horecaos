package uz.horecaos.platform.voice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.voice.application.CallLogQueryService;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.CallLogRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/** The branch's recent call list (frontend information architecture Sec 1.6, "call list"). */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/voice/call-log")
@Tag(name = "Voice call log", description = "Recent call events at a branch (ADR 0064)")
public class CallLogController {

    private final CallLogQueryService callLog;

    public CallLogController(CallLogQueryService callLog) {
        this.callLog = callLog;
    }

    @GetMapping
    @RequiresCapability(value = Capability.VOICE_CALL_LOG_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "This branch's most recent call events, newest first")
    public ResponseEntity<List<CallLogEntryResponse>> recent(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(callLog.recent(tenantId, locationId).stream()
                .map(CallLogEntryResponse::of)
                .toList());
    }

    public record CallLogEntryResponse(
            UUID callEventId,
            String providerCallId,
            String eventType,
            String direction,
            @Nullable String lineDid,
            @Nullable String operatorPrincipalId,
            @Nullable Integer durationSeconds,
            Instant occurredAt) {

        static CallLogEntryResponse of(CallLogRow row) {
            return new CallLogEntryResponse(
                    row.id(),
                    row.providerCallId(),
                    row.eventType(),
                    row.direction(),
                    row.lineDid(),
                    row.operatorPrincipalId(),
                    row.durationSeconds(),
                    row.occurredAt());
        }
    }
}
