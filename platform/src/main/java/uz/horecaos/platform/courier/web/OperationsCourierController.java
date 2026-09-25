package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.application.CourierAccountProvisioningService;
import uz.horecaos.platform.courier.application.CourierAdjustmentService;
import uz.horecaos.platform.courier.application.CourierCashService;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicies;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.CourierRosterQueryService;
import uz.horecaos.platform.courier.application.CourierRosterQueryService.RosterEntry;
import uz.horecaos.platform.courier.application.CourierRosterService;
import uz.horecaos.platform.courier.application.CourierSettlementService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.CourierTypeService;
import uz.horecaos.platform.courier.application.DeliveryCostQueryService;
import uz.horecaos.platform.courier.application.PartnerInvoiceService;
import uz.horecaos.platform.courier.application.PlannedShiftService;
import uz.horecaos.platform.courier.application.PlannedShiftService.NewPlannedShift;
import uz.horecaos.platform.courier.application.PlannedShiftService.RosterComparison;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.ComplianceField;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.PartnerChargeType;
import uz.horecaos.platform.courier.domain.PayoutMethod;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.RevealTiming;
import uz.horecaos.platform.courier.domain.SettlementPeriodStatus;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.AdjustmentSplit;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.ShiftCashSummary;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore.CardSummaryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.HandoverRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.BranchBindingRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierGroupRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceLineRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcPlannedShiftStore.PlannedShiftRow;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The operations half of ADR 0042: engagements, verification, adjustments,
 * settlement, delivery cost, and partner invoices.
 *
 * <p>Nothing here can open a shift or end a break, and there is no endpoint
 * through which a manager could. That absence is the design: those two
 * transitions belong to the courier alone, and the way a capability model states
 * that is by not offering the route.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Courier operations", description = "Engagements, shift approval, settlement, and delivery cost")
public class OperationsCourierController {

    private final CourierEngagementService engagements;
    private final CourierAccountProvisioningService accountProvisioning;
    private final CourierShiftService shifts;
    private final CourierCashService cash;
    private final CourierAdjustmentService adjustments;
    private final CourierSettlementService settlement;
    private final DeliveryCostQueryService deliveryCosts;
    private final PartnerInvoiceService partnerInvoices;
    private final JdbcCourierLedgerStore ledger;
    private final CourierRosterQueryService rosterQuery;
    private final CourierRosterService roster;
    private final JdbcCourierStore courierStore;
    private final CourierTypeService courierTypes;
    private final CourierRateCardService rateCards;
    private final JdbcCourierRateCardStore rateCardStore;
    private final JdbcCourierShiftStore shiftStore;
    private final CourierPolicyResolver policyResolver;
    private final PolicyAuthor policyAuthor;
    private final JdbcDeliveryCostStore deliveryCostStore;
    private final PlannedShiftService plannedShifts;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;

    public OperationsCourierController(
            CourierEngagementService engagements,
            CourierAccountProvisioningService accountProvisioning,
            CourierShiftService shifts,
            CourierCashService cash,
            CourierAdjustmentService adjustments,
            CourierSettlementService settlement,
            DeliveryCostQueryService deliveryCosts,
            PartnerInvoiceService partnerInvoices,
            JdbcCourierLedgerStore ledger,
            CourierRosterQueryService rosterQuery,
            CourierRosterService roster,
            JdbcCourierStore courierStore,
            CourierTypeService courierTypes,
            CourierRateCardService rateCards,
            JdbcCourierRateCardStore rateCardStore,
            JdbcCourierShiftStore shiftStore,
            CourierPolicyResolver policyResolver,
            PolicyAuthor policyAuthor,
            JdbcDeliveryCostStore deliveryCostStore,
            PlannedShiftService plannedShifts,
            CurrentActor currentActor,
            AuthorizationService authorization) {
        this.engagements = engagements;
        this.accountProvisioning = accountProvisioning;
        this.shifts = shifts;
        this.cash = cash;
        this.adjustments = adjustments;
        this.settlement = settlement;
        this.deliveryCosts = deliveryCosts;
        this.partnerInvoices = partnerInvoices;
        this.ledger = ledger;
        this.rosterQuery = rosterQuery;
        this.roster = roster;
        this.courierStore = courierStore;
        this.courierTypes = courierTypes;
        this.rateCards = rateCards;
        this.rateCardStore = rateCardStore;
        this.shiftStore = shiftStore;
        this.policyResolver = policyResolver;
        this.policyAuthor = policyAuthor;
        this.deliveryCostStore = deliveryCostStore;
        this.plannedShifts = plannedShifts;
        this.currentActor = currentActor;
        this.authorization = authorization;
    }

    // ------------------------------------------------------------------ roster

    @GetMapping("/couriers")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(
            summary = "The in-house roster",
            description = "§3.3 Couriers, and §3.1's fleet rail — both read this. display_reference "
                    + "only, never the decrypted name (ADR 0029); current load is counted from open "
                    + "shipments the same way sourcing counts it, so a dispatcher and a manager can "
                    + "never see two different numbers for one courier.")
    public ResponseEntity<List<RosterEntryResponse>> roster(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(rosterQuery.roster(tenantId).stream()
                .map(RosterEntryResponse::of)
                .toList());
    }

    @GetMapping("/couriers/{courierId}")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(
            summary = "One courier, with the standing of their compliance file (IA 3.3)",
            description = "The detail pane behind the roster. Says which documents are on file "
                    + "and never what any of them contains — presence is what a manager needs to "
                    + "chase a missing licence, and reading a passport is the separate, audited "
                    + "act courier.pii.reveal gates. ПИНФЛ in particular is absent from every "
                    + "read on this controller.")
    public ResponseEntity<CourierDetailResponse> courier(@PathVariable UUID tenantId, @PathVariable UUID courierId) {

        return rosterQuery
                .detail(tenantId, courierId)
                .map(detail -> ResponseEntity.ok(CourierDetailResponse.of(detail)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such courier: " + courierId));
    }

    @PostMapping("/couriers/{courierId}/compliance-file")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Record or correct the compliance file (IA 3.3)",
            description = "Writes the fields the request carries, clears the ones it names in "
                    + "`clear`, and leaves every other field exactly as it was. It cannot be a "
                    + "whole-file replace: nothing outside a reveal holds these plaintexts, so a "
                    + "pane correcting a plate has no passport to send back and a replace would "
                    + "erase it.")
    public ResponseEntity<Void> recordComplianceFile(
            @PathVariable UUID tenantId,
            @PathVariable UUID courierId,
            @Valid @RequestBody CourierComplianceFileRequest body) {

        engagements.recordComplianceFile(
                tenantId, courierId, body.toCommand(), actor(), body.reason(), correlationId());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/couriers/{courierId}/compliance-file")
    @RequiresCapability(Capability.COURIER_PII_REVEAL)
    @Operation(
            summary = "Reveal the compliance file under a declared purpose (ADR 0029)",
            description = "The only path out of these nine columns. Requires a purpose, which is "
                    + "written as an audit fact naming which fields were opened. A field with no "
                    + "value is absent from the answer rather than present and empty: \"no "
                    + "licence is on file\" and \"the licence field is blank\" are different "
                    + "statements.")
    public ResponseEntity<CourierComplianceRevealResponse> revealComplianceFile(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @RequestParam @NotBlank String purpose) {

        Map<ComplianceField, String> revealed =
                engagements.revealComplianceFile(tenantId, courierId, purpose, actor(), correlationId());
        return ResponseEntity.ok(CourierComplianceRevealResponse.of(revealed));
    }

    // ------------------------------------------------------- groups and branches

    @GetMapping("/courier-groups")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(summary = "Every courier group the tenant has authored (IA 3.3)")
    public ResponseEntity<List<CourierGroupResponse>> courierGroups(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                roster.groups(tenantId).stream().map(CourierGroupResponse::of).toList());
    }

    @PostMapping("/courier-groups")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Author a courier group",
            description = "A label over the roster — «night», «bicycles» — and not an "
                    + "authorization: nothing in dispatch reads a group.")
    public ResponseEntity<CourierGroupIdResponse> createCourierGroup(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateCourierGroupRequest body) {

        return ResponseEntity.ok(new CourierGroupIdResponse(roster.createGroup(
                tenantId, body.code(), body.displayName(), actor(), body.reason(), correlationId())));
    }

    @PostMapping("/courier-groups/{groupId}/archival")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Archive a courier group",
            description = "Archived rather than deleted: a group named on a past shift plan is "
                    + "history, and its members stay listed.")
    public ResponseEntity<Void> archiveCourierGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID groupId,
            @Valid @RequestBody CourierRosterReasonRequest body) {

        roster.archiveGroup(tenantId, groupId, actor(), body.reason(), correlationId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/couriers/{courierId}/groups")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(summary = "Put a courier in a group", description = "Repeating the call changes nothing.")
    public ResponseEntity<Void> joinCourierGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID courierId,
            @Valid @RequestBody CourierGroupMembershipRequest body) {

        roster.addToGroup(tenantId, body.groupId(), courierId, actor(), body.reason(), correlationId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/couriers/{courierId}/groups/{groupId}/removal")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Take a courier out of a group",
            description = "A POST on a sub-resource rather than a DELETE, so that the reason "
                    + "every write on this controller records travels in a body instead of a "
                    + "query string — ADR 0029 keeps reasons out of URLs.")
    public ResponseEntity<Void> leaveCourierGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID courierId,
            @PathVariable UUID groupId,
            @Valid @RequestBody CourierRosterReasonRequest body) {

        roster.removeFromGroup(tenantId, groupId, courierId, actor(), body.reason(), correlationId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/couriers/{courierId}/branch-bindings")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Bind a courier to a branch (IA 3.3)",
            description = "Until now a courier was attached to a branch only by having opened a "
                    + "shift there, so the attachment existed exactly while somebody was working. "
                    + "At most one branch is primary; binding a second as primary stands the "
                    + "first down in the same call.")
    public ResponseEntity<Void> bindCourierToBranch(
            @PathVariable UUID tenantId,
            @PathVariable UUID courierId,
            @Valid @RequestBody CourierBranchBindingRequest body) {

        roster.bindToBranch(
                tenantId,
                courierId,
                body.brandId(),
                body.locationId(),
                body.primary(),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/couriers/{courierId}/branch-bindings/{brandId}/{locationId}/removal")
    @RequiresCapability(
            value = Capability.COURIER_ENGAGEMENT_MANAGE,
            scope = ResourceScope.ScopeType.LOCATION,
            mutating = true)
    @Operation(
            summary = "Unbind a courier from a branch",
            description = "Scoped to the branch named in the path: the manager of the branch a "
                    + "courier is leaving can release the binding without holding the roster "
                    + "across the whole tenant. A tenant-wide grant still covers it (ADR 0025). "
                    + "brandId carries the scope only, the same convention CallStatsController "
                    + "uses — the unbind itself is keyed on (tenant, courier, location), which is "
                    + "already unique.")
    public ResponseEntity<Void> unbindCourierFromBranch(
            @PathVariable UUID tenantId,
            @PathVariable UUID courierId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CourierRosterReasonRequest body) {

        roster.unbindFromBranch(tenantId, courierId, locationId, actor(), body.reason(), correlationId());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/courier-types")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(
            summary = "Vehicle classes, for the registration form's picker and the IA 3.4 management screen",
            description = "includeArchived=false (default) is the registration picker's list; "
                    + "the management screen passes true so an archived class a past rate card "
                    + "or courier still names does not vanish from the table.")
    public ResponseEntity<List<CourierTypeResponse>> types(
            @PathVariable UUID tenantId, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(courierStore.listTypes(tenantId, includeArchived).stream()
                .map(CourierTypeResponse::of)
                .toList());
    }

    @PostMapping("/courier-types")
    @RequiresCapability(value = Capability.COURIER_TYPE_MANAGE, mutating = true)
    @Operation(
            summary = "Define a vehicle class (IA 3.4)",
            description = "The dispatch numbers — minimum/maximum distance, the offer TTL — and "
                    + "not a courier's pay, which is a rate card and a separate act. "
                    + "startingMinuteOffset and workMode are ADR 0108: captured and rendered, not "
                    + "yet read by the accrual calculator or the dispatch gate.")
    public ResponseEntity<CourierTypeResponse> createType(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateCourierTypeRequest body) {

        UUID typeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                typeId,
                tenantId,
                body.code(),
                body.displayName(),
                body.vehicleClass(),
                body.minDistanceMeters(),
                body.maxDistanceMeters(),
                body.maxConcurrentAssignments(),
                body.offerTtlSeconds(),
                body.startingMinuteOffset(),
                body.workModeOrDefault(),
                "ACTIVE",
                1));

        return ResponseEntity.ok(
                CourierTypeResponse.of(courierStore.findType(tenantId, typeId).orElseThrow()));
    }

    @PutMapping("/courier-types/{typeId}")
    @RequiresCapability(value = Capability.COURIER_TYPE_MANAGE, mutating = true)
    @Operation(
            summary = "Correct a vehicle class (ADR 0108)",
            description = "Types were create-only at every layer: no update and no archive, even "
                    + "though status already has ARCHIVED, so a mistyped code or a wrong offer "
                    + "TTL was permanent. expectedVersion is required and is the version this "
                    + "controller last reported for this row; a stale one is refused with "
                    + "STALE_VERSION.")
    public ResponseEntity<CourierTypeResponse> updateType(
            @PathVariable UUID tenantId, @PathVariable UUID typeId, @Valid @RequestBody UpdateCourierTypeRequest body) {

        CourierTypeRow updated = courierTypes.updateType(
                tenantId,
                typeId,
                new JdbcCourierStore.CourierTypeUpdate(
                        body.code(),
                        body.displayName(),
                        body.vehicleClass(),
                        body.minDistanceMeters(),
                        body.maxDistanceMeters(),
                        body.maxConcurrentAssignments(),
                        body.offerTtlSeconds(),
                        body.startingMinuteOffset(),
                        body.workMode()),
                body.expectedVersion(),
                actor(),
                body.reason());

        return ResponseEntity.ok(CourierTypeResponse.of(updated));
    }

    @PostMapping("/courier-types/{typeId}/archival")
    @RequiresCapability(value = Capability.COURIER_TYPE_MANAGE, mutating = true)
    @Operation(
            summary = "Archive a vehicle class",
            description = "Archived, never deleted: a past rate card or courier still names it. "
                    + "The registration picker stops offering it; the management screen keeps "
                    + "showing it when includeArchived=true.")
    public ResponseEntity<Void> archiveType(
            @PathVariable UUID tenantId,
            @PathVariable UUID typeId,
            @Valid @RequestBody ArchiveCourierTypeRequest body) {

        courierTypes.archiveType(tenantId, typeId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------ rate cards

    @GetMapping("/rate-cards")
    @RequiresCapability(Capability.COURIER_RATECARD_READ)
    @Operation(summary = "Every rate card the brand has authored (IA 3.4, Тариф курьера)")
    public ResponseEntity<List<RateCardSummaryResponse>> rateCards(
            @PathVariable UUID tenantId, @RequestParam UUID brandId) {

        return ResponseEntity.ok(rateCardStore.list(tenantId, brandId).stream()
                .map(RateCardSummaryResponse::of)
                .toList());
    }

    @GetMapping("/rate-cards/{cardId}")
    @RequiresCapability(Capability.COURIER_RATECARD_READ)
    @Operation(summary = "One rate card, with its band ladder")
    public ResponseEntity<RateCardDetailResponse> rateCard(@PathVariable UUID tenantId, @PathVariable UUID cardId) {

        return rateCardStore
                .findCard(tenantId, cardId)
                .map(card -> ResponseEntity.ok(RateCardDetailResponse.of(card)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such rate card: " + cardId));
    }

    @PostMapping("/rate-cards")
    @RequiresCapability(value = Capability.COURIER_RATECARD_MANAGE, mutating = true)
    @Operation(
            summary = "Author a draft rate card",
            description = "A draft prices nobody until it is activated, so a season's tariff can "
                    + "be built during service without moving a live one.")
    public ResponseEntity<Map<String, UUID>> authorRateCard(
            @PathVariable UUID tenantId, @Valid @RequestBody NewRateCardRequest body) {

        UUID cardId = rateCards.author(new CourierRateCardService.NewRateCard(
                tenantId,
                body.brandId(),
                body.locationId(),
                body.courierTypeId(),
                body.code(),
                body.cardVersion(),
                body.currency(),
                body.components().stream()
                        .map(c -> new RateComponent(
                                UUID.randomUUID(),
                                RateComponentType.valueOf(c.componentType()),
                                c.priority(),
                                c.amountMinor(),
                                c.bandFromMeters(),
                                c.bandToMeters(),
                                c.minimumPaidSeconds()))
                        .toList()));

        return ResponseEntity.ok(Map.of("cardId", cardId));
    }

    @PostMapping("/rate-cards/{cardId}/activation")
    @RequiresCapability(value = Capability.COURIER_RATECARD_MANAGE, mutating = true)
    @Operation(
            summary = "Put a rate card in front of couriers",
            description = "Supersedes any earlier active card with the same code, in the same "
                    + "transaction — two active versions of one code would make an accrual depend "
                    + "on which row a query happened to read first.")
    public ResponseEntity<Void> activateRateCard(
            @PathVariable UUID tenantId, @PathVariable UUID cardId, @Valid @RequestBody ActivateRateCardRequest body) {

        rateCards.activate(tenantId, cardId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    // -------------------------------------------------------------------- shifts

    @GetMapping("/courier-shifts")
    @RequiresCapability(Capability.COURIER_SHIFT_READ)
    @Operation(
            summary = "The branch's shifts, newest first (IA 3.5, Посещаемость)",
            description = "Open, closed and everything between — including AWAITING_APPROVAL, "
                    + "which is the manager's own worklist on this screen. from/to window the read "
                    + "to a period; omitted, the read falls back to the most recent `limit` shifts "
                    + "the way this endpoint always has.")
    public ResponseEntity<List<ShiftResponse>> courierShifts(
            @PathVariable UUID tenantId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") int limit) {

        List<ShiftRow> rows = shiftStore.atLocation(tenantId, brandId, locationId, from, to, Math.min(limit, 500));
        Map<UUID, String> names = courierStore.displayReferencesOf(
                tenantId, rows.stream().map(ShiftRow::courierId).collect(Collectors.toSet()));
        return ResponseEntity.ok(rows.stream()
                .map(row -> ShiftResponse.of(row, names.get(row.courierId())))
                .toList());
    }

    // ------------------------------------------------------------ roster entries

    @GetMapping("/courier-roster-entries")
    @RequiresCapability(value = Capability.COURIER_SHIFT_READ, scope = ResourceScope.ScopeType.LOCATION)
    @Operation(
            summary = "The branch's planned shifts (IA 3.5's roster, over P02's ScheduleGrid)",
            description = "The plan a manager authored, as distinct from what a courier actually "
                    + "opened. from/to window the read; both default to the surrounding week when "
                    + "omitted so the grid always has something to draw.")
    public ResponseEntity<List<PlannedShiftResponse>> rosterEntries(
            @PathVariable UUID tenantId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") int limit) {

        List<PlannedShiftRow> rows =
                plannedShifts.atLocation(tenantId, brandId, locationId, from, to, Math.min(limit, 500));
        Map<UUID, String> names = courierStore.displayReferencesOf(
                tenantId, rows.stream().map(PlannedShiftRow::courierId).collect(Collectors.toSet()));
        return ResponseEntity.ok(rows.stream()
                .map(row -> PlannedShiftResponse.of(row, names.get(row.courierId()), null))
                .toList());
    }

    @GetMapping("/courier-roster-entries/comparison")
    @RequiresCapability(value = Capability.COURIER_SHIFT_READ, scope = ResourceScope.ScopeType.LOCATION)
    @Operation(
            summary = "Planned versus actual, for one period (IA 3.5)",
            description = "Every planned entry in the window, each carrying whichever actual shift "
                    + "of the same courier overlapped it — COVERED, PENDING (the window has not "
                    + "elapsed) or UNCOVERED. The comparison is computed at read time; nothing here "
                    + "writes MISSED onto the entry itself.")
    public ResponseEntity<List<PlannedShiftResponse>> rosterComparison(
            @PathVariable UUID tenantId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "200") int limit) {

        List<RosterComparison> rows =
                plannedShifts.comparisonAt(tenantId, brandId, locationId, from, to, Math.min(limit, 500));
        Map<UUID, String> names = courierStore.displayReferencesOf(
                tenantId, rows.stream().map(row -> row.entry().courierId()).collect(Collectors.toSet()));
        return ResponseEntity.ok(rows.stream()
                .map(row -> PlannedShiftResponse.of(
                        row.entry(), names.get(row.entry().courierId()), row))
                .toList());
    }

    @PostMapping("/courier-roster-entries")
    @RequiresCapability(
            value = Capability.COURIER_SHIFT_APPROVE,
            scope = ResourceScope.ScopeType.LOCATION,
            mutating = true)
    @Operation(
            summary = "Plan a courier's shift ahead of time",
            description = "Lands as DRAFT. Refused when the courier has no live engagement, the "
                    + "same precondition a courier's own shift-open checks. brandId/locationId "
                    + "are query parameters, the same as the GET siblings of this route, and carry "
                    + "the scope this write is checked at.")
    public ResponseEntity<PlannedShiftResponse> draftRosterEntry(
            @PathVariable UUID tenantId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @Valid @RequestBody DraftRosterEntryRequest body) {

        PlannedShiftRow entry = plannedShifts.draft(new NewPlannedShift(
                tenantId,
                brandId,
                locationId,
                body.courierId(),
                body.plannedStart(),
                body.plannedEnd(),
                actorUuid(),
                actor(),
                body.reason()));
        Map<UUID, String> names = courierStore.displayReferencesOf(tenantId, Set.of(entry.courierId()));
        return ResponseEntity.ok(PlannedShiftResponse.of(entry, names.get(entry.courierId()), null));
    }

    @PostMapping("/courier-roster-entries/{entryId}/publish")
    @RequiresCapability(
            value = Capability.COURIER_SHIFT_APPROVE,
            scope = ResourceScope.ScopeType.LOCATION,
            mutating = true)
    @Operation(
            summary = "Publish a planned shift, making it a visible offer",
            description = "brandId/locationId are query parameters carrying the scope this write "
                    + "is checked at; the entry itself is refused as not-found (never merely "
                    + "forbidden, per ADR 0031) when it does not actually belong to that branch.")
    public ResponseEntity<Void> publishRosterEntry(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @Valid @RequestBody RosterEntryReasonRequest body) {

        plannedShifts.publish(tenantId, brandId, locationId, entryId, actor(), actorUuid(), body.reason());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/courier-roster-entries/{entryId}/cancel")
    @RequiresCapability(
            value = Capability.COURIER_SHIFT_APPROVE,
            scope = ResourceScope.ScopeType.LOCATION,
            mutating = true)
    @Operation(
            summary = "Cancel a planned shift that is still DRAFT or PUBLISHED",
            description = "brandId/locationId are query parameters carrying the scope this write "
                    + "is checked at; the entry itself is refused as not-found (never merely "
                    + "forbidden, per ADR 0031) when it does not actually belong to that branch.")
    public ResponseEntity<Void> cancelRosterEntry(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @Valid @RequestBody RosterEntryReasonRequest body) {

        plannedShifts.cancel(tenantId, brandId, locationId, entryId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------------- policy

    /**
     * Neither policy endpoint below carries {@code @RequiresCapability}, and
     * that absence is deliberate rather than an oversight the build-time scan
     * would have caught (see the two exemptions named for these paths in
     * {@code EndpointCapabilityDeclarationTests}).
     *
     * <p>{@code brandId}/{@code locationId} are optional request parameters —
     * omitting both resolves the tenant-wide document, and either widens or
     * narrows the resolution exactly as {@link #policyScope} computes. A
     * {@code @RequiresCapability(scope = ...)} declaration is one fixed
     * {@link uz.horecaos.platform.iam.api.ResourceScope.ScopeType} per method,
     * enforced by {@code CapabilityEnforcementInterceptor} from the request
     * before the handler ever runs. {@code TENANT} (the annotation's default)
     * is the only scope that never crashes here, because it is the only one
     * whose identifier — {@code tenantId} — is not optional; but a
     * TENANT-scoped enforcement check is never satisfied by a BRAND- or
     * LOCATION-scoped grant ({@link uz.horecaos.platform.iam.api.ResourceScope#covers}
     * only lets a broader scope reach a narrower one), so it silently refused
     * a BRAND_MANAGER calling with their own {@code brandId} even though
     * {@code PlatformRole} bundles {@code DELIVERY_POLICY_READ}/{@code
     * DELIVERY_POLICY_WRITE} into that role for exactly this call. Declaring
     * {@code BRAND} instead would fix that case but crash the tenant-wide one
     * ({@code brandId} omitted, per {@code
     * EndpointCapabilityDeclarationTests#aDeclaredScopeNamesOnlyPathVariablesOrRequestParametersTheRouteActuallyDeclares}),
     * and making {@code brandId} a required parameter to satisfy that test
     * would delete the tenant-wide read/write the frontend's scope ladder
     * depends on. So the check is made explicitly, against the same scope the
     * read or write actually resolves at, mirroring {@code
     * OperationsStreamController.authorize}'s per-channel {@code
     * authorization.require} call for the identical reason.
     */
    @GetMapping("/courier-policy")
    @Operation(
            summary = "The courier compensation policy in force (IA 3.9, settings.md §10.13/§16)",
            description = "Omit brandId/locationId for the tenant-wide resolution; supply either "
                    + "to see what a specific brand or location actually resolves. Wave P38 gave "
                    + "this document a writer beside this read (see PUT of the same path) and "
                    + "five new fields couriers.md §16 always named: the GPS master toggle with "
                    + "its accept and status-change radii, the kitchen-ready-only gate, when the "
                    + "customer's exact location is revealed, and the post-delivery payment "
                    + "check. Gap map row 3.3 added a seventh, onlineWithinMinutes, the roster's "
                    + "online threshold — the only one of the newer fields anything outside this "
                    + "controller actually reads (CourierRosterQueryService). Courier billing mode "
                    + "stays refused by ADR 0042 and has no field here; the telemetry collection "
                    + "gate is a separate, PLATFORM_ADMIN-only ADR 0030 key and is not part of "
                    + "this document. Authorization is checked "
                    + "against brandId/locationId's own resolved scope, not a fixed TENANT "
                    + "default, so a BRAND_MANAGER reading their own brand's policy is not "
                    + "refused for a grant the role bundle already gives them.")
    public ResponseEntity<CourierPolicyResponse> courierPolicy(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID locationId) {

        ResourceScope scope = policyScope(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_POLICY_READ, scope);
        ResolvedPolicy<CourierCompensationPolicy> resolved = policyResolver.resolveWithIdentity(scope);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(resolved.policyVersion()))
                .body(CourierPolicyResponse.of(resolved));
    }

    @PutMapping("/courier-policy")
    @Idempotent
    @Operation(
            summary = "Publish the next version of the courier compensation policy",
            description = "Whole-document replace: every field is required, because ADR 0030 "
                    + "versions the document as one unit rather than merging a partial write "
                    + "over the version it replaces. Omit brandId/locationId to publish the "
                    + "tenant-wide default; supply either to publish a brand or location "
                    + "override. The version this replaces is never touched — PolicyResolver.pinned "
                    + "keeps answering with it for whatever already resolved it. Authorization is "
                    + "checked against that same resolved scope (see the class-level doc on "
                    + "courierPolicy above), so a BRAND_MANAGER publishing their own brand's "
                    + "override is not refused for a grant the role bundle already gives them. "
                    + "Requires If-Match carrying the version the GET at this same scope returned "
                    + "(ADR 0031's concurrency section) — the document itself is append-only "
                    + "versioned and every write technically succeeds, so without this check two "
                    + "operators editing the same scope's policy from two open tabs would silently "
                    + "overwrite one another's fields with whatever their own stale form last held.")
    public ResponseEntity<CourierPolicyResponse> writeCourierPolicy(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID locationId,
            @Valid @RequestBody CourierPolicyWriteRequest body,
            HttpServletRequest request) {

        ResourceScope scope = policyScope(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_POLICY_WRITE, scope);

        long expectedVersion = AggregateVersion.requireIfMatch(request);
        ResolvedPolicy<CourierCompensationPolicy> current = policyResolver.resolveWithIdentity(scope);
        AggregateVersion.requireMatch(expectedVersion, current.policyVersion());

        CourierCompensationPolicy document = body.toDocument();
        ResolvedPolicy<CourierCompensationPolicy> published =
                policyAuthor.author(CourierPolicies.COMPENSATION, scope, document, actor(), body.reason());

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(published.policyVersion()))
                .body(CourierPolicyResponse.of(published));
    }

    /** Omit brandId/locationId for the tenant-wide scope; supply either for a brand or location override. */
    private static ResourceScope policyScope(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        return locationId != null && brandId != null
                ? ResourceScope.location(tenantId, brandId, locationId)
                : brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);
    }

    // ------------------------------------------------------------- engagement

    @PostMapping("/couriers")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Register a courier, open their engagement, and file what documents exist",
            description = "Opens in PENDING_VERIFICATION. Onboarding somebody and attesting to "
                    + "their registration are different acts by different people, and this call "
                    + "deliberately cannot do the second. The compliance fields are optional and "
                    + "may be filed here or later: an incomplete file is a state the roster is "
                    + "built to show, and refusing the registration over a missing passport would "
                    + "only teach operators to type something into the box. Gap map row 3.3: the "
                    + "courier's own account at the identity provider is created here, the same "
                    + "phone-first path ADR 0116's staff invitation uses (CourierAccountProvisioningService) — "
                    + "the operator no longer types a Keycloak subject; there is no field for one.")
    public ResponseEntity<CourierResponse> register(
            @PathVariable UUID tenantId, @Valid @RequestBody RegisterCourierRequest body) {

        CourierAccountProvisioningService.Provisioned account = accountProvisioning.provision(
                tenantId, body.firstName(), body.lastName(), body.phone(), blankToNull(body.email()), actor());

        CourierEngagementService.Registration registration = engagements.register(
                new CourierEngagementService.NewCourier(
                        tenantId,
                        body.courierTypeId(),
                        account.subjectId(),
                        body.displayReference(),
                        body.fullName(),
                        body.engagedFrom(),
                        actor(),
                        body.reason(),
                        correlationId()),
                body.compliance());

        return ResponseEntity.ok(
                new CourierResponse(registration.courierId(), registration.engagementId(), "PENDING_VERIFICATION"));
    }

    @PostMapping("/courier-engagements/{engagementId}/verify")
    @RequiresCapability(value = Capability.COURIER_REGISTRATION_VERIFY, mutating = true)
    @Operation(
            summary = "Attest that the registration evidence was sighted",
            description = "Activates the engagement. The identifier is stored under ADR 0029 "
                    + "envelope encryption and can never be queried; the validity dates are held "
                    + "in clear so that \"who expires this month\" has an answer.")
    public ResponseEntity<EngagementResponse> verify(
            @PathVariable UUID tenantId, @PathVariable UUID engagementId, @Valid @RequestBody VerifyRequest body) {

        var engagement = engagements.verify(new CourierEngagementService.VerifyRegistration(
                tenantId,
                engagementId,
                body.registrationIdentifier(),
                body.validUntil(),
                VerificationMethod.valueOf(body.method()),
                body.evidenceMediaId(),
                actor(),
                body.reason(),
                correlationId()));

        return ResponseEntity.ok(new EngagementResponse(
                engagement.id(),
                engagement.status().name(),
                engagement.warningState().name(),
                engagement.registrationValidUntil(),
                engagement.reverificationDueOn()));
    }

    @PostMapping("/courier-engagements/{engagementId}/suspend")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(summary = "Suspend an engagement for an operational reason")
    public ResponseEntity<Void> suspend(
            @PathVariable UUID tenantId, @PathVariable UUID engagementId, @Valid @RequestBody SuspendRequest body) {

        engagements.suspend(tenantId, engagementId, body.reasonCode(), actor(), body.reason(), correlationId());
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------------ shift

    @PostMapping("/courier-shifts/{shiftId}/close")
    @RequiresCapability(value = Capability.COURIER_SHIFT_APPROVE, mutating = true)
    @Operation(
            summary = "Close a courier's shift, with a reason",
            description = "Permitted because ending service, closing the premises and safety are "
                    + "the tenant's to decide. The reason is recorded, and the hours land in "
                    + "AWAITING_APPROVAL rather than paying themselves.")
    public ResponseEntity<Void> closeShift(
            @PathVariable UUID tenantId, @PathVariable UUID shiftId, @Valid @RequestBody ManagerCloseRequest body) {

        shifts.close(new CourierShiftService.CloseShift(
                tenantId,
                shiftId,
                ShiftActor.MANAGER,
                actor(),
                body.reasonCode(),
                body.reason(),
                null,
                body.currency()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/courier-shifts/{shiftId}/approve")
    @RequiresCapability(value = Capability.COURIER_SHIFT_APPROVE, mutating = true)
    @Operation(summary = "Approve a shift's hours")
    public ResponseEntity<Void> approveShift(
            @PathVariable UUID tenantId, @PathVariable UUID shiftId, @Valid @RequestBody ApproveHoursRequest body) {

        shifts.approveHours(tenantId, shiftId, body.approvalRequestId(), actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/cash-handovers")
    @RequiresCapability(Capability.COURIER_CASH_READ)
    @Operation(
            summary = "The fleet's cash handover worklist — Finance 8.3",
            description = "PENDING and DECLARED first, largest expected amount first: the "
                    + "handovers most worth a cashier's attention before the shift's courier "
                    + "leaves. Optionally filtered to one branch or one status.")
    public ResponseEntity<List<CashHandoverResponse>> cashHandovers(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "100") int limit) {

        List<HandoverRow> rows = shiftStore.listHandovers(tenantId, status, locationId, Math.min(limit, 500));
        Map<UUID, String> names = courierStore.displayReferencesOf(
                tenantId, rows.stream().map(HandoverRow::courierId).collect(Collectors.toSet()));
        Map<UUID, ShiftCashSummary> summaries = ledger.cashSummaryByShift(
                tenantId, rows.stream().map(HandoverRow::shiftId).collect(Collectors.toSet()));
        return ResponseEntity.ok(rows.stream()
                .map(row -> CashHandoverResponse.of(row, names.get(row.courierId()), summaries.get(row.shiftId())))
                .toList());
    }

    @PostMapping("/cash-handovers/{handoverId}/confirm")
    @RequiresCapability(value = Capability.COURIER_CASH_CONFIRM, mutating = true)
    @Operation(summary = "Confirm the cash actually received")
    public ResponseEntity<Void> confirmCash(
            @PathVariable UUID tenantId, @PathVariable UUID handoverId, @Valid @RequestBody ConfirmCashRequest body) {

        cash.confirm(tenantId, handoverId, body.confirmedMinor(), body.reasonCode(), actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------ adjustments

    @PostMapping("/couriers/{courierId}/adjustments")
    @RequiresCapability(value = Capability.COURIER_ADJUSTMENT_CREATE, mutating = true)
    @Operation(
            summary = "Record a bonus or a penalty",
            description = "A manual penalty is never written on this call alone: it returns the "
                    + "approval request and writes nothing until a second person decides. "
                    + "origin stays on the request for wire compatibility but this endpoint never "
                    + "reads it: every adjustment reaching HorecaOS over HTTP is MANUAL, "
                    + "unconditionally, whatever the field says. Before this wave the origin the "
                    + "caller sent controlled the stamped value, and a caller sending RULE bypassed "
                    + "the four-eyes branch a MANUAL penalty above threshold requires (ADR 0108) — "
                    + "RULE now exists only as a value AdjustmentRuleEvaluator's own Java call "
                    + "constructs, never as something an HTTP request can cause.")
    public ResponseEntity<AdjustmentResponse> adjust(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @Valid @RequestBody AdjustmentRequest body) {

        CourierAdjustmentService.Outcome outcome = adjustments.request(new CourierAdjustmentService.AdjustmentCommand(
                tenantId,
                courierId,
                body.locationId(),
                body.amountMinor(),
                body.currency(),
                body.reasonCode(),
                AdjustmentOrigin.MANUAL,
                body.idempotencyKey(),
                actor(),
                body.reason(),
                correlationId()));

        JdbcCourierLedgerStore.LedgerEntryRow entry = outcome.entry();
        return ResponseEntity.ok(new AdjustmentResponse(
                entry == null ? null : entry.id(), outcome.approvalRequestId(), outcome.written()));
    }

    // ------------------------------------------------------ adjustment reasons

    @GetMapping("/adjustment-reasons")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(
            summary = "The bonus/penalty registry (IA 3.4, ADR 0108)",
            description = "Every reason this tenant has authored, manual-only and rule-wired "
                    + "alike — hasRule says which. Read under courier.read: a dispatcher choosing "
                    + "a reason on the manual-entry form needs this list and does not need "
                    + "courier.adjustment.reason.manage to see it.")
    public ResponseEntity<List<AdjustmentReasonResponse>> adjustmentReasons(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(courierStore.listAdjustmentReasons(tenantId).stream()
                .map(AdjustmentReasonResponse::of)
                .toList());
    }

    @PostMapping("/adjustment-reasons")
    @RequiresCapability(value = Capability.COURIER_ADJUSTMENT_REASON_MANAGE, mutating = true)
    @Operation(
            summary = "Define a bonus/penalty reason, manual-only or rule-wired (ADR 0108)",
            description = "outcomeBasis is closed (ADR 0042): every code names a delivery outcome, "
                    + "never a behaviour. Omit every rule* field for a manual-only reason; supply "
                    + "all five to wire it to AdjustmentRuleEvaluator — ruleAmountMinor's sign must "
                    + "match kind, and ORDER_UNDELIVERED/ORDER_DAMAGED have no evaluator reader and "
                    + "may only be authored manual-only.")
    public ResponseEntity<AdjustmentReasonResponse> createAdjustmentReason(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateAdjustmentReasonRequest body) {

        UUID reasonId = UUID.randomUUID();
        courierStore.insertAdjustmentReason(
                reasonId, tenantId, body.code(), body.kind(), body.outcomeBasis(), body.displayName(), body.toRule());

        return ResponseEntity.ok(AdjustmentReasonResponse.of(
                courierStore.findAdjustmentReason(tenantId, body.code()).orElseThrow()));
    }

    @PostMapping("/adjustment-reasons/{reasonId}/archival")
    @RequiresCapability(value = Capability.COURIER_ADJUSTMENT_REASON_MANAGE, mutating = true)
    @Operation(
            summary = "Archive a bonus/penalty reason",
            description = "Archived, never deleted: a ledger entry still names its code. A "
                    + "rule-wired reason stops evaluating the moment it archives, because "
                    + "ruleReasonsAt reads status = 'ACTIVE'.")
    public ResponseEntity<Void> archiveAdjustmentReason(
            @PathVariable UUID tenantId,
            @PathVariable UUID reasonId,
            @Valid @RequestBody ArchiveCourierTypeRequest body) {

        courierTypes.archiveAdjustmentReason(tenantId, reasonId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/couriers/{courierId}/ledger")
    @RequiresCapability(Capability.COURIER_LEDGER_READ)
    @Operation(
            summary = "A courier's ledger",
            description = "One balance, not a wage balance and a cash balance. A courier holding "
                    + "the tenant's cash while being owed for deliveries is one net position.")
    public ResponseEntity<LedgerResponse> ledgerOf(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @RequestParam(defaultValue = "100") int limit) {

        List<JdbcCourierLedgerStore.LedgerEntryRow> entries =
                ledger.entriesOfCourier(tenantId, courierId, Math.min(limit, 500));
        return ResponseEntity.ok(new LedgerResponse(
                ledger.balanceMinor(tenantId, courierId),
                // entriesOfCourier orders newest first; the most recent entry's
                // currency is the balance's, and null only for a courier with no
                // ledger entries at all, whose balance is zero regardless.
                entries.isEmpty() ? null : entries.getFirst().currency(),
                entries.stream()
                        .map(entry -> new LedgerLine(
                                entry.id(),
                                entry.entryType().name(),
                                entry.amountMinor(),
                                entry.currency(),
                                entry.reasonCode(),
                                entry.occurredAt().toString()))
                        .toList()));
    }

    // ------------------------------------------------------------- settlement

    @GetMapping("/courier-settlement-periods")
    @RequiresCapability(Capability.COURIER_SETTLEMENT_READ)
    @Operation(
            summary = "Every settlement period across the fleet — Finance 8.5's payout worklist",
            description = "CLOSED periods (statement hashed, payout not yet authorised) sort "
                    + "first by the largest amount payable. Optionally filtered to one status.")
    public ResponseEntity<List<SettlementPeriodResponse>> settlementPeriods(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {

        SettlementPeriodStatus parsed = status == null ? null : parseSettlementStatus(status);
        List<JdbcCourierLedgerStore.PeriodRow> rows = ledger.listPeriods(tenantId, parsed, Math.min(limit, 500));
        Map<UUID, AdjustmentSplit> splits = ledger.adjustmentSplitByPeriod(
                tenantId,
                rows.stream().map(JdbcCourierLedgerStore.PeriodRow::id).collect(Collectors.toSet()));
        return ResponseEntity.ok(rows.stream()
                .map(row -> SettlementPeriodResponse.of(row, splits.get(row.id())))
                .toList());
    }

    @PostMapping("/courier-settlement-periods/{periodId}/close")
    @RequiresCapability(value = Capability.COURIER_SETTLEMENT_CLOSE, mutating = true)
    @Operation(
            summary = "Close a settlement period and hash its statement",
            description = "A closed period is never reopened. Anything arriving afterwards lands "
                    + "in the next one as a prior-period adjustment keeping its original instant.")
    public ResponseEntity<StatementResponse> closePeriod(
            @PathVariable UUID tenantId, @PathVariable UUID periodId, @Valid @RequestBody CloseperiodRequest body) {

        CourierSettlementService.Statement statement = settlement.close(tenantId, periodId, actor(), body.reason());
        return ResponseEntity.ok(new StatementResponse(
                statement.periodId(),
                statement.statementHash(),
                statement.totals().amountPayableMinor(),
                statement.complianceFlag()));
    }

    @GetMapping("/courier-settlement-periods/{periodId}/statement")
    @RequiresCapability(Capability.COURIER_SETTLEMENT_CLOSE)
    @Operation(
            summary = "The stored statement",
            description = "Read back, never recomputed. Gross only: no withholding line and no "
                    + "net-of-tax line anywhere on it.")
    public ResponseEntity<Map<String, Object>> statement(@PathVariable UUID tenantId, @PathVariable UUID periodId) {

        return ResponseEntity.ok(settlement.statementOf(tenantId, periodId));
    }

    @PostMapping("/courier-settlement-periods/{periodId}/payouts")
    @RequiresCapability(value = Capability.COURIER_PAYOUT_AUTHORISE, mutating = true)
    @Operation(
            summary = "Authorise the payout for a closed period",
            description = "A period carrying the compliance flag needs four eyes first. The "
                    + "courier is paid either way: the work was done and the money is owed.")
    public ResponseEntity<PayoutResponse> authorisePayout(
            @PathVariable UUID tenantId, @PathVariable UUID periodId, @Valid @RequestBody PayoutRequest body) {

        CourierSettlementService.PayoutOutcome outcome = settlement.authorisePayout(
                tenantId, periodId, PayoutMethod.valueOf(body.method()), actor(), body.reason());
        return ResponseEntity.ok(
                new PayoutResponse(outcome.payoutId(), outcome.approvalRequestId(), outcome.authorised()));
    }

    // --------------------------------------------------------- delivery cost

    @GetMapping("/delivery-costs")
    @RequiresCapability(Capability.DELIVERY_COST_READ)
    @Operation(
            summary = "Delivery cost at a stated basis",
            description = "The basis is required. Two lines and a total, never one number: an "
                    + "in-house accrual and a partner invoice are recognised at different "
                    + "instants and rest on different tax documents.")
    public ResponseEntity<DeliveryCostQueryService.CostReport> deliveryCosts(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String basis,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(deliveryCosts.report(tenantId, parseBasis(basis), from, to));
    }

    @GetMapping("/partner-delivery-invoices")
    @RequiresCapability(Capability.PARTNER_INVOICE_READ)
    @Operation(
            summary = "Every imported partner delivery invoice — Finance 8.4's reconciliation worklist",
            description = "IMPORTED (not yet matched against HorecaOS's own shipments) sorts "
                    + "first, largest total first. Optionally filtered to one status.")
    public ResponseEntity<List<PartnerInvoiceResponse>> partnerInvoices(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {

        return ResponseEntity.ok(deliveryCostStore.listInvoices(tenantId, status, Math.min(limit, 500)).stream()
                .map(PartnerInvoiceResponse::of)
                .toList());
    }

    @GetMapping("/partner-delivery-invoices/{invoiceId}")
    @RequiresCapability(Capability.PARTNER_INVOICE_READ)
    @Operation(summary = "One partner invoice, with its lines and their match state")
    public ResponseEntity<PartnerInvoiceDetailResponse> partnerInvoiceDetail(
            @PathVariable UUID tenantId, @PathVariable UUID invoiceId) {

        InvoiceRow invoice = deliveryCostStore
                .findInvoice(tenantId, invoiceId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No invoice %s for this tenant".formatted(invoiceId)));
        List<InvoiceLineRow> lines = deliveryCostStore.linesOfInvoice(tenantId, invoiceId);
        return ResponseEntity.ok(PartnerInvoiceDetailResponse.of(invoice, lines));
    }

    @PostMapping("/partner-delivery-invoices")
    @RequiresCapability(value = Capability.PARTNER_INVOICE_MANAGE, mutating = true)
    @Operation(summary = "Import a partner delivery invoice")
    public ResponseEntity<Map<String, UUID>> importInvoice(
            @PathVariable UUID tenantId, @Valid @RequestBody ImportInvoiceRequest body) {

        UUID invoiceId = partnerInvoices.importInvoice(new PartnerInvoiceService.ImportInvoice(
                tenantId,
                body.providerCode(),
                body.providerInvoiceRef(),
                body.legalEntityId(),
                body.periodStart(),
                body.periodEnd(),
                body.totalMinor(),
                body.currency(),
                body.lines().stream()
                        .map(line -> new PartnerInvoiceService.ImportedLine(
                                line.providerShipmentRef(),
                                line.amountMinor(),
                                PartnerChargeType.valueOf(line.chargeType())))
                        .toList(),
                actor(),
                body.reason()));

        return ResponseEntity.ok(Map.of("invoiceId", invoiceId));
    }

    @PostMapping("/partner-delivery-invoices/{invoiceId}/match")
    @RequiresCapability(value = Capability.PARTNER_INVOICE_MANAGE, mutating = true)
    @Operation(
            summary = "Reconcile an imported invoice against HorecaOS's shipments",
            description = "Reports UNMATCHED_LINE explicitly. A line the partner billed for a "
                    + "shipment HorecaOS has no record of is never netted into any total.")
    public ResponseEntity<PartnerInvoiceService.MatchReport> match(
            @PathVariable UUID tenantId, @PathVariable UUID invoiceId, @Valid @RequestBody MatchRequest body) {

        return ResponseEntity.ok(
                partnerInvoices.match(tenantId, invoiceId, body.shipmentsByProviderRef(), actor(), body.reason()));
    }

    @PostMapping("/partner-delivery-invoices/{invoiceId}/dispute")
    @RequiresCapability(value = Capability.PARTNER_INVOICE_MANAGE, mutating = true)
    @Operation(
            summary = "Flag an invoice for pushback to the partner — the акт сверки dispute path",
            description = "Refused once the invoice is PAID. Disputing records the operator's "
                    + "decision; it settles nothing with the partner itself.")
    public ResponseEntity<Void> disputeInvoice(
            @PathVariable UUID tenantId, @PathVariable UUID invoiceId, @Valid @RequestBody DisputeInvoiceRequest body) {

        partnerInvoices.disputeInvoice(tenantId, invoiceId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/partner-delivery-invoices/{invoiceId}/lines/{lineId}/variance-acceptance")
    @RequiresCapability(value = Capability.PARTNER_INVOICE_MANAGE, mutating = true)
    @Operation(
            summary = "Accept or dispute one VARIANCE line",
            description = "The only two dispositions a VARIANCE row can carry: pay the partner's "
                    + "charge as invoiced, or flag it disputed. match_status stays VARIANCE either "
                    + "way — it is a fact about the money — only variance_resolution changes.")
    public ResponseEntity<PartnerInvoiceLineResponse> resolveVariance(
            @PathVariable UUID tenantId,
            @PathVariable UUID invoiceId,
            @PathVariable UUID lineId,
            @Valid @RequestBody VarianceAcceptanceRequest body) {

        return ResponseEntity.ok(PartnerInvoiceLineResponse.of(
                partnerInvoices.resolveVariance(tenantId, invoiceId, lineId, body.accept(), actor(), body.reason())));
    }

    @PostMapping("/shipments/{shipmentId}/external-delivery-cost/reconcile")
    @RequiresCapability(value = Capability.PARTNER_INVOICE_MANAGE, mutating = true)
    @Operation(
            summary = "T11 7.4c: the per-order external-delivery-cost report's per-line reconcile action",
            description = "Marks the shipment's DELIVERY-charge invoice line MATCHED when one exists. "
                    + "A shipment with no invoice line at all is genuinely UNBILLED -- nothing to "
                    + "reconcile against yet -- and the acknowledgement is recorded on the audit trail "
                    + "alone; reconciled is false on that response. Refuses (UNPROCESSABLE_STATE) a line "
                    + "that is VARIANCE: that money discrepancy is disposed of only through the "
                    + "variance-acceptance endpoint's accept/dispute choice, never by this action.")
    public ResponseEntity<ReconcileShipmentResponse> reconcileExternalDeliveryCost(
            @PathVariable UUID tenantId,
            @PathVariable UUID shipmentId,
            @Valid @RequestBody ReconcileShipmentRequest body) {

        boolean reconciled = partnerInvoices.reconcileShipment(tenantId, shipmentId, actor(), body.reason());
        return ResponseEntity.ok(new ReconcileShipmentResponse(reconciled));
    }

    private static SettlementPeriodStatus parseSettlementStatus(String status) {
        try {
            return SettlementPeriodStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown settlement period status: " + status);
        }
    }

    private static @Nullable CostBasis parseBasis(@Nullable String basis) {
        if (basis == null || basis.isBlank()) {
            // Handed to the service as null so the refusal, and its wording, live
            // in one place rather than being duplicated per transport.
            return null;
        }
        try {
            return CostBasis.valueOf(basis);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown delivery cost basis: " + basis);
        }
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /**
     * The caller's own authenticated identity as a {@code created_by}/{@code
     * published_by} column expects it. Production Keycloak subjects are UUIDs;
     * a service account or malformed token fails loudly here rather than
     * writing a fabricated identity onto a roster row.
     */
    private UUID actorUuid() {
        return UUID.fromString(currentActor.get().subject());
    }

    private String correlationId() {
        return org.slf4j.MDC.get("correlationId") == null
                ? UUID.randomUUID().toString()
                : org.slf4j.MDC.get("correlationId");
    }

    // --------------------------------------------------------------- payloads

    /**
     * The widened register form (IA 3.3).
     *
     * <p>No {@code principalSubject} field, unlike before gap map row {@code
     * 3.3}'s own fix: an operator no longer types a Keycloak subject created
     * outside this console. {@link #register} provisions the account itself
     * ({@link CourierAccountProvisioningService}, ADR 0116's phone-first
     * path) from {@link #firstName}/{@link #lastName}/{@link #phone}/{@link
     * #email} and uses the identity it gets back — never a value this body
     * could name, so there is no way to bind a courier to somebody else's
     * account.
     *
     * <p>Every compliance field is optional and none of them is echoed back by
     * any response on this controller.
     */
    record RegisterCourierRequest(
            @NotNull UUID courierTypeId,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(min = 9, max = 20) String phone,
            @Email @Size(max = 255) @Nullable String email,
            @NotBlank @Size(max = 32) String displayReference,
            @NotNull LocalDate engagedFrom,
            @Nullable @Size(max = 32) String passport,
            @Nullable @Size(max = 32) String pinfl,
            @Nullable @Size(max = 32) String drivingLicence,
            @Nullable @Size(max = 64) String vehicleRegistration,
            @Nullable @Size(max = 32) String vehiclePlate,
            @Nullable @Size(max = 24) String vehicleFuelType,
            @Nullable UUID photoMediaId,
            @Nullable @Size(max = 512) String homeAddress,
            @Nullable @Size(max = 256) String emergencyContact,
            @Nullable @Size(max = 256) String referral,
            @Nullable @Size(max = 2000) String remarks,
            @NotBlank String reason) {

        /** The one place {@link #firstName}/{@link #lastName} are joined for a human to read. */
        String fullName() {
            return (firstName.strip() + " " + lastName.strip()).strip();
        }

        CourierEngagementService.ComplianceFile compliance() {
            Map<ComplianceField, String> recorded = new EnumMap<>(ComplianceField.class);
            put(recorded, ComplianceField.PASSPORT, passport);
            put(recorded, ComplianceField.PINFL, pinfl);
            put(recorded, ComplianceField.DRIVING_LICENCE, drivingLicence);
            put(recorded, ComplianceField.VEHICLE_REGISTRATION, vehicleRegistration);
            put(recorded, ComplianceField.VEHICLE_PLATE, vehiclePlate);
            put(recorded, ComplianceField.ADDRESS, homeAddress);
            put(recorded, ComplianceField.EMERGENCY_CONTACT, emergencyContact);
            put(recorded, ComplianceField.REFERRAL, referral);
            put(recorded, ComplianceField.NOTES, remarks);
            return new CourierEngagementService.ComplianceFile(
                    recorded, Set.of(), blankToNull(vehicleFuelType), photoMediaId);
        }
    }

    /**
     * A compliance-file write.
     *
     * <p>Three-valued, and it has to be: a field sent is written, a field named
     * in {@code clear} is removed, a field mentioned in neither is untouched.
     * See {@code CourierEngagementService.recordComplianceFile} for why a
     * two-valued API would erase a passport every time somebody fixed a plate.
     *
     * <p>The free-text field is called {@code remarks} rather than {@code notes}
     * throughout this controller for a mechanical reason worth stating: ADR
     * 0029's classifier flags a response component whose name contains "note",
     * and a request record sharing a name with a response is how that check gets
     * argued with instead of obeyed. The column is still {@code protected_notes}
     * and the field is still {@link ComplianceField#NOTES}.
     */
    record CourierComplianceFileRequest(
            @Nullable @Size(max = 32) String passport,
            @Nullable @Size(max = 32) String pinfl,
            @Nullable @Size(max = 32) String drivingLicence,
            @Nullable @Size(max = 64) String vehicleRegistration,
            @Nullable @Size(max = 32) String vehiclePlate,
            @Nullable @Size(max = 24) String vehicleFuelType,
            @Nullable UUID photoMediaId,
            @Nullable @Size(max = 512) String homeAddress,
            @Nullable @Size(max = 256) String emergencyContact,
            @Nullable @Size(max = 256) String referral,
            @Nullable @Size(max = 2000) String remarks,
            @Nullable List<String> clear,
            @NotBlank String reason) {

        CourierEngagementService.ComplianceFile toCommand() {
            Map<ComplianceField, String> recorded = new EnumMap<>(ComplianceField.class);
            put(recorded, ComplianceField.PASSPORT, passport);
            put(recorded, ComplianceField.PINFL, pinfl);
            put(recorded, ComplianceField.DRIVING_LICENCE, drivingLicence);
            put(recorded, ComplianceField.VEHICLE_REGISTRATION, vehicleRegistration);
            put(recorded, ComplianceField.VEHICLE_PLATE, vehiclePlate);
            put(recorded, ComplianceField.ADDRESS, homeAddress);
            put(recorded, ComplianceField.EMERGENCY_CONTACT, emergencyContact);
            put(recorded, ComplianceField.REFERRAL, referral);
            put(recorded, ComplianceField.NOTES, remarks);

            Set<ComplianceField> cleared = EnumSet.noneOf(ComplianceField.class);
            for (String name : clear == null ? List.<String>of() : clear) {
                cleared.add(parseComplianceField(name));
            }
            // A field both written and cleared in one call is a contradiction,
            // and silently letting one win would be worse than refusing.
            Set<ComplianceField> both = EnumSet.copyOf(cleared);
            both.retainAll(recorded.keySet());
            if (!both.isEmpty()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "A field cannot be recorded and cleared in the same request");
            }

            return new CourierEngagementService.ComplianceFile(
                    recorded, cleared, blankToNull(vehicleFuelType), photoMediaId);
        }
    }

    record CreateCourierGroupRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String reason) {}

    record CourierGroupIdResponse(UUID groupId) {}

    record CourierRosterReasonRequest(@NotBlank String reason) {}

    record CourierGroupMembershipRequest(
            @NotNull UUID groupId, @NotBlank String reason) {}

    record CourierBranchBindingRequest(
            @NotNull UUID brandId,
            @NotNull UUID locationId,
            boolean primary,
            @NotBlank String reason) {}

    record CourierGroupResponse(UUID groupId, String code, String displayName, String status, int memberCount) {

        static CourierGroupResponse of(CourierGroupRow row) {
            return new CourierGroupResponse(row.id(), row.code(), row.displayName(), row.status(), row.memberCount());
        }
    }

    record CourierBranchBindingResponse(UUID locationId, UUID brandId, String locationName, boolean primary) {

        static CourierBranchBindingResponse of(BranchBindingRow row) {
            return new CourierBranchBindingResponse(row.locationId(), row.brandId(), row.locationName(), row.primary());
        }
    }

    /**
     * One courier's detail pane (IA 3.3).
     *
     * <p>{@code complianceFieldsOnFile} is the whole of what this response says
     * about the documents: their names, never their contents. A manager chasing
     * an expiring licence needs to know the field is empty, and needs no reveal
     * to learn it.
     */
    record CourierDetailResponse(
            UUID courierId,
            String displayReference,
            String status,
            UUID courierTypeId,
            String courierTypeName,
            String vehicleClass,
            int activeAssignments,
            int concurrencyCeiling,
            @Nullable UUID engagementId,
            @Nullable String engagementStatus,
            @Nullable String warningState,
            @Nullable LocalDate reverificationDueOn,
            boolean online,
            @Nullable Instant lastSeenAt,
            List<String> complianceFieldsOnFile,
            @Nullable String vehicleFuelType,
            @Nullable UUID photoMediaId,
            @Nullable Instant complianceUpdatedAt,
            List<CourierGroupResponse> groups,
            List<CourierBranchBindingResponse> branches) {

        static CourierDetailResponse of(CourierRosterQueryService.CourierDetail detail) {
            var courier = detail.entry().courier();
            var compliance = detail.compliance();
            return new CourierDetailResponse(
                    courier.id(),
                    courier.displayReference(),
                    courier.status(),
                    courier.courierTypeId(),
                    courier.courierTypeName(),
                    courier.vehicleClass(),
                    detail.entry().activeAssignments(),
                    courier.maxConcurrentAssignments(),
                    courier.engagementId(),
                    courier.engagementStatus(),
                    courier.warningState(),
                    courier.reverificationDueOn(),
                    detail.entry().online(),
                    detail.entry().lastSeenAt(),
                    compliance.onFile().stream().map(Enum::name).sorted().toList(),
                    compliance.vehicleFuelType(),
                    compliance.photoMediaId(),
                    compliance.complianceUpdatedAt(),
                    detail.groups().stream().map(CourierGroupResponse::of).toList(),
                    detail.branches().stream()
                            .map(CourierBranchBindingResponse::of)
                            .toList());
        }
    }

    /**
     * The revealed file (ADR 0029).
     *
     * <p>A list of field/value pairs rather than eleven named components, for one
     * reason: a field with nothing on file is absent from the list, and a record
     * of nullable components cannot express that difference — every absent
     * document would arrive as an explicit null, and a screen rendering the
     * response would show empty boxes where it should show nothing at all.
     *
     * <p>This is a {@code GET} and therefore never enters the idempotency
     * record's stored body; the values here exist only for the duration of the
     * response the caller asked for by name, with a purpose, under audit.
     */
    record CourierComplianceRevealResponse(List<RevealedComplianceField> fields) {

        static CourierComplianceRevealResponse of(Map<ComplianceField, String> revealed) {
            return new CourierComplianceRevealResponse(revealed.entrySet().stream()
                    .map(entry -> new RevealedComplianceField(entry.getKey().name(), entry.getValue()))
                    .toList());
        }
    }

    record RevealedComplianceField(String field, String value) {}

    private static void put(Map<ComplianceField, String> target, ComplianceField field, @Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null) {
            target.put(field, trimmed);
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ComplianceField parseComplianceField(String name) {
        try {
            return ComplianceField.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown compliance field: " + name);
        }
    }

    record VerifyRequest(
            @NotBlank String registrationIdentifier,
            @NotNull LocalDate validUntil,
            @NotBlank String method,
            UUID evidenceMediaId,
            @NotBlank String reason) {}

    record SuspendRequest(
            @NotBlank @Size(max = 48) String reasonCode,
            @NotBlank String reason) {}

    record ManagerCloseRequest(
            @NotBlank @Size(max = 48) String reasonCode,
            @NotBlank String reason,
            @Size(min = 3, max = 3) String currency) {}

    record ApproveHoursRequest(
            UUID approvalRequestId, @NotBlank String reason) {}

    record ConfirmCashRequest(
            long confirmedMinor,
            @Size(max = 48) String reasonCode,
            @NotBlank String reason) {}

    /**
     * A manual bonus or penalty.
     *
     * <p>{@code origin} stays on the wire — the OpenAPI contract test refuses
     * to make a published required field optional or remove it, and this one
     * has been required since before this wave — but {@link #adjust} never
     * reads it. Whatever value a caller sends, including {@code "RULE"}, is
     * accepted and discarded; see that method's own doc for the gap this
     * closes.
     */
    record AdjustmentRequest(
            UUID locationId,
            long amountMinor,
            @Size(min = 3, max = 3) String currency,
            @NotBlank String reasonCode,
            @NotBlank String origin,
            @NotBlank String idempotencyKey,
            @NotBlank String reason) {}

    /**
     * One row of the bonus/penalty registry (ADR 0108). {@code hasRule} is a
     * courier-typed convenience over "every rule* field is non-null"; the
     * six are omitted individually here for the same reason the response
     * carries {@code hasRule} rather than making a caller check nullness six
     * times to answer one boolean question.
     */
    record AdjustmentReasonResponse(
            UUID reasonId,
            String code,
            String kind,
            String outcomeBasis,
            String displayName,
            String status,
            boolean hasRule,
            @Nullable Long ruleAmountMinor,
            @Nullable String ruleCurrency,
            @Nullable String ruleComparator,
            @Nullable Long ruleThreshold,
            @Nullable String ruleWindow,
            @Nullable String ruleTrigger,
            int ruleVersion) {

        static AdjustmentReasonResponse of(JdbcCourierStore.AdjustmentReasonRow row) {
            return new AdjustmentReasonResponse(
                    row.id(),
                    row.code(),
                    row.kind(),
                    row.outcomeBasis(),
                    row.displayName(),
                    row.status(),
                    row.hasRule(),
                    row.ruleAmountMinor(),
                    row.ruleCurrency(),
                    row.ruleComparator(),
                    row.ruleThreshold(),
                    row.ruleWindow(),
                    row.ruleTrigger(),
                    row.ruleVersion());
        }
    }

    /**
     * Defines a reason. Every {@code rule*} field is optional and they arrive
     * together or not at all: supplying some but not others is refused before
     * this ever reaches the database's own {@code
     * ck_adjustment_reason_rule_pair}, so the caller sees ADR 0031's
     * vocabulary rather than a constraint-violation message.
     */
    record CreateAdjustmentReasonRequest(
            @NotBlank @Size(max = 48) String code,
            @NotBlank String kind,
            @NotBlank String outcomeBasis,
            @NotBlank @Size(max = 160) String displayName,
            @Nullable Long ruleAmountMinor,
            @Nullable @Size(min = 3, max = 3) String ruleCurrency,
            @Nullable String ruleComparator,
            @Nullable Long ruleThreshold,
            @Nullable String ruleWindow,
            @Nullable String ruleTrigger) {

        JdbcCourierStore.@Nullable RuleConfig toRule() {
            List<Object> present = Arrays.asList(
                    ruleAmountMinor, ruleCurrency, ruleComparator, ruleThreshold, ruleWindow, ruleTrigger);
            long presentCount = present.stream().filter(Objects::nonNull).count();
            if (presentCount == 0) {
                return null;
            }
            if (presentCount < present.size()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "A rule needs all of ruleAmountMinor, ruleCurrency, ruleComparator, "
                                + "ruleThreshold, ruleWindow and ruleTrigger, or none of them");
            }
            return new JdbcCourierStore.RuleConfig(
                    Objects.requireNonNull(ruleAmountMinor),
                    Objects.requireNonNull(ruleCurrency),
                    Objects.requireNonNull(ruleComparator),
                    Objects.requireNonNull(ruleThreshold),
                    Objects.requireNonNull(ruleWindow),
                    Objects.requireNonNull(ruleTrigger));
        }
    }

    record CloseperiodRequest(@NotBlank String reason) {}

    record PayoutRequest(@NotBlank String method, @NotBlank String reason) {}

    record ImportInvoiceRequest(
            @NotBlank String providerCode,
            @NotBlank String providerInvoiceRef,
            UUID legalEntityId,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            long totalMinor,
            @Size(min = 3, max = 3) String currency,
            @NotNull List<ImportInvoiceLine> lines,
            @NotBlank String reason) {}

    record ImportInvoiceLine(
            @NotBlank String providerShipmentRef,
            long amountMinor,
            @NotBlank String chargeType) {}

    record MatchRequest(
            @NotNull Map<String, UUID> shipmentsByProviderRef,
            @NotBlank String reason) {}

    record DisputeInvoiceRequest(@NotBlank String reason) {}

    /** @param accept true pays the partner's charge as invoiced; false disputes it. */
    record VarianceAcceptanceRequest(
            boolean accept, @NotBlank String reason) {}

    record ReconcileShipmentRequest(@NotBlank String reason) {}

    record ReconcileShipmentResponse(boolean reconciled) {}

    /**
     * One roster row on the wire. No name field exists here at all — not even
     * a masked one — because there is nothing decrypted to mask; see {@link
     * Capability#COURIER_READ}'s own doc for why {@code displayReference} is
     * the whole of what this response names a person by.
     */
    /**
     * @param online   gap map row 3.3: whether the courier's most recent
     *                 telemetry fix is within the tenant's {@code
     *                 courier.compensation} policy {@code onlineWithinMinutes}.
     *                 Rating stays absent from this response entirely —
     *                 no source exists on either side yet
     * @param lastSeenAt the fix {@code online} was computed from, or null for a
     *                 courier this call found no live row for at all
     */
    record RosterEntryResponse(
            UUID courierId,
            String displayReference,
            String status,
            UUID courierTypeId,
            String courierTypeName,
            String vehicleClass,
            int activeAssignments,
            int concurrencyCeiling,
            @Nullable UUID engagementId,
            @Nullable String engagementStatus,
            @Nullable String warningState,
            @Nullable LocalDate reverificationDueOn,
            boolean online,
            @Nullable Instant lastSeenAt) {

        static RosterEntryResponse of(RosterEntry entry) {
            var courier = entry.courier();
            return new RosterEntryResponse(
                    courier.id(),
                    courier.displayReference(),
                    courier.status(),
                    courier.courierTypeId(),
                    courier.courierTypeName(),
                    courier.vehicleClass(),
                    entry.activeAssignments(),
                    courier.maxConcurrentAssignments(),
                    courier.engagementId(),
                    courier.engagementStatus(),
                    courier.warningState(),
                    courier.reverificationDueOn(),
                    entry.online(),
                    entry.lastSeenAt());
        }
    }

    record CourierTypeResponse(
            UUID courierTypeId,
            String code,
            String displayName,
            String vehicleClass,
            int minDistanceMeters,
            @Nullable Integer maxDistanceMeters,
            int maxConcurrentAssignments,
            int offerTtlSeconds,
            int startingMinuteOffset,
            String workMode,
            String status,
            int version) {

        static CourierTypeResponse of(CourierTypeRow row) {
            return new CourierTypeResponse(
                    row.id(),
                    row.code(),
                    row.displayName(),
                    row.vehicleClass(),
                    row.minDistanceMeters(),
                    row.maxDistanceMeters(),
                    row.maxConcurrentAssignments(),
                    row.offerTtlSeconds(),
                    row.startingMinuteOffset(),
                    row.workMode(),
                    row.status(),
                    row.version());
        }
    }

    /**
     * @param workMode null (or blank) defaults to {@code SHIFT} — kept
     *                 optional, unlike {@link UpdateCourierTypeRequest}'s own
     *                 field, because the OpenAPI contract test refuses to add
     *                 a new required field to an existing endpoint: an older
     *                 client that has never heard of ADR 0108 still has to be
     *                 able to create a type
     */
    record CreateCourierTypeRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String vehicleClass,
            @PositiveOrZero int minDistanceMeters,
            @Nullable Integer maxDistanceMeters,
            @Positive int maxConcurrentAssignments,
            @Positive int offerTtlSeconds,
            @PositiveOrZero @Max(1440) int startingMinuteOffset,
            @Nullable String workMode) {

        String workModeOrDefault() {
            return workMode == null || workMode.isBlank() ? "SHIFT" : workMode;
        }
    }

    /**
     * Corrects a vehicle class (ADR 0108). {@code code} is included: a
     * mistyped one used to be permanent, which is exactly the gap this record
     * exists to close.
     */
    record UpdateCourierTypeRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String vehicleClass,
            @PositiveOrZero int minDistanceMeters,
            @Nullable Integer maxDistanceMeters,
            @Positive int maxConcurrentAssignments,
            @Positive int offerTtlSeconds,
            @PositiveOrZero @Max(1440) int startingMinuteOffset,
            @NotBlank String workMode,
            int expectedVersion,
            @NotBlank String reason) {}

    record ArchiveCourierTypeRequest(@NotBlank String reason) {}

    record RateCardSummaryResponse(
            UUID cardId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID courierTypeId,
            String code,
            int cardVersion,
            String status,
            String currency,
            @Nullable Instant effectiveFrom,
            @Nullable Instant effectiveTo) {

        static RateCardSummaryResponse of(CardSummaryRow row) {
            return new RateCardSummaryResponse(
                    row.id(),
                    row.brandId(),
                    row.locationId(),
                    row.courierTypeId(),
                    row.code(),
                    row.cardVersion(),
                    row.status(),
                    row.currency(),
                    row.effectiveFrom(),
                    row.effectiveTo());
        }
    }

    record RateComponentResponse(
            UUID componentId,
            String componentType,
            int priority,
            long amountMinor,
            @Nullable Integer bandFromMeters,
            @Nullable Integer bandToMeters,
            @Nullable Integer minimumPaidSeconds) {

        static RateComponentResponse of(RateComponent component) {
            return new RateComponentResponse(
                    component.id(),
                    component.type().name(),
                    component.priority(),
                    component.amountMinor(),
                    component.bandFromMeters(),
                    component.bandToMeters(),
                    component.minimumPaidSeconds());
        }
    }

    record RateCardDetailResponse(
            UUID cardId, int cardVersion, String currency, List<RateComponentResponse> components) {

        static RateCardDetailResponse of(RateCard card) {
            return new RateCardDetailResponse(
                    card.id(),
                    card.version(),
                    card.currency(),
                    card.components().stream().map(RateComponentResponse::of).toList());
        }
    }

    record RateComponentRequest(
            @NotBlank String componentType,
            int priority,
            long amountMinor,
            @Nullable Integer bandFromMeters,
            @Nullable Integer bandToMeters,
            @Nullable Integer minimumPaidSeconds) {}

    record NewRateCardRequest(
            @NotNull UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID courierTypeId,
            @NotBlank @Size(max = 48) String code,
            @Positive int cardVersion,
            @Size(min = 3, max = 3) String currency,
            @NotEmpty List<RateComponentRequest> components) {}

    record ActivateRateCardRequest(@NotBlank String reason) {}

    /**
     * @param courierDisplayReference the non-personal handle (ADR 0029), never
     *                                the decrypted name — null only when the
     *                                courier row itself has since been removed,
     *                                which {@code fulfillment.couriers} never
     *                                does today
     */
    record ShiftResponse(
            UUID shiftId,
            UUID courierId,
            @Nullable String courierDisplayReference,
            String status,
            String dutyState,
            Instant openedAt,
            @Nullable Instant closedAt,
            @Nullable Long paidSeconds,
            long breakSeconds,
            @Nullable UUID approvalRequestId) {

        static ShiftResponse of(ShiftRow shift, @Nullable String courierDisplayReference) {
            return new ShiftResponse(
                    shift.id(),
                    shift.courierId(),
                    courierDisplayReference,
                    shift.status().name(),
                    shift.dutyState().name(),
                    shift.openedAt(),
                    shift.closedAt(),
                    shift.paidSeconds(),
                    shift.breakSeconds(),
                    shift.approvalRequestId());
        }
    }

    /**
     * One planned shift on the wire (IA 3.5's roster grid). {@code comparison}
     * is present only from {@link #rosterComparison}; the plain roster read
     * leaves it null rather than computing a per-row match nobody asked for.
     */
    record PlannedShiftResponse(
            UUID entryId,
            UUID courierId,
            @Nullable String courierDisplayReference,
            String status,
            Instant plannedStart,
            Instant plannedEnd,
            @Nullable Instant publishedAt,
            @Nullable Instant respondedAt,
            @Nullable RosterComparisonView comparison) {

        static PlannedShiftResponse of(
                PlannedShiftRow entry, @Nullable String courierDisplayReference, @Nullable RosterComparison compared) {
            return new PlannedShiftResponse(
                    entry.id(),
                    entry.courierId(),
                    courierDisplayReference,
                    entry.status().name(),
                    entry.plannedStart(),
                    entry.plannedEnd(),
                    entry.publishedAt(),
                    entry.respondedAt(),
                    compared == null ? null : RosterComparisonView.of(compared));
        }
    }

    /** @param coverage {@code COVERED}, {@code PENDING} or {@code UNCOVERED} — see {@link RosterComparison}. */
    record RosterComparisonView(
            String coverage,
            @Nullable UUID matchedShiftId,
            @Nullable String matchedDutyState) {

        static RosterComparisonView of(RosterComparison comparison) {
            ShiftRow matched = comparison.matchedShift();
            return new RosterComparisonView(
                    comparison.coverage(),
                    matched == null ? null : matched.id(),
                    matched == null ? null : matched.dutyState().name());
        }
    }

    record DraftRosterEntryRequest(
            @NotNull UUID courierId,
            @NotNull Instant plannedStart,
            @NotNull Instant plannedEnd,
            @NotBlank String reason) {}

    record RosterEntryReasonRequest(@NotBlank String reason) {}

    record CourierPolicyResponse(
            int reverificationDays,
            int warningDays,
            int settlementPeriodDays,
            long cashCeilingMinor,
            long penaltyApprovalThresholdMinor,
            String shiftEnforcement,
            int graceSeconds,
            int confirmationPointRetentionDays,
            boolean gpsVerificationEnabled,
            int gpsAcceptRadiusMeters,
            int gpsStatusChangeRadiusMeters,
            boolean kitchenReadyOnly,
            String revealCustomerLocationTiming,
            boolean postDeliveryPaymentCheckRequired,
            int onlineWithinMinutes,
            String winningScope,
            UUID policyId,
            int policyVersion) {

        static CourierPolicyResponse of(ResolvedPolicy<CourierCompensationPolicy> resolved) {
            var doc = resolved.document();
            return new CourierPolicyResponse(
                    doc.reverificationDays(),
                    doc.warningDays(),
                    doc.settlementPeriodDays(),
                    doc.cashCeilingMinor(),
                    doc.penaltyApprovalThresholdMinor(),
                    doc.shiftEnforcement().name(),
                    doc.graceSeconds(),
                    doc.confirmationPointRetentionDays(),
                    doc.gpsVerificationEnabled(),
                    doc.gpsAcceptRadiusMeters(),
                    doc.gpsStatusChangeRadiusMeters(),
                    doc.kitchenReadyOnly(),
                    doc.revealCustomerLocationTiming().name(),
                    doc.postDeliveryPaymentCheckRequired(),
                    doc.onlineWithinMinutes(),
                    resolved.winningScope().name(),
                    resolved.policyId(),
                    resolved.policyVersion());
        }
    }

    /**
     * The whole-document write body for {@code PUT .../courier-policy}. Every
     * field is required — ADR 0030 versions this document as one unit, not a
     * partial merge over the version it replaces, so a caller reads the
     * current {@link CourierPolicyResponse} first and sends every field back,
     * changed or not.
     */
    record CourierPolicyWriteRequest(
            @Min(1) int reverificationDays,
            @Min(1) int warningDays,
            @Min(1) int settlementPeriodDays,
            @PositiveOrZero long cashCeilingMinor,
            @PositiveOrZero long penaltyApprovalThresholdMinor,
            @NotBlank String shiftEnforcement,
            @PositiveOrZero int graceSeconds,
            @Min(1) int confirmationPointRetentionDays,
            boolean gpsVerificationEnabled,
            @Positive int gpsAcceptRadiusMeters,
            @Positive int gpsStatusChangeRadiusMeters,
            boolean kitchenReadyOnly,
            @NotBlank String revealCustomerLocationTiming,
            boolean postDeliveryPaymentCheckRequired,
            @Positive int onlineWithinMinutes,
            @NotBlank @Size(max = 500) String reason) {

        CourierCompensationPolicy toDocument() {
            return new CourierCompensationPolicy(
                    reverificationDays,
                    warningDays,
                    settlementPeriodDays,
                    cashCeilingMinor,
                    penaltyApprovalThresholdMinor,
                    parseShiftEnforcement(),
                    graceSeconds,
                    confirmationPointRetentionDays,
                    gpsVerificationEnabled,
                    gpsAcceptRadiusMeters,
                    gpsStatusChangeRadiusMeters,
                    kitchenReadyOnly,
                    parseRevealTiming(),
                    postDeliveryPaymentCheckRequired,
                    onlineWithinMinutes);
        }

        private ShiftEnforcement parseShiftEnforcement() {
            try {
                return ShiftEnforcement.valueOf(shiftEnforcement);
            } catch (IllegalArgumentException unknown) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Unknown shiftEnforcement \"%s\"".formatted(shiftEnforcement));
            }
        }

        private RevealTiming parseRevealTiming() {
            try {
                return RevealTiming.valueOf(revealCustomerLocationTiming);
            } catch (IllegalArgumentException unknown) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Unknown revealCustomerLocationTiming \"%s\"".formatted(revealCustomerLocationTiming));
            }
        }
    }

    record CourierResponse(UUID courierId, UUID engagementId, String status) {}

    record EngagementResponse(
            UUID engagementId,
            String status,
            String warningState,
            @Nullable LocalDate registrationValidUntil,
            @Nullable LocalDate reverificationDueOn) {}

    record AdjustmentResponse(
            @Nullable UUID entryId, @Nullable UUID approvalRequestId, boolean written) {}

    /** @param currency null only for a courier with no ledger entries; the balance is then zero regardless. */
    record LedgerResponse(long balanceMinor, @Nullable String currency, List<LedgerLine> entries) {}

    record LedgerLine(
            UUID entryId,
            String entryType,
            long amountMinor,
            String currency,
            @Nullable String reasonCode,
            String occurredAt) {}

    record StatementResponse(UUID periodId, String statementHash, long amountPayableMinor, boolean complianceFlag) {}

    record PayoutResponse(@Nullable UUID payoutId, @Nullable UUID approvalRequestId, boolean authorised) {}

    /**
     * One cash handover — Finance 8.3.
     *
     * @param courierDisplayReference the non-personal handle (ADR 0029), never
     *                                the decrypted name — null only where the
     *                                courier row has since been removed
     * @param bonusPaidMinor          a bonus already posted to this courier
     *                                while the shift was open — money paid
     *                                that reduces what the cash count still
     *                                owes, shown rather than netted into
     *                                {@code expectedMinor}: the handover's own
     *                                expected figure stays exactly what
     *                                {@code cashCollectedDuringShift} says
     * @param cashDeliveredCount      deliveries this shift where cash changed
     *                                hands
     * @param nonCashDeliveredCount   deliveries this shift on any other
     *                                payment method — the IA's "by payment
     *                                method" split
     * @param nonCashEarningsMinor    gross earnings on those non-cash
     *                                deliveries; there is no "collected"
     *                                figure for them because no cash changed
     *                                hands on any of them
     */
    record CashHandoverResponse(
            UUID handoverId,
            UUID shiftId,
            UUID courierId,
            @Nullable String courierDisplayReference,
            UUID locationId,
            String status,
            String currency,
            long expectedMinor,
            @Nullable Long declaredMinor,
            @Nullable Long confirmedMinor,
            @Nullable Long varianceMinor,
            long bonusPaidMinor,
            int cashDeliveredCount,
            int nonCashDeliveredCount,
            long nonCashEarningsMinor,
            @Nullable String declaredAt,
            @Nullable String confirmedBy,
            @Nullable String confirmedAt,
            @Nullable String reasonCode) {

        static CashHandoverResponse of(
                HandoverRow row, @Nullable String courierDisplayReference, @Nullable ShiftCashSummary summary) {
            return new CashHandoverResponse(
                    row.id(),
                    row.shiftId(),
                    row.courierId(),
                    courierDisplayReference,
                    row.locationId(),
                    row.status(),
                    row.currency(),
                    row.expectedMinor(),
                    row.declaredMinor(),
                    row.confirmedMinor(),
                    row.varianceMinor(),
                    summary == null ? 0L : summary.bonusPaidMinor(),
                    summary == null ? 0 : summary.cashDeliveredCount(),
                    summary == null ? 0 : summary.nonCashDeliveredCount(),
                    summary == null ? 0L : summary.nonCashEarningsMinor(),
                    row.declaredAt() == null ? null : row.declaredAt().toString(),
                    row.confirmedBy(),
                    row.confirmedAt() == null ? null : row.confirmedAt().toString(),
                    row.reasonCode());
        }
    }

    /**
     * One settlement period — Finance 8.5's salary report (IA §8.5: "orders,
     * km, hours, вовремя, penalties, bonus, К оплате").
     *
     * @param distanceMeters the period's km column, in the base unit money
     *                       already uses this codebase over: raw metres,
     *                       never a pre-divided double
     * @param paidSeconds    the period's hours column, in seconds for the same
     *                       reason
     * @param bonusMinor     the period's bonus column, split from {@code
     *                       adjustmentsMinor} at read time — see {@link
     *                       JdbcCourierLedgerStore#adjustmentSplitByPeriod}
     * @param penaltyMinor   the period's penalty column, same split
     */
    record SettlementPeriodResponse(
            UUID periodId,
            UUID courierId,
            String periodStart,
            String periodEnd,
            String status,
            String currency,
            long grossEarningsMinor,
            long adjustmentsMinor,
            long cashHeldMinor,
            long amountPayableMinor,
            int deliveredCount,
            int onTimeCount,
            long distanceMeters,
            long paidSeconds,
            long bonusMinor,
            long penaltyMinor,
            boolean complianceFlag,
            @Nullable String statementHash,
            @Nullable String closedAt,
            @Nullable String settledAt) {

        static SettlementPeriodResponse of(JdbcCourierLedgerStore.PeriodRow row, @Nullable AdjustmentSplit split) {
            return new SettlementPeriodResponse(
                    row.id(),
                    row.courierId(),
                    row.periodStart().toString(),
                    row.periodEnd().toString(),
                    row.status().name(),
                    row.currency(),
                    row.grossEarningsMinor(),
                    row.adjustmentsMinor(),
                    row.cashHeldMinor(),
                    row.amountPayableMinor(),
                    row.deliveredCount(),
                    row.onTimeCount(),
                    row.distanceMeters(),
                    row.paidSeconds(),
                    split == null ? 0L : split.bonusMinor(),
                    split == null ? 0L : split.penaltyMinor(),
                    row.complianceFlag(),
                    row.statementHash(),
                    row.closedAt() == null ? null : row.closedAt().toString(),
                    row.settledAt() == null ? null : row.settledAt().toString());
        }
    }

    /** One partner delivery invoice, on the reconciliation worklist — Finance 8.4. */
    record PartnerInvoiceResponse(
            UUID invoiceId,
            String providerCode,
            String providerInvoiceRef,
            @Nullable UUID legalEntityId,
            String periodStart,
            String periodEnd,
            long totalMinor,
            String currency,
            String status) {

        static PartnerInvoiceResponse of(InvoiceRow row) {
            return new PartnerInvoiceResponse(
                    row.id(),
                    row.providerCode(),
                    row.providerInvoiceRef(),
                    row.legalEntityId(),
                    row.periodStart().toString(),
                    row.periodEnd().toString(),
                    row.totalMinor(),
                    row.currency(),
                    row.status());
        }
    }

    /** @param varianceResolution ACCEPTED or DISPUTED — only ever set on a VARIANCE line. */
    record PartnerInvoiceLineResponse(
            UUID lineId,
            String providerShipmentRef,
            @Nullable UUID shipmentId,
            long amountMinor,
            String currency,
            String chargeType,
            String matchStatus,
            @Nullable Long varianceMinor,
            @Nullable String reasonCode,
            @Nullable String varianceResolution) {

        static PartnerInvoiceLineResponse of(InvoiceLineRow row) {
            return new PartnerInvoiceLineResponse(
                    row.id(),
                    row.providerShipmentRef(),
                    row.shipmentId(),
                    row.amountMinor(),
                    row.currency(),
                    row.chargeType().name(),
                    row.matchStatus().name(),
                    row.varianceMinor(),
                    row.reasonCode(),
                    row.varianceResolution());
        }
    }

    record PartnerInvoiceDetailResponse(PartnerInvoiceResponse invoice, List<PartnerInvoiceLineResponse> lines) {

        static PartnerInvoiceDetailResponse of(InvoiceRow invoice, List<InvoiceLineRow> lines) {
            return new PartnerInvoiceDetailResponse(
                    PartnerInvoiceResponse.of(invoice),
                    lines.stream().map(PartnerInvoiceLineResponse::of).toList());
        }
    }
}
