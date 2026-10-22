package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.courier.application.CourierRuleSimulator;
import uz.horecaos.platform.courier.application.CourierRuleSimulator.Candidate;
import uz.horecaos.platform.courier.application.CourierRuleSimulator.Hit;
import uz.horecaos.platform.courier.application.CourierRuleSimulator.Simulation;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * «Who would this rule have hit last week, and for how much» (couriers.md §11, ADR 0178).
 *
 * <p>A {@code POST} only because its body is a candidate rule that does not exist yet. It writes nothing -- no
 * ledger entry, no approval, no audit fact about a courier -- which is why it needs no {@code Idempotency-Key},
 * and it declares the capability of authoring the rule it previews, not a weaker one: the preview names
 * couriers by display reference and the sum they would lose.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(
        name = "Courier rule simulation",
        description = "Preview a bonus or penalty rule against recent shifts and periods")
public class OperationsCourierRuleSimulationController {

    private final CourierRuleSimulator simulator;

    public OperationsCourierRuleSimulationController(CourierRuleSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/adjustment-rule-simulations")
    @RequiresCapability(Capability.COURIER_ADJUSTMENT_REASON_MANAGE)
    @Operation(
            operationId = "simulateCourierAdjustmentRule",
            summary = "Who a bonus or penalty rule would have hit in the last days, and for how much",
            description = "Replays the very arithmetic AdjustmentRuleEvaluator posts from over shifts that "
                    + "closed (window SHIFT) or settlement periods that ended (window SETTLEMENT_PERIOD) in "
                    + "the last lookbackDays, and writes nothing. windowsReplayed says how many were looked at, "
                    + "so «nobody» can be told from «nothing to look at». An open period is replayed as it "
                    + "stands and flagged. locationId narrows shifts only: a settlement period has no "
                    + "location. A basis nothing reads (ORDER_UNDELIVERED, ORDER_DAMAGED) is 422 "
                    + "BASIS_HAS_NO_EVALUATOR_READER, and CASH_VARIANCE on a shift window is 400.")
    public ResponseEntity<SimulationResponse> simulate(
            @PathVariable UUID tenantId, @Valid @RequestBody SimulateRuleRequest body) {

        Simulation result = simulator.simulate(
                tenantId,
                new Candidate(
                        body.outcomeBasis(),
                        body.ruleComparator(),
                        body.ruleThreshold(),
                        body.ruleWindow(),
                        body.ruleAmountMinor(),
                        body.ruleCurrency()),
                body.lookbackDays() == null ? 7 : body.lookbackDays(),
                body.locationId());
        return ResponseEntity.ok(SimulationResponse.of(result));
    }

    /** The six fields the registry stores, as the rule form has them. */
    record SimulateRuleRequest(
            @NotBlank String outcomeBasis,
            @NotBlank String ruleComparator,
            @NotNull Long ruleThreshold,
            @NotBlank String ruleWindow,
            @NotNull Long ruleAmountMinor,
            @NotBlank @Size(min = 3, max = 3) String ruleCurrency,
            @Nullable Integer lookbackDays,
            @Nullable UUID locationId) {}

    record SimulationResponse(
            int lookbackDays,
            String window,
            int windowsReplayed,
            int couriersHit,
            long totalAmountMinor,
            String currency,
            boolean truncated,
            boolean includesOpenPeriods,
            List<HitResponse> hits) {

        static SimulationResponse of(Simulation result) {
            return new SimulationResponse(
                    result.lookbackDays(),
                    result.window(),
                    result.windowsReplayed(),
                    result.couriersHit(),
                    result.totalAmountMinor(),
                    result.currency(),
                    result.truncated(),
                    result.includesOpenPeriods(),
                    result.hits().stream().map(HitResponse::of).toList());
        }
    }

    record HitResponse(
            UUID courierId,
            @Nullable String courierReference,
            UUID windowId,
            @Nullable Instant windowEnd,
            long value,
            long amountMinor,
            String currency,
            String windowStatus,
            boolean windowStillOpen) {

        static HitResponse of(Hit hit) {
            return new HitResponse(
                    hit.courierId(),
                    hit.courierReference(),
                    hit.windowId(),
                    hit.windowEnd(),
                    hit.value(),
                    hit.amountMinor(),
                    hit.currency(),
                    hit.windowStatus(),
                    hit.windowStillOpen());
        }
    }
}
