package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.application.CallStatsQueryService;
import uz.horecaos.platform.reporting.application.ReportingFacts.CallHourFact;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0064: telephony statistics, through the same {@code REPORTING_READ}
 * capability every other reporting surface already uses — see {@link
 * CallStatsQueryService}'s own doc for why this is a dedicated read rather
 * than a signed metric.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/voice/call-stats")
@Tag(name = "Voice call stats", description = "Offered/answered/missed/transferred, per hour, per operator (ADR 0064)")
public class CallStatsController {

    private final CallStatsQueryService callStats;

    public CallStatsController(CallStatsQueryService callStats) {
        this.callStats = callStats;
    }

    @GetMapping
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "This branch's call activity for one business date, by hour and operator",
            description = "Written by the same day-close pipeline every other ADR 0043 fact uses. "
                    + "Empty until the day closes — a live shift's calls appear here the next time "
                    + "the close runs, not in real time.")
    public ResponseEntity<List<CallHourResponse>> forDate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.ok(callStats.forDate(tenantId, locationId, businessDate).stream()
                .map(CallHourResponse::of)
                .toList());
    }

    public record CallHourResponse(
            int hourOfDay,
            String operatorPrincipalId,
            int offeredCount,
            int answeredCount,
            int missedCount,
            int transferredCount,
            long talkDurationSeconds) {

        static CallHourResponse of(CallHourFact fact) {
            return new CallHourResponse(
                    fact.hourOfDay(),
                    fact.operatorPrincipalId(),
                    fact.offeredCount(),
                    fact.answeredCount(),
                    fact.missedCount(),
                    fact.transferredCount(),
                    fact.talkDurationSeconds());
        }
    }
}
