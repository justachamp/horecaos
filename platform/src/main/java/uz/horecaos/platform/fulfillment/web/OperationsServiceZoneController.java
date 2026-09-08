package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own authoring of its delivery zones (ADR 0037).
 *
 * <p>Drawing a delivery zone and deciding what tariff and threshold it carries
 * is ordinary, tenant-owned configuration — the same class of decision the
 * tenant already makes about its menu or its opening hours — so this mirrors
 * {@link ServiceZoneController} onto the operations surface (ADR 0057) rather
 * than leaving it reachable only from the platform-staff control-plane app.
 * Every capability declared here — {@code DELIVERY_ZONE_READ}, {@code
 * DELIVERY_ZONE_MANAGE}, {@code DELIVERY_ZONE_ACTIVATE} — is one {@code
 * TENANT_OWNER}, {@code TENANT_ADMIN} and (for read and manage, not activate)
 * {@code BRAND_MANAGER} already hold under {@link uz.horecaos.platform.iam.api.PlatformRole};
 * this controller is the surface that was missing, not a new grant of
 * authority.
 *
 * <p>Delegates to the exact same {@link ServiceZoneService} the control-plane
 * controller uses, so the geometry validation, the deterministic ranking, and
 * the ADR 0027 audit trail the service now writes on every mutation are
 * identical on both surfaces. The one deliberate difference from {@link
 * ServiceZoneController}'s request shape: the actor who created a version or
 * activated one is resolved from the caller's own authenticated identity
 * here, never accepted as a request field — the reason {@code
 * LegalEntityController} gives for the same choice applies just as much to a
 * zone's {@code activated_by} column.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/service-zones")
@Tag(name = "Service zones", description = "A tenant's own delivery and catchment geometry (ADR 0037)")
public class OperationsServiceZoneController {

    private final ServiceZoneService zones;
    private final CurrentActor currentActor;

    public OperationsServiceZoneController(ServiceZoneService zones, CurrentActor currentActor) {
        this.zones = zones;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every zone this brand has registered")
    public ResponseEntity<List<ServiceZoneController.ZoneSummaryResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(zones.listZones(tenantId, brandId).stream()
                .map(ServiceZoneController.ZoneSummaryResponse::of)
                .toList());
    }

    @GetMapping("/{zoneId}")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One zone's live numbers and the branches it applies to")
    public ResponseEntity<ServiceZoneController.ZoneDetailResponse> detail(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID zoneId) {
        try {
            return ResponseEntity.ok(
                    ServiceZoneController.ZoneDetailResponse.of(zones.zoneDetail(tenantId, brandId, zoneId)));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Register a zone",
            description = "Creates the lineage only. A zone has no geometry until a version is "
                    + "drafted and activated, so registering one changes nothing a customer sees.")
    public ResponseEntity<ServiceZoneController.ZoneView> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody ServiceZoneController.CreateZoneRequest body) {

        UUID zoneId = zones.createZone(
                tenantId,
                brandId,
                body.role(),
                body.code(),
                body.displayNameRu(),
                body.displayNameUz(),
                body.displayNameEn());
        return ResponseEntity.ok(new ServiceZoneController.ZoneView(
                zoneId, body.code(), body.role().name()));
    }

    @PostMapping("/{zoneId}/versions")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a new version of a zone",
            description = "Every edit to geometry, priority, tariff binding or threshold is a new "
                    + "version; the live one is never mutated. Supply either a circle around a "
                    + "branch or a GeoJSON polygon, never both.")
    public ResponseEntity<ServiceZoneController.VersionView> draftVersion(
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
                actorId());

        try {
            ServiceZoneService.DraftedVersion drafted = body.circle() != null
                    ? zones.draftCircleVersion(
                            request,
                            body.circle().originLocationId(),
                            body.circle().radiusMeters())
                    : zones.draftPolygonVersion(request, body.geoJson());
            return ResponseEntity.ok(
                    new ServiceZoneController.VersionView(drafted.zoneId(), drafted.version(), "DRAFT"));
        } catch (BranchOrigin.UnlocatedBranchException unlocated) {
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
                    + "and an area above the platform maximum. Every reason is returned at once "
                    + "rather than one attempt at a time.")
    public ResponseEntity<ServiceZoneController.VersionView> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @PathVariable int version) {

        try {
            zones.activate(tenantId, brandId, zoneId, version, actorId());
            return ResponseEntity.ok(new ServiceZoneController.VersionView(zoneId, version, "ACTIVE"));
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
            @Valid @RequestBody ServiceZoneController.BindLocationRequest body) {

        zones.bindLocation(tenantId, brandId, zoneId, body.locationId());
        return ResponseEntity.noContent().build();
    }

    private ZoneRole resolveRole(UUID tenantId, UUID brandId, UUID zoneId) {
        return zones.roleOf(tenantId, brandId, zoneId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No zone " + zoneId + " for this brand"));
    }

    /**
     * The caller's own authenticated identity, as the {@code created_by}/{@code
     * activated_by} column expects it. Production Keycloak subjects are UUIDs;
     * a service account or malformed token fails loudly here rather than
     * writing a fabricated identity onto a delivery-fee-governing row.
     */
    private UUID actorId() {
        return UUID.fromString(currentActor.get().subject());
    }

    /**
     * A new draft version of a zone's geometry and delivery terms.
     *
     * @param circle a circle around a branch. Mutually exclusive with {@code geoJson}
     * @param priority the first key of the overlap ranking; ties fall to the
     *                 smaller area and then to the zone id
     */
    public record DraftVersionRequest(
            ServiceZoneController.CircleRequest circle,
            String geoJson,
            UUID regionId,
            int priority,
            @NotBlank @Size(min = 3, max = 3) String currency,
            UUID deliveryTariffId,
            @PositiveOrZero Long freeDeliveryFromMinor,
            @PositiveOrZero Long minBasketMinor) {

        public DraftVersionRequest {
            if ((circle == null) == (geoJson == null)) {
                throw new IllegalArgumentException("A version is either a circle or a polygon; supply exactly one");
            }
        }
    }
}
