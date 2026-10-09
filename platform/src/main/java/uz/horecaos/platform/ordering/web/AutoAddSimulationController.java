package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.AutoAddSimulationService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The console's simulator for cart-injection rules (ADR 0158): a non-mutating {@code POST} whose body is a
 * hypothetical basket. It lives with the ordering module, which owns the evaluator, and answers under the
 * catalog authoring path of the control-plane document group (ADR 0057) beside the rules it previews.
 *
 * <p>{@code catalog.read} at brand scope: it writes nothing and reveals only what the rules screen already
 * lists. No idempotency key is asked for, because nothing is created.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/auto-add-rules/simulations")
@Tag(name = "Auto-add rules", description = "Cart injection: packaging, cutlery and gift lines added by a rule")
public class AutoAddSimulationController {

    private final AutoAddSimulationService simulations;

    public AutoAddSimulationController(AutoAddSimulationService simulations) {
        this.simulations = simulations;
    }

    @PostMapping
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Run a hypothetical basket through the brand's auto-add rules",
            description = "Evaluates draft and active rules with the evaluator the cart runs, and answers per rule "
                    + "whether it would INJECT a line, OFFER one, or be SKIPPED, with the variant and the quantity. "
                    + "Nothing is written. The only check run on a rule's variant is that it is active: stock, sale "
                    + "windows and prices belong to a real cart at a real branch.")
    public SimulationResponse simulate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SimulationRequest request) {
        var result = simulations.simulate(
                tenantId,
                brandId,
                new AutoAddSimulationService.Simulation(
                        request.fulfillmentMode(),
                        request.channelSystemType(),
                        request.locationId(),
                        request.lines().stream()
                                .map(line ->
                                        new AutoAddSimulationService.SimulatedLine(line.variantId(), line.quantity()))
                                .toList(),
                        request.declinedRuleIds() == null
                                ? java.util.Set.of()
                                : new LinkedHashSet<>(request.declinedRuleIds())));
        return new SimulationResponse(
                result.units(),
                result.portions(),
                result.outcomes().stream()
                        .map(outcome -> new OutcomeResponse(
                                outcome.ruleId(),
                                outcome.ruleCode(),
                                outcome.ruleStatus(),
                                outcome.outcome(),
                                outcome.variantId(),
                                outcome.quantity(),
                                outcome.reason()))
                        .toList());
    }

    public record SimulatedLineBody(
            @NotNull UUID variantId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("999")
            BigDecimal quantity) {}

    public record SimulationRequest(
            @NotBlank @Pattern(regexp = "DELIVERY|PICKUP|DINE_IN")
            String fulfillmentMode,

            @NotBlank @Pattern(regexp = "WEB|IOS|ANDROID|TELEGRAM|KIOSK|QR_TABLE|CALL_CENTRE")
            String channelSystemType,

            @Nullable UUID locationId,
            @NotEmpty @Size(max = 100) List<@Valid SimulatedLineBody> lines,
            @Nullable @Size(max = 50) List<UUID> declinedRuleIds) {}

    public record OutcomeResponse(
            UUID ruleId,
            String ruleCode,
            String ruleStatus,
            String outcome,
            @Nullable UUID variantId,
            @Nullable BigDecimal quantity,
            @Nullable String reason) {}

    public record SimulationResponse(BigDecimal units, BigDecimal portions, List<OutcomeResponse> outcomes) {}
}
