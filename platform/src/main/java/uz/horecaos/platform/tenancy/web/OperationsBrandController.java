package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.BrandView;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.LocationView;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore.ServiceState;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own read of its brands (operations Settings 10.1, 10.2's scope
 * bar brand picker).
 *
 * <p>{@link TenantControlPlaneController} already reads this same data — {@link
 * TenantControlPlaneService#getBrands} and the new {@link
 * TenantControlPlaneService#getBrand} below reuse it exactly — but that
 * controller sits on the control-plane surface (ADR 0057) because creating and
 * activating a brand is staff-provisioning, per the frontend information
 * architecture's rule that tenant, brand and location creation stays in
 * control-plane 2.3. Reading the profile of a brand that already exists is a
 * different act done by a different audience: the tenant's own staff, in
 * {@code apps/operations}. This controller is the read half of that split,
 * write-free by construction — a brand's status and identifiers change only
 * through control-plane today, and 10.1's own gap list names the display-name
 * edit this controller does not yet offer.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands")
@Tag(name = "Operations brand profile", description = "A tenant's own read of its brands (Settings 10.1)")
public class OperationsBrandController {

    private final TenantControlPlaneService service;
    private final ServiceScheduleService schedules;

    public OperationsBrandController(TenantControlPlaneService service, ServiceScheduleService schedules) {
        this.service = service;
        this.schedules = schedules;
    }

    @GetMapping
    @RequiresCapability(Capability.BRAND_READ)
    @Operation(summary = "List the tenant's brands, for the Settings scope bar's brand picker")
    List<BrandView> list(@PathVariable UUID tenantId) {
        return service.getBrands(new TenantId(tenantId));
    }

    @GetMapping("/{brandId}")
    @RequiresCapability(value = Capability.BRAND_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One brand's profile, for the Settings 10.1 screen")
    BrandView get(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return service.getBrand(new TenantId(tenantId), new BrandId(brandId));
    }

    @GetMapping("/{brandId}/locations")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(summary = "The brand's locations, for the Settings 10.2 list and the scope bar's location picker")
    List<LocationView> locations(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return service.getLocations(new TenantId(tenantId), new BrandId(brandId));
    }

    /**
     * Every location's own manual-override state, batched (Settings 10.2a
     * branch list).
     *
     * <p>Before this wave the branch list had no way to answer "which
     * branches are shut and why" without one request per row — the N+1
     * {@code locations-page.ts}'s own comment named. One join
     * ({@link ServiceScheduleService#statesForBrand}) answers the whole list.
     */
    @GetMapping("/{brandId}/locations/service-states")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every location's manual-override state, batched, for the branch list's state column and filter")
    List<LocationServiceStateResponse> locationServiceStates(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        Instant now = Instant.now();
        return schedules.statesForBrand(tenantId, brandId).entrySet().stream()
                .map(entry -> toResponse(entry.getKey(), entry.getValue(), now))
                .toList();
    }

    private static LocationServiceStateResponse toResponse(UUID locationId, ServiceState state, Instant now) {
        return new LocationServiceStateResponse(
                locationId,
                state.mode().name(),
                state.effectiveMode(now).name(),
                state.reasonCode(),
                state.effectiveUntil());
    }

    public record LocationServiceStateResponse(
            UUID locationId,
            String mode,
            String effectiveMode,
            @Nullable String reasonCode,
            @Nullable Instant effectiveUntil) {}
}
