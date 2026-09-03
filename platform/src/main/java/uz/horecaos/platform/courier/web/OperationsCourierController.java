package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.application.CourierAdjustmentService;
import uz.horecaos.platform.courier.application.CourierCashService;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.CourierRosterQueryService;
import uz.horecaos.platform.courier.application.CourierRosterQueryService.RosterEntry;
import uz.horecaos.platform.courier.application.CourierSettlementService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.DeliveryCostQueryService;
import uz.horecaos.platform.courier.application.PartnerInvoiceService;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.PartnerChargeType;
import uz.horecaos.platform.courier.domain.PayoutMethod;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore.CardSummaryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.domain.SettlementPeriodStatus;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.HandoverRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceLineRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.InvoiceRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

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
    private final CourierShiftService shifts;
    private final CourierCashService cash;
    private final CourierAdjustmentService adjustments;
    private final CourierSettlementService settlement;
    private final DeliveryCostQueryService deliveryCosts;
    private final PartnerInvoiceService partnerInvoices;
    private final JdbcCourierLedgerStore ledger;
    private final CourierRosterQueryService rosterQuery;
    private final JdbcCourierStore courierStore;
    private final CourierRateCardService rateCards;
    private final JdbcCourierRateCardStore rateCardStore;
    private final JdbcCourierShiftStore shiftStore;
    private final CourierPolicyResolver policyResolver;
    private final JdbcDeliveryCostStore deliveryCostStore;
    private final CurrentActor currentActor;

    public OperationsCourierController(
            CourierEngagementService engagements,
            CourierShiftService shifts,
            CourierCashService cash,
            CourierAdjustmentService adjustments,
            CourierSettlementService settlement,
            DeliveryCostQueryService deliveryCosts,
            PartnerInvoiceService partnerInvoices,
            JdbcCourierLedgerStore ledger,
            CourierRosterQueryService rosterQuery,
            JdbcCourierStore courierStore,
            CourierRateCardService rateCards,
            JdbcCourierRateCardStore rateCardStore,
            JdbcCourierShiftStore shiftStore,
            CourierPolicyResolver policyResolver,
            JdbcDeliveryCostStore deliveryCostStore,
            CurrentActor currentActor) {
        this.engagements = engagements;
        this.shifts = shifts;
        this.cash = cash;
        this.adjustments = adjustments;
        this.settlement = settlement;
        this.deliveryCosts = deliveryCosts;
        this.partnerInvoices = partnerInvoices;
        this.ledger = ledger;
        this.rosterQuery = rosterQuery;
        this.courierStore = courierStore;
        this.rateCards = rateCards;
        this.rateCardStore = rateCardStore;
        this.shiftStore = shiftStore;
        this.policyResolver = policyResolver;
        this.deliveryCostStore = deliveryCostStore;
        this.currentActor = currentActor;
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

    @GetMapping("/courier-types")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(summary = "Vehicle classes, for the registration form's picker")
    public ResponseEntity<List<CourierTypeResponse>> types(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(courierStore.listTypes(tenantId).stream()
                .map(CourierTypeResponse::of)
                .toList());
    }

    @PostMapping("/courier-types")
    @RequiresCapability(value = Capability.COURIER_TYPE_MANAGE, mutating = true)
    @Operation(
            summary = "Define a vehicle class (IA 3.4)",
            description = "The two dispatch numbers — minimum distance and the offer TTL — and "
                    + "not a courier's pay, which is a rate card and a separate act.")
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
                "ACTIVE"));

        return ResponseEntity.ok(
                CourierTypeResponse.of(courierStore.findType(tenantId, typeId).orElseThrow()));
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
                    + "which is the manager's own worklist on this screen.")
    public ResponseEntity<List<ShiftResponse>> courierShifts(
            @PathVariable UUID tenantId,
            @RequestParam UUID brandId,
            @RequestParam UUID locationId,
            @RequestParam(defaultValue = "200") int limit) {

        return ResponseEntity.ok(shiftStore.atLocation(tenantId, brandId, locationId, Math.min(limit, 500)).stream()
                .map(ShiftResponse::of)
                .toList());
    }

    // ------------------------------------------------------------------- policy

    @GetMapping("/courier-policy")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(
            summary = "The courier compensation policy in force (IA 3.9)",
            description = "Omit brandId/locationId for the tenant-wide resolution; supply either "
                    + "to see what a specific brand or location actually resolves. Read-only this "
                    + "wave — couriers.md §16 also names GPS gates, the kitchen-ready-only toggle, "
                    + "reveal-location timing and the telemetry gate default, none of which any "
                    + "policy document backs yet.")
    public ResponseEntity<CourierPolicyResponse> courierPolicy(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID locationId) {

        ResourceScope scope = locationId != null && brandId != null
                ? ResourceScope.location(tenantId, brandId, locationId)
                : brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);

        return ResponseEntity.ok(CourierPolicyResponse.of(policyResolver.resolveWithIdentity(scope)));
    }

    // ------------------------------------------------------------- engagement

    @PostMapping("/couriers")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            summary = "Register a courier and open their engagement",
            description = "Opens in PENDING_VERIFICATION. Onboarding somebody and attesting to "
                    + "their registration are different acts by different people, and this call "
                    + "deliberately cannot do the second.")
    public ResponseEntity<CourierResponse> register(
            @PathVariable UUID tenantId, @Valid @RequestBody RegisterCourierRequest body) {

        CourierEngagementService.Registration registration =
                engagements.register(new CourierEngagementService.NewCourier(
                        tenantId,
                        body.courierTypeId(),
                        body.principalSubject(),
                        body.displayReference(),
                        body.fullName(),
                        body.engagedFrom(),
                        actor(),
                        body.reason(),
                        correlationId()));

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

        return ResponseEntity.ok(shiftStore.listHandovers(tenantId, status, locationId, Math.min(limit, 500)).stream()
                .map(CashHandoverResponse::of)
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
                    + "approval request and writes nothing until a second person decides.")
    public ResponseEntity<AdjustmentResponse> adjust(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @Valid @RequestBody AdjustmentRequest body) {

        CourierAdjustmentService.Outcome outcome = adjustments.request(new CourierAdjustmentService.AdjustmentCommand(
                tenantId,
                courierId,
                body.locationId(),
                body.amountMinor(),
                body.currency(),
                body.reasonCode(),
                AdjustmentOrigin.valueOf(body.origin()),
                body.idempotencyKey(),
                actor(),
                body.reason(),
                correlationId()));

        JdbcCourierLedgerStore.LedgerEntryRow entry = outcome.entry();
        return ResponseEntity.ok(new AdjustmentResponse(
                entry == null ? null : entry.id(), outcome.approvalRequestId(), outcome.written()));
    }

    @GetMapping("/couriers/{courierId}/ledger")
    @RequiresCapability(Capability.COURIER_LEDGER_READ)
    @Operation(
            summary = "A courier's ledger",
            description = "One balance, not a wage balance and a cash balance. A courier holding "
                    + "the tenant's cash while being owed for deliveries is one net position.")
    public ResponseEntity<LedgerResponse> ledgerOf(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @RequestParam(defaultValue = "100") int limit) {

        return ResponseEntity.ok(new LedgerResponse(
                ledger.balanceMinor(tenantId, courierId),
                ledger.entriesOfCourier(tenantId, courierId, Math.min(limit, 500)).stream()
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
        return ResponseEntity.ok(ledger.listPeriods(tenantId, parsed, Math.min(limit, 500)).stream()
                .map(SettlementPeriodResponse::of)
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

    private String correlationId() {
        return org.slf4j.MDC.get("correlationId") == null
                ? UUID.randomUUID().toString()
                : org.slf4j.MDC.get("correlationId");
    }

    // --------------------------------------------------------------- payloads

    record RegisterCourierRequest(
            @NotNull UUID courierTypeId,
            @NotBlank String principalSubject,
            @NotBlank @Size(max = 32) String displayReference,
            @NotBlank String fullName,
            @NotNull LocalDate engagedFrom,
            @NotBlank String reason) {}

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

    record AdjustmentRequest(
            UUID locationId,
            long amountMinor,
            @Size(min = 3, max = 3) String currency,
            @NotBlank String reasonCode,
            @NotBlank String origin,
            @NotBlank String idempotencyKey,
            @NotBlank String reason) {}

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

    /**
     * One roster row on the wire. No name field exists here at all — not even
     * a masked one — because there is nothing decrypted to mask; see {@link
     * Capability#COURIER_READ}'s own doc for why {@code displayReference} is
     * the whole of what this response names a person by.
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
            @Nullable LocalDate reverificationDueOn) {

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
                    courier.reverificationDueOn());
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
            int offerTtlSeconds) {

        static CourierTypeResponse of(CourierTypeRow row) {
            return new CourierTypeResponse(
                    row.id(),
                    row.code(),
                    row.displayName(),
                    row.vehicleClass(),
                    row.minDistanceMeters(),
                    row.maxDistanceMeters(),
                    row.maxConcurrentAssignments(),
                    row.offerTtlSeconds());
        }
    }

    record CreateCourierTypeRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String vehicleClass,
            @PositiveOrZero int minDistanceMeters,
            @Nullable Integer maxDistanceMeters,
            @Positive int maxConcurrentAssignments,
            @Positive int offerTtlSeconds) {}

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

    record ShiftResponse(
            UUID shiftId,
            UUID courierId,
            String status,
            String dutyState,
            Instant openedAt,
            @Nullable Instant closedAt,
            @Nullable Long paidSeconds,
            long breakSeconds,
            @Nullable UUID approvalRequestId) {

        static ShiftResponse of(ShiftRow shift) {
            return new ShiftResponse(
                    shift.id(),
                    shift.courierId(),
                    shift.status().name(),
                    shift.dutyState().name(),
                    shift.openedAt(),
                    shift.closedAt(),
                    shift.paidSeconds(),
                    shift.breakSeconds(),
                    shift.approvalRequestId());
        }
    }

    record CourierPolicyResponse(
            int reverificationDays,
            int warningDays,
            int settlementPeriodDays,
            long cashCeilingMinor,
            long penaltyApprovalThresholdMinor,
            String shiftEnforcement,
            int graceSeconds,
            int confirmationPointRetentionDays,
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
                    resolved.winningScope().name(),
                    resolved.policyId(),
                    resolved.policyVersion());
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

    record LedgerResponse(long balanceMinor, List<LedgerLine> entries) {}

    record LedgerLine(
            UUID entryId,
            String entryType,
            long amountMinor,
            String currency,
            @Nullable String reasonCode,
            String occurredAt) {}

    record StatementResponse(UUID periodId, String statementHash, long amountPayableMinor, boolean complianceFlag) {}

    record PayoutResponse(@Nullable UUID payoutId, @Nullable UUID approvalRequestId, boolean authorised) {}

    /** One cash handover — Finance 8.3. */
    record CashHandoverResponse(
            UUID handoverId,
            UUID shiftId,
            UUID courierId,
            UUID locationId,
            String status,
            String currency,
            long expectedMinor,
            @Nullable Long declaredMinor,
            @Nullable Long confirmedMinor,
            @Nullable Long varianceMinor,
            @Nullable String declaredAt,
            @Nullable String confirmedBy,
            @Nullable String confirmedAt,
            @Nullable String reasonCode) {

        static CashHandoverResponse of(HandoverRow row) {
            return new CashHandoverResponse(
                    row.id(),
                    row.shiftId(),
                    row.courierId(),
                    row.locationId(),
                    row.status(),
                    row.currency(),
                    row.expectedMinor(),
                    row.declaredMinor(),
                    row.confirmedMinor(),
                    row.varianceMinor(),
                    row.declaredAt() == null ? null : row.declaredAt().toString(),
                    row.confirmedBy(),
                    row.confirmedAt() == null ? null : row.confirmedAt().toString(),
                    row.reasonCode());
        }
    }

    /** One settlement period — Finance 8.5. */
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
            boolean complianceFlag,
            @Nullable String statementHash,
            @Nullable String closedAt,
            @Nullable String settledAt) {

        static SettlementPeriodResponse of(JdbcCourierLedgerStore.PeriodRow row) {
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

    record PartnerInvoiceLineResponse(
            UUID lineId,
            String providerShipmentRef,
            @Nullable UUID shipmentId,
            long amountMinor,
            String currency,
            String chargeType,
            String matchStatus,
            @Nullable Long varianceMinor,
            @Nullable String reasonCode) {

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
                    row.reasonCode());
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
