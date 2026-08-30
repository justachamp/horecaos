package uz.horecaos.platform.kitchen.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.kitchen.application.KitchenStationService;
import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Configuring a branch's stations and the rules that route dishes onto them
 * (ADR 0041).
 *
 * <p>All of this is authored from nothing. The legacy estate has no station data
 * to import — its {@code kitchens} table is a catalogue browse facet with no
 * branch reference, as V0030 sets out — so a branch cannot run a kitchen screen
 * until somebody who has stood in that kitchen has typed its stations in.
 *
 * <p>Both endpoints sit at {@code LOCATION} scope even though a brand routing
 * rule is a brand-level fact. The narrower scope is the safe direction: an
 * ADR 0025 grant covers downwards, so a brand-scoped grant satisfies a
 * location-scoped requirement, while the reverse would lock out every branch
 * manager. A brand rule authored from a branch path is still a brand rule and
 * still needs a brand-reaching grant to have been given.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen")
@Tag(name = "Kitchen configuration", description = "Production stations and routing rules")
public class KitchenStationController {

    private final KitchenStationService stations;

    public KitchenStationController(KitchenStationService stations) {
        this.stations = stations;
    }

    @GetMapping("/stations")
    @RequiresCapability(value = Capability.KITCHEN_TICKET_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "The branch's stations")
    public ResponseEntity<List<StationResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId) {

        return ResponseEntity.ok(stations.list(tenantId, locationId).stream()
                .map(StationResponse::of)
                .toList());
    }

    @PostMapping("/stations")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Create a station",
            description = "One active station per role per branch, and exactly one fallback. Both "
                    + "are database constraints: a second grill would make a brand routing rule "
                    + "resolve to whichever row was read first.")
    public ResponseEntity<StationResponse> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @Valid @RequestBody StationRequest body) {

        StationRow created = stations.create(new KitchenStationService.NewStation(
                tenantId, brandId, locationId, body.code(), StationRole.require(body.role()),
                body.displayNameRu(), body.displayNameUz(), body.displayNameEn(),
                body.sortOrder() == null ? 0 : body.sortOrder(),
                Boolean.TRUE.equals(body.fallback())));

        return ResponseEntity.ok(StationResponse.of(created));
    }

    @PostMapping("/routing-rules")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Route a catalogue node to a station role, or to a station",
            description = "Naming a station writes the location layer; naming a role writes the "
                    + "brand layer. A rule addresses exactly one of a variant, a product, or a "
                    + "category.")
    public ResponseEntity<RoutingRuleResponse> route(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @Valid @RequestBody RoutingRuleRequest body) {

        UUID id = stations.route(new KitchenStationService.NewRoutingRule(
                tenantId, brandId, body.stationId() == null ? null : locationId, body.variantId(),
                body.productId(), body.categoryId(),
                body.stationRole() == null ? null : StationRole.require(body.stationRole()),
                body.stationId()));

        return ResponseEntity.ok(new RoutingRuleResponse(id,
                body.stationId() == null ? "BRAND" : "LOCATION"));
    }

    record StationRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 16) String role,
            @NotBlank @Size(max = 120) String displayNameRu,
            @NotBlank @Size(max = 120) String displayNameUz,
            @NotBlank @Size(max = 120) String displayNameEn,
            Integer sortOrder,
            Boolean fallback) { }

    record StationResponse(UUID stationId, String code, String role, String displayNameRu,
            String displayNameUz, String displayNameEn, int sortOrder, boolean fallback,
            String status, int version) {

        static StationResponse of(StationRow station) {
            return new StationResponse(station.id(), station.code(), station.role().name(),
                    station.displayNameRu(), station.displayNameUz(), station.displayNameEn(),
                    station.sortOrder(), station.fallback(), station.status(), station.version());
        }
    }

    record RoutingRuleRequest(UUID variantId, UUID productId, UUID categoryId,
            @Size(max = 16) String stationRole, UUID stationId) { }

    record RoutingRuleResponse(UUID ruleId, String layer) { }
}
