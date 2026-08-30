package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Drawing zones and deciding that a drawing governs (ADR 0037).
 *
 * <p>Everything sits at {@code BRAND} scope, which is both the narrowest the path
 * supports and the truth: a zone belongs to one brand's menu and prices, and a
 * grant over one branch is not enough to redraw a district.
 *
 * <p>Authoring and activation are separate capabilities. Drawing a polygon is
 * routine work; deciding that it governs what the platform will sell is not, and
 * activating a bad one stops sales in a district silently — the person who drew it
 * is the last person likely to notice that it is wrong.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/service-zones")
@Tag(name = "Service zones", description = "Delivery and catchment geometry, versioned like a policy")
public class ServiceZoneController {

    private final ServiceZoneService zones;

    public ServiceZoneController(ServiceZoneService zones) {
        this.zones = zones;
    }

    @PostMapping
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Register a zone",
            description = "Creates the lineage only. A zone has no geometry until a version is "
                    + "drafted and activated, so registering one changes nothing a customer sees.")
    public ResponseEntity<ZoneView> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateZoneRequest body) {

        UUID zoneId = zones.createZone(
                tenantId,
                brandId,
                body.role(),
                body.code(),
                body.displayNameRu(),
                body.displayNameUz(),
                body.displayNameEn());
        return ResponseEntity.ok(new ZoneView(zoneId, body.code(), body.role().name()));
    }

    @PostMapping("/{zoneId}/versions")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a new version of a zone",
            description = "Every edit to geometry, priority, tariff binding or threshold is a new "
                    + "version; the live one is never mutated. A payout dispute six weeks later "
                    + "asks whether that address was inside that polygon, and today's geometry "
                    + "cannot answer it. Supply either a circle around a branch or a GeoJSON "
                    + "polygon, never both.")
    public ResponseEntity<VersionView> draftVersion(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody DraftVersionRequest body) {

        var request = new ServiceZoneService.NewVersion(
                tenantId,
                brandId,
                zoneId,
                resolveRole(tenantId, brandId, zoneId),
                body.regionId(),
                body.priority(),
                body.currency(),
                body.deliveryTariffId(),
                body.freeDeliveryFromMinor(),
                body.minBasketMinor(),
                body.actorId());

        try {
            ServiceZoneService.DraftedVersion drafted = body.circle() != null
                    ? zones.draftCircleVersion(
                            request,
                            body.circle().originLocationId(),
                            body.circle().radiusMeters())
                    : zones.draftPolygonVersion(request, body.geoJson());
            return ResponseEntity.ok(new VersionView(drafted.zoneId(), drafted.version(), "DRAFT"));
        } catch (BranchOrigin.UnlocatedBranchException unlocated) {
            // The refusal names the branch and says what to do about it. Answering
            // with an empty zone list instead — which is what a system that merely
            // found no candidates would do — would send an operator to redraw a
            // polygon when the actual fault is a branch nobody has placed on a map.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unlocated.getMessage(),
                    Map.of("locationId", unlocated.locationId().toString(), "reason", "BRANCH_NOT_LOCATED"));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{zoneId}/versions/{version}/activate")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_ACTIVATE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a zone version live",
            description = "Refuses self-intersecting rings, geometry outside the region's box, "
                    + "and an area above the platform maximum — which is what stops an operator "
                    + "drawing a polygon around the country by accident. Every reason is returned "
                    + "at once rather than one attempt at a time.")
    public ResponseEntity<VersionView> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @PathVariable int version,
            @Valid @RequestBody ActivateRequest body) {

        try {
            zones.activate(tenantId, brandId, zoneId, version, body.actorId());
            return ResponseEntity.ok(new VersionView(zoneId, version, "ACTIVE"));
        } catch (ServiceZoneService.ZoneActivationRefusedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, refused.getMessage(), Map.of("problems", refused.problems()));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{zoneId}/locations")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Bind the zone to a branch",
            description = "A zone with no binding covers nothing. That is the safe direction: a "
                    + "half-configured zone is visibly inert rather than quietly serving the "
                    + "whole brand.")
    public ResponseEntity<Void> bind(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody BindLocationRequest body) {

        zones.bindLocation(tenantId, brandId, zoneId, body.locationId());
        return ResponseEntity.noContent().build();
    }

    private ZoneRole resolveRole(UUID tenantId, UUID brandId, UUID zoneId) {
        return zones.roleOf(tenantId, brandId, zoneId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No zone " + zoneId + " for this brand"));
    }

    public record CreateZoneRequest(
            @NotNull ZoneRole role,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 200) String displayNameRu,
            @NotBlank @Size(max = 200) String displayNameUz,
            @NotBlank @Size(max = 200) String displayNameEn) {}

    /**
     * @param circle a circle around a branch, which is the shape the legacy
     *               {@code max_distance} import produces and the one most operators
     *               reach for. Mutually exclusive with {@code geoJson}
     * @param priority the first key of the overlap ranking; ties fall to the
     *                 smaller area and then to the zone id, so a tie is always
     *                 resolved and never left to the query planner
     */
    public record DraftVersionRequest(
            CircleRequest circle,
            String geoJson,
            UUID regionId,
            int priority,
            @NotBlank @Size(min = 3, max = 3) String currency,
            UUID deliveryTariffId,
            @PositiveOrZero Long freeDeliveryFromMinor,
            @PositiveOrZero Long minBasketMinor,
            @NotNull UUID actorId) {

        public DraftVersionRequest {
            if ((circle == null) == (geoJson == null)) {
                throw new IllegalArgumentException("A version is either a circle or a polygon; supply exactly one");
            }
        }
    }

    public record CircleRequest(
            @NotNull UUID originLocationId, @Positive int radiusMeters) {}

    public record ActivateRequest(@NotNull UUID actorId) {}

    public record BindLocationRequest(@NotNull UUID locationId) {}

    public record ZoneView(UUID zoneId, String code, String role) {}

    public record VersionView(UUID zoneId, int version, String status) {}
}
