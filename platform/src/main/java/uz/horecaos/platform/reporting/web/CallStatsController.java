package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0064: telephony statistics, through the same {@code REPORTING_READ}
 * capability every other reporting surface already uses — see {@link
 * CallStatsQueryService}'s own doc for why this is a dedicated read rather
 * than a signed metric.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/voice/call-stats")
@Tag(
        name = "Voice call stats",
        description = "Offered/answered/missed/transferred, answer speed and call-to-order conversion, "
                + "per hour, per operator (ADR 0064, ADR 0175)")
public class CallStatsController {

    /** The widest range the coverage read answers; it counts closed days, so a year is a lot already. */
    static final int COVERAGE_MAX_DAYS = 366;

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
                    + "the close runs, not in real time. ADR 0175 formulas, applied where the figures are "
                    + "read and never stored as ratios: answer speed = sum(ringSecondsTotal) / "
                    + "sum(ringSampleCount), ring time being the seconds from a call's OFFERED event to its "
                    + "first ANSWERED event; conversion = sum(convertedCount) / sum(answeredCallCount), "
                    + "an answered call converting when at least one order, in any status, was placed from "
                    + "it; coverage = sum(ringSampleCount) / sum(answeredCallCount). Conversion is a lower "
                    + "bound: a provenance written after its day closed is counted by a rebuild, not by the "
                    + "close. A null count means the row was closed before the measure existed (not "
                    + "measured), which is never zero. talkDurationSeconds is the call's length, first "
                    + "event to end, ring and queue time included; the name is historic.")
    public ResponseEntity<List<CallHourResponse>> forDate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.ok(callStats.forDate(tenantId, locationId, businessDate).stream()
                .map(CallHourResponse::of)
                .toList());
    }

    @GetMapping("/coverage")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Whether this branch has telephony, and what its closed call history covers",
            description = "ADR 0175, Decision 7. connection is CONNECTED when an ACTIVE voice installation "
                    + "has an ACTIVE binding at the branch and NOT_CONNECTED otherwise. The Telephony tab is "
                    + "shown when the branch is CONNECTED or closedDaysWithCalls is above zero, and is "
                    + "otherwise left out. closedDaysWithCalls counts closed business days in [from, to] that "
                    + "hold a call fact; firstCallEventAt and lastCallEventAt are the earliest and latest "
                    + "call event of the branch in that range; nextCloseAt is when the business day open now "
                    + "will close and its calls will first appear. No caller number, customer or order "
                    + "identifier is returned.")
    public ResponseEntity<CallCoverageResponse> coverage(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "to must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) >= COVERAGE_MAX_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The range may span at most " + COVERAGE_MAX_DAYS + " days");
        }
        return ResponseEntity.ok(CallCoverageResponse.of(callStats.coverage(tenantId, locationId, from, to)));
    }

    public record CallHourResponse(
            int hourOfDay,
            String operatorPrincipalId,
            int offeredCount,
            int answeredCount,
            int missedCount,
            int transferredCount,
            long talkDurationSeconds,
            @Nullable Integer answeredCallCount,
            @Nullable Long ringSecondsTotal,
            @Nullable Integer ringSampleCount,
            @Nullable Integer ringSecondsMax,
            @Nullable Integer convertedCount) {

        static CallHourResponse of(CallHourFact fact) {
            return new CallHourResponse(
                    fact.hourOfDay(),
                    fact.operatorPrincipalId(),
                    fact.offeredCount(),
                    fact.answeredCount(),
                    fact.missedCount(),
                    fact.transferredCount(),
                    fact.talkDurationSeconds(),
                    fact.answeredCallCount(),
                    fact.ringSecondsTotal(),
                    fact.ringSampleCount(),
                    fact.ringSecondsMax(),
                    fact.convertedCount());
        }
    }

    /** ADR 0175: {@code connection} is {@code CONNECTED} or {@code NOT_CONNECTED}. */
    public record CallCoverageResponse(
            String connection,
            int activeInstallationCount,
            @Nullable Instant firstCallEventAt,
            @Nullable Instant lastCallEventAt,
            int closedDaysWithCalls,
            Instant nextCloseAt) {

        static CallCoverageResponse of(CallStatsQueryService.Coverage coverage) {
            return new CallCoverageResponse(
                    coverage.connected() ? "CONNECTED" : "NOT_CONNECTED",
                    coverage.activeInstallationCount(),
                    coverage.firstCallEventAt(),
                    coverage.lastCallEventAt(),
                    coverage.closedDaysWithCalls(),
                    coverage.nextCloseAt());
        }
    }
}
