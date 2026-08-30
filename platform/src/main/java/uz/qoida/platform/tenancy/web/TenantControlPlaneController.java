package uz.qoida.platform.tenancy.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.tenancy.api.BrandId;
import uz.qoida.platform.tenancy.api.LocationId;
import uz.qoida.platform.tenancy.api.TenantId;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.BrandView;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.CreateBrandCommand;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.CreateLocationCommand;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.CreateTenantCommand;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.DescribeLocationCommand;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.LocationView;
import uz.qoida.platform.tenancy.application.TenantControlPlaneService.TenantView;
import uz.qoida.platform.tenancy.domain.CoordinateSource;
import uz.qoida.platform.tenancy.domain.CustomerIdentityMode;
import uz.qoida.platform.web.authorization.RequiresCapability;

@RestController
@RequestMapping("/api/v1/control-plane/tenants")
@Tag(name = "SaaS control plane", description = "Tenant, brand, and single-brand location onboarding")
public class TenantControlPlaneController {

    private final TenantControlPlaneService service;

    public TenantControlPlaneController(TenantControlPlaneService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresCapability(value = Capability.TENANT_WRITE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Create a provisioning tenant", description = "Requires the global platform-admin role.")
    ResponseEntity<TenantView> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        TenantView tenant = service.createTenant(new CreateTenantCommand(
                request.slug(),
                request.legalName(),
                request.displayName(),
                request.defaultCurrency(),
                request.defaultTimezone(),
                request.customerIdentityMode()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{tenantId}")
                .buildAndExpand(tenant.id())
                .toUri();
        return ResponseEntity.created(location).body(tenant);
    }

    @GetMapping("/{tenantId}")
    @RequiresCapability(Capability.TENANT_READ)
    @Operation(summary = "Get a tenant", description = "Requires platform access or membership in the tenant organization.")
    TenantView getTenant(@PathVariable UUID tenantId) {
        return service.getTenant(new TenantId(tenantId));
    }

    @PutMapping("/{tenantId}/identity/keycloak-organization")
    @RequiresCapability(value = Capability.TENANT_WRITE, mutating = true)
    @Operation(
            summary = "Reconcile a tenant's Keycloak organization link",
            description = "Idempotently stores the immutable Keycloak organization ID. Requires platform-admin.")
    TenantView linkKeycloakOrganization(
            @PathVariable UUID tenantId,
            @Valid @RequestBody LinkKeycloakOrganizationRequest request) {
        return service.linkKeycloakOrganization(new TenantId(tenantId), request.organizationId());
    }

    @PostMapping("/{tenantId}/brands")
    @RequiresCapability(value = Capability.BRAND_WRITE, mutating = true)
    @Operation(summary = "Create a brand within a tenant")
    ResponseEntity<BrandView> createBrand(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreateOperatingUnitRequest request) {
        BrandView brand = service.createBrand(
                new TenantId(tenantId),
                new CreateBrandCommand(request.code(), request.slug(), request.displayName()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{brandId}")
                .buildAndExpand(brand.id())
                .toUri();
        return ResponseEntity.created(location).body(brand);
    }

    @GetMapping("/{tenantId}/brands")
    @RequiresCapability(Capability.BRAND_READ)
    @Operation(summary = "List brands within a tenant")
    List<BrandView> getBrands(@PathVariable UUID tenantId) {
        return service.getBrands(new TenantId(tenantId));
    }

    @PostMapping("/{tenantId}/brands/{brandId}/locations")
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Create a location owned by exactly one brand")
    ResponseEntity<LocationView> createLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody CreateLocationRequest request) {
        LocationView location = service.createLocation(
                new TenantId(tenantId),
                new BrandId(brandId),
                new CreateLocationCommand(
                        request.code(), request.slug(), request.displayName(), request.timezone()));
        URI resourceLocation = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{locationId}")
                .buildAndExpand(location.id())
                .toUri();
        return ResponseEntity.created(resourceLocation).body(location);
    }

    @GetMapping("/{tenantId}/brands/{brandId}/locations")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(summary = "List locations for one brand")
    List<LocationView> getLocations(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return service.getLocations(new TenantId(tenantId), new BrandId(brandId));
    }

    /**
     * Records the branch's address, telephone and point.
     *
     * <p>{@code PUT}, and the whole place at once. A {@code PATCH} of individual
     * fields would let a caller move a pin while leaving a contradicting address
     * behind it, and the two are read together by everything that uses them.
     */
    @PutMapping("/{tenantId}/brands/{brandId}/locations/{locationId}/place")
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Record where a location is, and how to reach it")
    LocationView describeLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody DescribeLocationRequest request) {
        return service.describeLocation(
                new TenantId(tenantId),
                new BrandId(brandId),
                new LocationId(locationId),
                new DescribeLocationCommand(
                        request.addressLine(), request.district(), request.city(),
                        request.landmark(), request.contactPhone(),
                        request.latitude(), request.longitude(), request.coordinateSource()));
    }

    /**
     * @param landmark ориентир
     * @param coordinateSource omit to let the platform infer it: a supplied point
     *                         becomes a merchant pin, and no point stays
     *                         {@code NOT_GEOCODED} and on the backfill's work list
     */
    record DescribeLocationRequest(
            @Size(max = 200) String addressLine,
            @Size(max = 120) String district,
            @Size(max = 120) String city,
            @Size(max = 200) String landmark,
            @Size(max = 32) @Pattern(regexp = "\\+[1-9][0-9]{7,14}")
            @Schema(example = "+998712000000") String contactPhone,
            @DecimalMin("-90.0") @DecimalMax("90.0")
            @Schema(example = "41.311081") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0")
            @Schema(example = "69.240562") Double longitude,
            CoordinateSource coordinateSource) { }

    record CreateTenantRequest(
            @NotBlank @Size(max = 63)
            @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
            String slug,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}")
            @Schema(example = "UZS") String defaultCurrency,
            @NotBlank @Size(max = 63)
            @Schema(example = "Asia/Tashkent") String defaultTimezone,
            @NotNull CustomerIdentityMode customerIdentityMode) { }

    record LinkKeycloakOrganizationRequest(
            @NotBlank @Size(max = 64)
            @Schema(description = "Immutable Keycloak organization UUID")
            String organizationId) { }

    record CreateOperatingUnitRequest(
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String code,
            @NotBlank @Size(max = 63)
            @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?") String slug,
            @NotBlank @Size(max = 200) String displayName) { }

    record CreateLocationRequest(
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String code,
            @NotBlank @Size(max = 63)
            @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?") String slug,
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Size(max = 63) @Schema(example = "Asia/Tashkent") String timezone) { }
}
