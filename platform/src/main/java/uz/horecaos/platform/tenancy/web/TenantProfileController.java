package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.TenantProfileService;
import uz.horecaos.platform.tenancy.application.TenantProfileService.CountryChange;
import uz.horecaos.platform.tenancy.domain.BusinessType;
import uz.horecaos.platform.tenancy.domain.Markets;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore.TenantProfile;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Where each tenant trades and what kind of business it is (ADR 0090): the
 * residency board, the business-type catalog, and the two acts that change
 * them, one of which waits for a second signature.
 */
@RestController
@Tag(
        name = "Tenant residency and business type",
        description = "Where each tenant trades, and what kind of business it is")
public class TenantProfileController {

    private final TenantProfileService profiles;
    private final CurrentActor currentActor;
    private final String hostingCountry;

    public TenantProfileController(
            TenantProfileService profiles,
            CurrentActor currentActor,
            @Value("${horecaos.hosting.country:UZ}") String hostingCountry) {
        this.profiles = profiles;
        this.currentActor = currentActor;
        this.hostingCountry = hostingCountry;
    }

    @GetMapping("/api/v1/control-plane/residency")
    @RequiresCapability(value = Capability.TENANT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Where every tenant trades, and where its data is hosted",
            description = "All tenant data is hosted in one country, whatever market the tenant "
                    + "trades in; each tenant's market decides the currency and timezone it starts from.")
    public ResponseEntity<ResidencyView> residency() {
        return ResponseEntity.ok(new ResidencyView(
                hostingCountry,
                Markets.all().stream().map(MarketView::of).toList(),
                profiles.all().stream().map(TenantProfileView::of).toList()));
    }

    @PostMapping("/api/v1/control-plane/tenants/{tenantId}/country-change")
    @RequiresCapability(value = Capability.TENANT_WRITE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Move a tenant to another market",
            description = "Needs a second signature. The first call raises the approval and answers "
                    + "AWAITING_APPROVAL; the same call after approval makes the change. The tenant's "
                    + "currency and timezone are not changed.")
    public ResponseEntity<CountryChangeView> changeCountry(
            @PathVariable UUID tenantId, @Valid @RequestBody CountryChangeRequest body) {
        CountryChange outcome = profiles.changeCountry(tenantId, body.countryCode(), actor(), body.reason());
        return ResponseEntity.ok(new CountryChangeView(outcome.status(), outcome.approvalRequestId()));
    }

    @GetMapping("/api/v1/control-plane/business-types")
    @RequiresCapability(value = Capability.TENANT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The kinds of business the platform serves, and how many tenants are each",
            description = "Each type's usual handovers and whether it runs a kitchen display. The type "
                    + "is recorded and shown; it does not enable or disable anything by itself.")
    public ResponseEntity<List<BusinessTypeView>> businessTypes() {
        Map<BusinessType, Long> counts = profiles.all().stream()
                .collect(Collectors.groupingBy(TenantProfile::businessType, Collectors.counting()));
        return ResponseEntity.ok(Arrays.stream(BusinessType.values())
                .map(type -> BusinessTypeView.of(type, counts.getOrDefault(type, 0L)))
                .toList());
    }

    @PutMapping("/api/v1/control-plane/tenants/{tenantId}/business-type")
    @RequiresCapability(value = Capability.TENANT_WRITE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Record what kind of business a tenant is")
    public ResponseEntity<Void> setBusinessType(
            @PathVariable UUID tenantId, @Valid @RequestBody BusinessTypeRequest body) {
        BusinessType type;
        try {
            type = BusinessType.valueOf(body.businessType());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown business type %s".formatted(body.businessType()));
        }
        profiles.setBusinessType(tenantId, type, actor(), body.reason());
        return ResponseEntity.noContent().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    public record ResidencyView(String hostingCountry, List<MarketView> markets, List<TenantProfileView> tenants) {}

    public record MarketView(String code, String name, String defaultCurrency, String defaultTimezone) {
        static MarketView of(Markets.Market market) {
            return new MarketView(market.code(), market.name(), market.defaultCurrency(), market.defaultTimezone());
        }
    }

    public record TenantProfileView(
            UUID tenantId,
            String slug,
            String displayName,
            String status,
            String countryCode,
            String businessType,
            String defaultCurrency,
            String defaultTimezone) {

        static TenantProfileView of(TenantProfile profile) {
            return new TenantProfileView(
                    profile.tenantId(),
                    profile.slug(),
                    profile.displayName(),
                    profile.status().name(),
                    profile.countryCode(),
                    profile.businessType().name(),
                    profile.defaultCurrency(),
                    profile.defaultTimezone());
        }
    }

    public record BusinessTypeView(String code, List<String> handovers, boolean kitchenDisplay, long tenants) {
        static BusinessTypeView of(BusinessType type, long tenants) {
            return new BusinessTypeView(
                    type.name(), type.handovers().stream().map(Enum::name).toList(), type.kitchenDisplay(), tenants);
        }
    }

    /** What a change of country did. */
    public record CountryChangeView(String status, @Nullable UUID approvalRequestId) {}

    public record CountryChangeRequest(
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @NotBlank @Size(max = 1000) String reason) {}

    public record BusinessTypeRequest(
            @NotBlank String businessType,
            @NotBlank @Size(max = 1000) String reason) {}
}
