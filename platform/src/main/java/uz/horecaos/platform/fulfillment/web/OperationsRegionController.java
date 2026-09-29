package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.application.RegionService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionGeography;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcRegionStore.RegionRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Regions — the geography a geocoder is allowed to answer inside (ADR 0037,
 * ADR 0104).
 *
 * <p>The first surface {@code fulfillment.regions} has ever had. The table has
 * existed since V0025 and its only writer in this repository was test SQL, so
 * a production database had no region at all: the bounding-box check {@link
 * ServiceZoneService#activate} runs — the one that catches a transposed
 * latitude — had nothing to check against, and onboarding a merchant in a
 * second city was a hand-written {@code INSERT}.
 *
 * <p><strong>{@code TENANT} scope, not {@code BRAND}.</strong> The row has no
 * {@code brand_id} and every brand under the tenant geocodes against it, so a
 * brand-scoped write would be a lie the URL tells about ownership and would
 * let a grant over one brand redraw the geography gating another brand's zone
 * activations. ADR 0104 records the cost of that choice out loud: {@code
 * BRAND_MANAGER}, who may draw and bind zones, cannot author a region.
 *
 * <p><strong>The capability is {@code DELIVERY_ZONE_MANAGE}</strong>, not one
 * of its own. A region constrains a geocoder; it is not a separate authority,
 * and minting a capability for four endpoints would put a row in every role
 * bundle that already implies this one.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/regions")
@Tag(name = "Regions", description = "Geocoder bounding boxes and the zone-activation guard (ADR 0037)")
public class OperationsRegionController {

    private final RegionService regions;

    public OperationsRegionController(RegionService regions) {
        this.regions = regions;
    }

    @GetMapping
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every region this tenant may use",
            description = "Its own, and the platform's — V0025's nullable tenant: \"Tashkent is "
                    + "not one tenant's fact\". A platform region is marked as such and is "
                    + "read-only to a tenant.")
    public ResponseEntity<List<RegionResponse>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                regions.listWithNames(tenantId).stream().map(RegionResponse::of).toList());
    }

    @GetMapping("/locale-set")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The languages the region editor offers",
            description = "Row 10.12. A region belongs to the tenant, not to a brand, so it is "
                    + "edited in the union of the tenant's brands' supported languages, default "
                    + "first; the default is the tenant's first brand's. A brand that has chosen "
                    + "no set contributes the platform triple. The set is a menu, not a "
                    + "constraint: a language outside it that a region already carries is kept, "
                    + "never deleted, by an edit.")
    public ResponseEntity<LocaleSetResponse> localeSet(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(LocaleSetResponse.of(regions.localeSet(tenantId)));
    }

    @PostMapping
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Register a region",
            description = "The SW/NE box is what constrains the geocoder and what a zone "
                    + "activation is checked against, so a box that is inverted, has no area, or "
                    + "does not contain its own centre is refused with every reason at once. The "
                    + "name is given per locale in `displayNames` (row 10.12); the tenant's "
                    + "default language is required, every other locale optional. "
                    + "displayNameRu/displayNameUz/displayNameEn remain accepted for callers that "
                    + "predate `displayNames`, and `displayNames` wins where both name a locale.")
    public ResponseEntity<RegionRegisteredView> create(
            @PathVariable UUID tenantId, @Valid @RequestBody RegionGeographyRequest body) {

        try {
            UUID regionId = regions.create(tenantId, body.toGeography());
            return ResponseEntity.ok(new RegionRegisteredView(regionId, body.code()));
        } catch (RegionService.RegionRefusedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, refused.getMessage(), Map.of("problems", refused.problems()));
        }
    }

    @PutMapping("/{regionId}")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Rewrite a region",
            description = "A tenant may rewrite its own regions and not the platform's; a "
                    + "platform region answers not-found rather than forbidden, so this cannot "
                    + "be used to discover which platform regions exist. expectedVersion is "
                    + "required and is the version RegionResponse last reported for this row; a "
                    + "stale one is refused with STALE_VERSION, the optimistic-locking convention "
                    + "every other mutable aggregate on this surface already carries. Names are "
                    + "written only for the locales the request names; one left out keeps its "
                    + "name, so an editor that shows only some languages never deletes the others.")
    public ResponseEntity<Void> update(
            @PathVariable UUID tenantId, @PathVariable UUID regionId, @Valid @RequestBody RegionGeographyRequest body) {

        if (body.expectedVersion() == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "expectedVersion is required to rewrite a region",
                    Map.of("problems", List.of("expectedVersion is required")));
        }
        try {
            regions.update(tenantId, regionId, body.toGeography(), body.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (RegionService.RegionRefusedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, refused.getMessage(), Map.of("problems", refused.problems()));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{regionId}/archive")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Archive a region",
            description = "Archived, never deleted: zone versions name this row and a months-old "
                    + "fee resolution's evidence must not become a dangling id. The versions that "
                    + "already name it keep being checked against its box.")
    public ResponseEntity<Void> archive(@PathVariable UUID tenantId, @PathVariable UUID regionId) {
        try {
            regions.archive(tenantId, regionId);
            return ResponseEntity.noContent().build();
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    /**
     * A region's geography as an operator types it.
     *
     * <p>No numeric bounds here beyond the database's own: {@code
     * RegionService.refuseBadGeography} reproduces {@code
     * ck_region_coordinates}, {@code ck_region_bbox_oriented} and {@code
     * ck_region_centre_within_bbox} as sentences and returns all of them at
     * once, which a per-field {@code @DecimalMin} cannot do for the three that
     * are about the numbers' relationship rather than their range.
     *
     * <p>{@code expectedVersion} is shared between {@code create} and {@code
     * update} the way {@code ConfigurationController.SetConfigurationValueRequest}
     * shares its own: {@code null} means "nothing to compare against yet" and
     * {@code create} never reads it; {@code update} requires it and refuses a
     * stale one with {@code STALE_VERSION}, the same optimistic-locking
     * convention {@code JdbcLegalEntityStore}/{@code JdbcSalesChannelStore}
     * already carry for their own aggregates.
     */
    public record RegionGeographyRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
            String code,

            @Nullable @Size(max = 200) String displayNameRu,
            @Nullable @Size(max = 200) String displayNameUz,
            @Nullable @Size(max = 200) String displayNameEn,
            double centreLat,
            double centreLon,
            double bboxSwLat,
            double bboxSwLon,
            double bboxNeLat,
            double bboxNeLon,
            @Nullable Integer expectedVersion,
            @Nullable @Size(max = 32) Map<String, @Size(max = 200) String> displayNames) {

        RegionGeography toGeography() {
            return new RegionGeography(
                    code,
                    displayNameRu,
                    displayNameUz,
                    displayNameEn,
                    centreLat,
                    centreLon,
                    bboxSwLat,
                    bboxSwLon,
                    bboxNeLat,
                    bboxNeLon,
                    displayNames == null ? Map.of() : displayNames);
        }
    }

    /** What a create answers: the id and the code the caller chose, nothing else. */
    public record RegionRegisteredView(UUID regionId, String code) {}

    /**
     * One row of {@link #list}.
     *
     * @param displayNames every locale the region has a name in, the platform triple
     *                     first then any other by code, each once (row 10.12)
     */
    public record RegionResponse(
            UUID regionId,
            boolean platform,
            String code,
            String displayNameRu,
            String displayNameUz,
            String displayNameEn,
            double centreLat,
            double centreLon,
            double bboxSwLat,
            double bboxSwLon,
            double bboxNeLat,
            double bboxNeLon,
            String status,
            int version,
            Map<String, String> displayNames) {

        static RegionResponse of(RegionService.RegionView view) {
            RegionRow row = view.row();
            return new RegionResponse(
                    row.regionId(),
                    row.platform(),
                    row.code(),
                    row.displayNameRu(),
                    row.displayNameUz(),
                    row.displayNameEn(),
                    row.centreLat(),
                    row.centreLon(),
                    row.bboxSwLat(),
                    row.bboxSwLon(),
                    row.bboxNeLat(),
                    row.bboxNeLon(),
                    row.status(),
                    row.version(),
                    view.names());
        }
    }

    /** @param locales default first; see {@link TenantLocaleSet} */
    public record LocaleSetResponse(List<String> locales, String defaultLocale, boolean configured) {

        static LocaleSetResponse of(TenantLocaleSet set) {
            return new LocaleSetResponse(set.locales(), set.defaultLocale(), set.configured());
        }
    }
}
