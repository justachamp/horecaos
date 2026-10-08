package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService.BatchImportReport;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService.ImportRow;
import uz.horecaos.platform.fulfillment.application.ZoneBatchImportService.RowOutcome;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
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
    private final ZoneBatchImportService batchImport;
    private final CurrentActor currentActor;

    public OperationsServiceZoneController(
            ServiceZoneService zones, ZoneBatchImportService batchImport, CurrentActor currentActor) {
        this.zones = zones;
        this.batchImport = batchImport;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every zone this brand has registered")
    public ResponseEntity<List<ServiceZoneController.ZoneSummaryResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(zones.listZonesWithNames(tenantId, brandId).stream()
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
                    ServiceZoneController.ZoneDetailResponse.of(zones.zoneDetailWithNames(tenantId, brandId, zoneId)));
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
                body.displayNameEn(),
                body.displayNames() == null ? Map.of() : body.displayNames());
        return ResponseEntity.ok(new ServiceZoneController.ZoneView(
                zoneId, body.code(), body.role().name()));
    }

    @PutMapping("/{zoneId}/names")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Rename a zone in the languages named",
            description = "Row 10.12. Writes the name for each locale in `displayNames` and no "
                    + "other: a locale left out keeps its name, so an editor that shows only the "
                    + "brand's supported languages never deletes the rest. A rename touches no "
                    + "geometry and creates no version. Answers not-found for a zone this brand "
                    + "does not have -- another tenant's or another brand's zone id included.")
    public ResponseEntity<ZoneNamesResponse> rename(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody RenameZoneRequest body) {
        try {
            ServiceZoneService.ZoneNames renamed = zones.renameZone(
                    tenantId,
                    brandId,
                    zoneId,
                    body.displayNameRu(),
                    body.displayNameUz(),
                    body.displayNameEn(),
                    body.displayNames() == null ? Map.of() : body.displayNames());
            return ResponseEntity.ok(new ZoneNamesResponse(renamed.zoneId(), renamed.names()));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
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

    @GetMapping("/{zoneId}/versions")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every version of one zone, newest first",
            description = "ADR 0037 versions a zone for the auditor; this is the same history for "
                    + "the operator who produced it. Without it the only lifecycle verb a console "
                    + "can reach is 'activate', and a wrong radius is permanent.")
    public ResponseEntity<List<ZoneVersionResponse>> versions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID zoneId) {
        try {
            return ResponseEntity.ok(zones.listVersions(tenantId, brandId, zoneId).stream()
                    .map(ZoneVersionResponse::of)
                    .toList());
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @GetMapping("/outlines")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The live outline of every zone this brand has (rows 3.2 and 7.10)",
            description = "ADR 0145: what a map draws a zone from. Only each zone's ACTIVE version, "
                    + "because a draft governs nothing and a map that drew one beside the live "
                    + "zones would show a boundary the platform does not enforce; a zone with no "
                    + "live version is absent, which is what it covers. Coordinates are named "
                    + "latitude/longitude, never an array pair, and each ring is open (the first "
                    + "corner is not repeated).")
    public ResponseEntity<List<ZoneOutlineResponse>> outlines(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(zones.activeOutlines(tenantId, brandId).stream()
                .map(ZoneOutlineResponse::of)
                .toList());
    }

    @GetMapping("/{zoneId}/versions/{version}/outline")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One version's outline, in any status (rows 3.6, 3.6c)",
            description = "ADR 0145 and ADR 0037: the geometry exactly as stored, so a draft can be "
                    + "looked at on a map before it is activated and a stored zone can be opened in "
                    + "the polygon editor to draft its next version. Answers not-found for a zone "
                    + "this brand does not have -- another tenant's or another brand's zone id "
                    + "included -- and for a version the zone never had.")
    public ResponseEntity<ZoneOutlineResponse> outline(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @PathVariable int version) {
        try {
            return ResponseEntity.ok(ZoneOutlineResponse.of(zones.versionOutline(tenantId, brandId, zoneId, version)));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{zoneId}/versions/{version}/deactivate")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_ACTIVATE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Retire the live version and put nothing in its place",
            description = "The zone then covers nothing — ADR 0037's safe direction. Deliberately "
                    + "the same capability as activation: deciding a drawing stops governing is "
                    + "the same class of decision as deciding it starts.")
    public ResponseEntity<ServiceZoneController.VersionView> deactivate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @PathVariable int version) {

        try {
            zones.deactivate(tenantId, brandId, zoneId, version);
            return ResponseEntity.ok(new ServiceZoneController.VersionView(zoneId, version, "RETIRED"));
        } catch (ServiceZoneService.ZoneActivationRefusedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, refused.getMessage(), Map.of("problems", refused.problems()));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @DeleteMapping("/{zoneId}/locations/{locationId}")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop this zone applying to a branch",
            description = "Closes the binding's validity window; the row stays, because a fee "
                    + "resolution six weeks old names the binding that applied. A branch that was "
                    + "not bound answers not-found rather than success — the two are identical on "
                    + "screen and one of them means the wrong row was clicked.")
    public ResponseEntity<Void> unbind(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID zoneId,
            @PathVariable UUID locationId) {

        try {
            zones.unbindLocation(tenantId, brandId, zoneId, locationId);
            return ResponseEntity.noContent().build();
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/import-batch")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Bulk-import legacy zone geometry (operations gap map row 3.6c, ADR 0037)",
            description = "Every accepted row lands as a new zone's DRAFT version, exactly like "
                    + "drafting one by hand — never activated by this endpoint. dryRun runs every "
                    + "check a real import would (geometry validity, the area ceiling, the "
                    + "region's bounding box) and then rolls the whole batch back, so an operator "
                    + "sees the outcome before committing it. A coordinate-order mistake still "
                    + "produces a valid polygon that ships nowhere near where it should — that is "
                    + "exactly why ADR 0037 gates activation behind rendering the shape on a map "
                    + "beside its source (X.4), which this endpoint does not attempt.")
    public ResponseEntity<BatchImportResponse> importBatch(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody BatchImportRequest body) {

        List<ImportRow> rows = body.rows().stream()
                .map(row -> new ImportRow(
                        row.externalRef(),
                        row.role(),
                        row.code(),
                        row.displayNameRu(),
                        row.displayNameUz(),
                        row.displayNameEn(),
                        row.regionId(),
                        row.priority(),
                        row.currency(),
                        row.deliveryTariffId(),
                        row.freeDeliveryFromMinor(),
                        row.minBasketMinor(),
                        row.geoJson(),
                        actorId()))
                .toList();

        BatchImportReport report = batchImport.importBatch(tenantId, brandId, rows, body.dryRun());
        return ResponseEntity.ok(BatchImportResponse.of(report));
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

    /**
     * A rename: the name per locale, and (for callers that predate `displayNames`) the
     * platform triple. Only the locales named are written.
     */
    public record RenameZoneRequest(
            @Nullable @Size(max = 200) String displayNameRu,
            @Nullable @Size(max = 200) String displayNameUz,
            @Nullable @Size(max = 200) String displayNameEn,
            @Nullable @Size(max = 32) Map<String, @Size(max = 200) String> displayNames) {}

    /** @param displayNames every locale the zone now has a name in, each once */
    public record ZoneNamesResponse(UUID zoneId, Map<String, String> displayNames) {}

    /**
     * One row of {@link #versions}.
     *
     * <p>{@code activatedAt} and {@code retiredAt} are the lifecycle, not
     * decoration: a version that was live and is not any more is the single
     * most useful row on this list, and it is indistinguishable from a
     * never-activated draft without both stamps.
     */
    public record ZoneVersionResponse(
            int version,
            String status,
            int priority,
            String currency,
            @Nullable UUID deliveryTariffId,
            @Nullable Long freeDeliveryFromMinor,
            @Nullable Long minBasketMinor,
            double areaSquareMeters,
            @Nullable UUID regionId,
            @Nullable UUID originLocationId,
            @Nullable String shapeKind,
            @Nullable Instant createdAt,
            @Nullable Instant activatedAt,
            @Nullable Instant retiredAt) {

        static ZoneVersionResponse of(JdbcServiceZoneStore.ZoneVersionRow row) {
            return new ZoneVersionResponse(
                    row.version(),
                    row.status(),
                    row.priority(),
                    row.currency(),
                    row.deliveryTariffId(),
                    row.freeDeliveryFromMinor(),
                    row.minBasketMinor(),
                    row.areaSquareMeters(),
                    row.regionId(),
                    row.originLocationId(),
                    row.shapeKind(),
                    row.createdAt(),
                    row.activatedAt(),
                    row.retiredAt());
        }
    }

    /** A point on an outline. */
    public record OutlinePointResponse(double latitude, double longitude) {}

    /** One polygon: its outer ring, then any holes; every ring open. */
    public record OutlinePolygonResponse(List<OutlinePointResponse> ring, List<List<OutlinePointResponse>> holes) {}

    /**
     * A zone version's geometry for a map.
     *
     * @param shapeKind {@code CIRCLE} or {@code POLYGON}: how it was authored, which decides whether
     *                  the polygon editor offers it for edit as a corner list or as what it is
     */
    public record ZoneOutlineResponse(
            UUID zoneId,
            String code,
            String role,
            int version,
            String status,
            @Nullable String shapeKind,
            List<OutlinePolygonResponse> polygons) {

        static ZoneOutlineResponse of(ServiceZoneService.ZoneOutline outline) {
            return new ZoneOutlineResponse(
                    outline.zoneId(),
                    outline.code(),
                    outline.role().name(),
                    outline.version(),
                    outline.status(),
                    outline.shapeKind(),
                    outline.polygons().stream()
                            .map(polygon -> new OutlinePolygonResponse(
                                    points(polygon.ring()),
                                    polygon.holes().stream()
                                            .map(ZoneOutlineResponse::points)
                                            .toList()))
                            .toList());
        }

        private static List<OutlinePointResponse> points(List<ServiceZoneService.OutlinePoint> ring) {
            return ring.stream()
                    .map(point -> new OutlinePointResponse(point.latitude(), point.longitude()))
                    .toList();
        }
    }

    /** A batch of legacy zone geometry to land as DRAFT versions (row {@code 3.6c}). */
    public record BatchImportRequest(
            boolean dryRun, @NotEmpty @Valid List<BatchImportZoneRow> rows) {}

    /**
     * One legacy zone, as the source system named it. {@code externalRef} is
     * never interpreted — it is echoed back on the matching {@link
     * RowOutcomeResponse} so an operator can line a report row up against the
     * source spreadsheet without hunting for it by geometry.
     */
    public record BatchImportZoneRow(
            @NotBlank @Size(max = 120) String externalRef,
            @NotNull ZoneRole role,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 200) String displayNameRu,
            @NotBlank @Size(max = 200) String displayNameUz,
            @NotBlank @Size(max = 200) String displayNameEn,
            @Nullable UUID regionId,
            int priority,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Nullable UUID deliveryTariffId,
            @PositiveOrZero Long freeDeliveryFromMinor,
            @PositiveOrZero Long minBasketMinor,
            @NotBlank String geoJson) {}

    public record BatchImportResponse(
            int totalRows, int accepted, int rejected, boolean dryRun, List<RowOutcomeResponse> rows) {

        static BatchImportResponse of(BatchImportReport report) {
            return new BatchImportResponse(
                    report.totalRows(),
                    report.accepted(),
                    report.rejected(),
                    report.dryRun(),
                    report.rows().stream().map(RowOutcomeResponse::of).toList());
        }
    }

    public record RowOutcomeResponse(
            String externalRef,
            boolean accepted,
            @Nullable UUID zoneId,
            @Nullable Integer version,
            @Nullable Double areaSquareMeters,
            List<String> warnings,
            @Nullable String error) {

        static RowOutcomeResponse of(RowOutcome outcome) {
            return new RowOutcomeResponse(
                    outcome.externalRef(),
                    outcome.accepted(),
                    outcome.zoneId(),
                    outcome.version(),
                    outcome.areaSquareMeters(),
                    outcome.warnings(),
                    outcome.error());
        }
    }
}
