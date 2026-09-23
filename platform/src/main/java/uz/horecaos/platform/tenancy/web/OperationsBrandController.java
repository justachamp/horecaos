package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.SalesChannelService;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService.BulkStateChangeOutcome;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService.ChangeServiceStateCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.BrandView;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.LocationView;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore.ServiceState;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
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
    private final SalesChannelService channels;
    private final LegalEntityDirectory legalEntities;
    private final Clock clock;

    public OperationsBrandController(
            TenantControlPlaneService service,
            ServiceScheduleService schedules,
            SalesChannelService channels,
            LegalEntityDirectory legalEntities,
            Clock clock) {
        this.service = service;
        this.schedules = schedules;
        this.channels = channels;
        this.legalEntities = legalEntities;
        this.clock = clock;
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

    /**
     * Closes, force-opens, or returns several of the brand's locations to
     * their own schedule at once (Settings 10.2a branch list's bulk close/open
     * bar) — reuses {@link ServiceScheduleService#changeServiceStateBulk},
     * which itself reuses {@link ServiceScheduleService#changeServiceState}
     * per location, exactly the single write
     * {@code LocationServiceOperationsController.changeServiceState} already
     * makes from the row action beside this bar.
     *
     * <p>{@code mutating = true} carries this endpoint's ADR 0031 replay
     * protection the same way it does on every other capability-gated write:
     * a resubmission under the same {@code Idempotency-Key} is answered from
     * the response {@code IdempotencyInterceptor} already recorded rather than
     * applying the command a second time.
     */
    @PostMapping("/{brandId}/locations/service-states")
    @RequiresCapability(value = Capability.LOCATION_SERVICE_STATE_CHANGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Close, force open, or return several locations to schedule at once",
            description = "Settings 10.2a's bulk close/open bar. N independent writes, never one "
                    + "all-or-nothing transaction: each location is applied through the same "
                    + "single-location write a lone request would use, so one location already in "
                    + "the target mode cannot fail the rest of the selection. Always 200 with a "
                    + "per-location outcome list, capped at "
                    + ServiceScheduleService.MAX_BULK_LOCATIONS + " locations.")
    public ResponseEntity<BulkServiceStateResponse> bulkChangeServiceState(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody BulkServiceStateRequest body) {
        try {
            List<BulkStateChangeOutcome> outcomes = schedules.changeServiceStateBulk(
                    tenantId,
                    brandId,
                    body.locationIds(),
                    new ChangeServiceStateCommand(body.mode(), body.reasonCode(), body.note(), body.effectiveUntil()));
            return ResponseEntity.ok(BulkServiceStateResponse.of(outcomes));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    public record BulkServiceStateRequest(
            @NotEmpty @Size(max = 200) List<UUID> locationIds,
            @NotNull ServiceMode mode,
            @Size(max = 48) String reasonCode,
            @Size(max = 400) String note,
            Instant effectiveUntil) {}

    public record BulkServiceStateResponse(
            int requestedCount, int appliedCount, int failedCount, List<BulkServiceStateItemResponse> items) {

        static BulkServiceStateResponse of(List<BulkStateChangeOutcome> outcomes) {
            int applied = (int)
                    outcomes.stream().filter(BulkStateChangeOutcome::applied).count();
            return new BulkServiceStateResponse(
                    outcomes.size(),
                    applied,
                    outcomes.size() - applied,
                    outcomes.stream().map(BulkServiceStateItemResponse::of).toList());
        }
    }

    public record BulkServiceStateItemResponse(
            UUID locationId, boolean applied, @Nullable String problemCode) {

        static BulkServiceStateItemResponse of(BulkStateChangeOutcome outcome) {
            return new BulkServiceStateItemResponse(outcome.locationId(), outcome.applied(), outcome.problemCode());
        }
    }

    /**
     * Every location's own assigned legal entity, batched (Settings 10.2a
     * branch list's INN filter, ADR 0038).
     */
    @GetMapping("/{brandId}/locations/legal-entities")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every location's own assigned legal entity, batched, for the branch list's INN filter")
    List<LocationLegalEntityResponse> locationLegalEntities(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        LocalDate today = LocalDate.now(clock);
        return legalEntities.sellersForBrand(tenantId, brandId, today).entrySet().stream()
                .map(entry -> toLegalEntityResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static LocationLegalEntityResponse toLegalEntityResponse(UUID locationId, FiscalSeller seller) {
        return new LocationLegalEntityResponse(locationId, seller.code(), seller.taxpayerNumber());
    }

    public record LocationLegalEntityResponse(UUID locationId, String legalEntityCode, String taxpayerNumber) {}

    /**
     * Every location's own active sales-channel codes, batched (Settings
     * 10.2a branch list's channel filter).
     */
    @GetMapping("/{brandId}/locations/channels")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every location's own active sales-channel codes, batched, for the branch list's channel filter")
    List<LocationChannelsResponse> locationChannels(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return channels.activeChannelCodesByLocation(tenantId, brandId).entrySet().stream()
                .map(entry -> new LocationChannelsResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    public record LocationChannelsResponse(UUID locationId, List<String> channelCodes) {}
}
