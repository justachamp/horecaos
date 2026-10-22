package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.MarketplaceRulesetBindingService;
import uz.horecaos.platform.catalog.application.MarketplaceRulesetBindingService.Applied;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Names the marketplace ruleset a MARKETPLACE binding is checked against (ADR 0138), on the surface the
 * installation screen lives on.
 *
 * <p>In {@code catalog} rather than {@code integration} because the registry the codes come from is catalog's
 * ({@code MarketplaceRulesets}) and catalog already reads the binding's column; an integration controller
 * reaching for it would make the two modules cyclic. The capability is the installation screen's own:
 * {@link Capability#INTEGRATION_INSTALLATION_MANAGE}, held by the tenant owner and admin.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/integrations")
@Tag(name = "Marketplace rulesets", description = "Which content rules a marketplace binding is checked against")
public class MarketplaceRulesetController {

    private final MarketplaceRulesetBindingService rulesets;
    private final CurrentActor currentActor;

    public MarketplaceRulesetController(MarketplaceRulesetBindingService rulesets, CurrentActor currentActor) {
        this.rulesets = rulesets;
        this.currentActor = currentActor;
    }

    @GetMapping("/marketplace-rulesets")
    @RequiresCapability(Capability.INTEGRATION_INSTALLATION_MANAGE)
    @Operation(
            summary = "The marketplace rulesets a binding may name",
            description = "ADR 0138. The closed, code-owned set. Empty until a per-marketplace ruleset is authored, "
                    + "which is the decision rather than a gap: the screen then says no ruleset is authored and "
                    + "offers only to clear a code.")
    public KnownRulesetsResponse known(@PathVariable UUID tenantId) {
        return new KnownRulesetsResponse(rulesets.knownCodes());
    }

    @PutMapping("/{installationId}/bindings/{bindingId}/marketplace-ruleset")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Name, or clear, the marketplace ruleset of a MARKETPLACE binding",
            description = "ADR 0138. code is one of the known rulesets, or null to clear it so the universal rules "
                    + "alone apply; any other code is refused 400 VALIDATION_FAILED with the known set. Under If-Match, the "
                    + "binding's version the editor read (it is on the binding list): another editor's change in "
                    + "between answers 409 STALE_VERSION, and naming the code already stored changes nothing and "
                    + "keeps the version. The response's ETag is the new version.")
    public ResponseEntity<RulesetResponse> set(
            @PathVariable UUID tenantId,
            @PathVariable UUID installationId,
            @PathVariable UUID bindingId,
            HttpServletRequest http,
            @Valid @RequestBody SetRulesetRequest request) {
        long expected = AggregateVersion.requireIfMatch(http);
        try {
            Applied applied = rulesets.set(
                    tenantId,
                    installationId,
                    bindingId,
                    request.code(),
                    expected,
                    currentActor.get().subject());
            return ResponseEntity.ok()
                    .eTag(AggregateVersion.toETag(applied.version()))
                    .body(new RulesetResponse(applied.bindingId(), applied.rulesetCode(), applied.version()));
        } catch (MarketplaceRulesetBindingService.UnknownBindingException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        } catch (MarketplaceRulesetBindingService.UnknownRulesetException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, unknown.getMessage(), Map.of("knownCodes", unknown.knownCodes()));
        } catch (MarketplaceRulesetBindingService.StaleBindingException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
    }

    public record KnownRulesetsResponse(List<String> knownCodes) {}

    /** @param code null clears the ruleset; the universal rules alone then apply */
    public record SetRulesetRequest(
            @Nullable @Size(max = 48) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
            String code) {}

    public record RulesetResponse(UUID bindingId, @Nullable String rulesetCode, long version) {}
}
