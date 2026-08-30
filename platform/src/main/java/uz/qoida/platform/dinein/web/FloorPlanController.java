package uz.qoida.platform.dinein.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.dinein.application.FloorPlanService;
import uz.qoida.platform.dinein.infrastructure.persistence.JdbcDineInStore.SectionRow;
import uz.qoida.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.qoida.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.CurrentActor;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.web.api.AggregateVersion;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * Authoring a branch's dining room (ADR 0047).
 *
 * <p>Everything here is at {@code LOCATION} scope, because a floor plan is
 * physical property of one branch and rearranging it is a job the person standing
 * in that room does. The ADR 0025 build gate enforces that the declared scope is
 * no wider than the path.
 *
 * <p>The QR rotation endpoint is the only one in the platform whose response
 * carries a live credential, and it carries it exactly once. There is no endpoint
 * that will return a table's token again: losing it means rotating, which is the
 * same operation, and a "show me the code" endpoint would turn a read grant into a
 * way of ordering to anybody's table.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/dine-in")
@Tag(name = "Dine-in floor plan", description = "Sections, tables, QR tokens, and branch settings")
public class FloorPlanController {

    private final FloorPlanService floorPlan;
    private final CurrentActor currentActor;

    public FloorPlanController(FloorPlanService floorPlan, CurrentActor currentActor) {
        this.floorPlan = floorPlan;
        this.currentActor = currentActor;
    }

    @GetMapping("/settings")
    @RequiresCapability(value = Capability.DINEIN_FLOORPLAN_MANAGE, scope = ScopeType.LOCATION)
    @Operation(summary = "What a scanned code does at this branch")
    public ResponseEntity<SettingsResponse> settings(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId) {

        return ResponseEntity.ok(SettingsResponse.of(
                floorPlan.settings(tenantId, brandId, locationId)));
    }

    @PutMapping("/settings")
    @RequiresCapability(value = Capability.DINEIN_FLOORPLAN_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Configure the branch's QR mode, turnaround buffer, and service charge",
            description = "SETTLE_OPEN_TICKET is refused here and again by the database. No POS "
                    + "adapter declares an open-ticket read or a ticket settlement, and ADR 0011 "
                    + "forbids an unsupported capability being the sole business path — so the "
                    + "mode fails at configuration rather than at a table with a bill on it.")
    public ResponseEntity<SettingsResponse> configure(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @Valid @RequestBody SettingsRequest body) {

        SettingsRow saved = floorPlan.configure(new FloorPlanService.BranchSettings(
                        tenantId, brandId, locationId, body.qrMode(), body.turnaroundMinutes(),
                        body.guestSessionTtlMinutes(), body.serviceChargeRateBp()),
                currentActor.get().subject(), body.reason());

        return ResponseEntity.ok(SettingsResponse.of(saved));
    }

    @GetMapping("/sections")
    @RequiresCapability(value = Capability.RESERVATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "The branch's sections")
    public ResponseEntity<List<SectionResponse>> sections(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId) {

        return ResponseEntity.ok(floorPlan.sections(tenantId, locationId).stream()
                .map(SectionResponse::of).toList());
    }

    @PostMapping("/sections")
    @RequiresCapability(value = Capability.DINEIN_FLOORPLAN_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Create a section",
            description = "A venue with no sections gets one section rather than a different "
                    + "model: a free-text table label has no availability and cannot be booked.")
    public ResponseEntity<SectionResponse> createSection(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @Valid @RequestBody SectionRequest body) {

        return ResponseEntity.ok(SectionResponse.of(floorPlan.createSection(
                new FloorPlanService.NewSection(tenantId, brandId, locationId, body.code(),
                        body.displayName(), body.sortOrder()))));
    }

    @GetMapping("/tables")
    @RequiresCapability(value = Capability.RESERVATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "The branch's tables",
            description = "Never carries a QR token. The digest is not a credential a reader "
                    + "needs and the token itself is not stored at all.")
    public ResponseEntity<List<TableResponse>> tables(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId) {

        return ResponseEntity.ok(floorPlan.tables(tenantId, locationId).stream()
                .map(TableResponse::of).toList());
    }

    @PostMapping("/tables")
    @RequiresCapability(value = Capability.DINEIN_FLOORPLAN_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Create a table")
    public ResponseEntity<TableResponse> createTable(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @Valid @RequestBody TableRequest body) {

        return ResponseEntity.ok(TableResponse.of(floorPlan.createTable(
                new FloorPlanService.NewTable(tenantId, brandId, locationId, body.sectionId(),
                        body.code(), body.displayName(), body.seats(),
                        Boolean.TRUE.equals(body.joinable()), body.layoutX(), body.layoutY()))));
    }

    @PostMapping("/tables/{tableId}/status-changes")
    @RequiresCapability(value = Capability.DINEIN_FLOORPLAN_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Take a table out of service, or archive it",
            description = "Tables archive and never delete: a booking whose table row is gone is "
                    + "a booking whose location cannot be rendered. Archiving also revokes the "
                    + "guest tokens live at that table.")
    public ResponseEntity<TableResponse> changeTableStatus(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @PathVariable UUID tableId,
            @Valid @RequestBody TableStatusRequest body, HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        return ResponseEntity.ok(TableResponse.of(floorPlan.changeTableStatus(
                tenantId, tableId, (int) expected, body.status(),
                currentActor.get().subject(), body.reason())));
    }

    @PostMapping("/tables/{tableId}/qr-token-rotations")
    @RequiresCapability(value = Capability.DINEIN_QR_ROTATE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Issue or rotate the table's QR token",
            description = "Returns the token once and never again. Rotation invalidates printed "
                    + "card and kills every guest token minted from the old code in the same "
                    + "transaction, so the response reports how many guests were cut off — "
                    + "during service that number is the cost of the decision.")
    public ResponseEntity<RotationResponse> rotate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @PathVariable UUID tableId,
            @Valid @RequestBody RotationRequest body, HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        FloorPlanService.IssuedQrToken issued = floorPlan.rotateQrToken(
                tenantId, tableId, (int) expected, currentActor.get().subject(), body.reason());

        return ResponseEntity.ok(new RotationResponse(issued.tableId(), issued.plaintext(),
                issued.rotatedAt(), issued.version(), issued.revokedGuestSessions()));
    }

    // -------------------------------------------------------------- contracts

    record SettingsRequest(
            @NotBlank @Size(max = 24) String qrMode,
            @Min(0) @Max(240) Integer turnaroundMinutes,
            @Min(5) @Max(1440) Integer guestSessionTtlMinutes,
            @Min(0) @Max(10000) Integer serviceChargeRateBp,
            @NotBlank @Size(max = 500) String reason) { }

    record SettingsResponse(UUID locationId, String qrMode, int turnaroundMinutes,
            int guestSessionTtlMinutes, int serviceChargeRateBp, int version) {

        static SettingsResponse of(SettingsRow row) {
            return new SettingsResponse(row.locationId(), row.qrMode().name(),
                    row.turnaroundMinutes(), row.guestSessionTtlMinutes(),
                    row.serviceChargeRateBp(), row.version());
        }
    }

    record SectionRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            Integer sortOrder) { }

    record SectionResponse(UUID sectionId, String code, String displayName, int sortOrder,
            String status, int version) {

        static SectionResponse of(SectionRow row) {
            return new SectionResponse(row.id(), row.code(), row.displayName(), row.sortOrder(),
                    row.status(), row.version());
        }
    }

    record TableRequest(
            UUID sectionId,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            @Min(1) @Max(100) int seats,
            Boolean joinable,
            BigDecimal layoutX,
            BigDecimal layoutY) { }

    /**
     * Carries {@code qrIssued} rather than the digest. Whether a table has a code
     * is what an operator needs to know; the digest tells them nothing and is one
     * more copy of a security-relevant value in one more log.
     */
    record TableResponse(UUID tableId, UUID sectionId, String code, String displayName, int seats,
            boolean joinable, String status, boolean qrIssued, Instant qrRotatedAt, int version) {

        static TableResponse of(TableRow row) {
            return new TableResponse(row.id(), row.sectionId(), row.code(), row.displayName(),
                    row.seats(), row.joinable(), row.status(), row.qrTokenHash() != null,
                    row.qrTokenRotatedAt(), row.version());
        }
    }

    record TableStatusRequest(
            @NotBlank @Size(max = 20) String status,
            @NotBlank @Size(max = 500) String reason) { }

    record RotationRequest(@NotBlank @Size(max = 500) String reason) { }

    record RotationResponse(UUID tableId, String qrToken, Instant rotatedAt, int version,
            int revokedGuestSessions) { }
}
