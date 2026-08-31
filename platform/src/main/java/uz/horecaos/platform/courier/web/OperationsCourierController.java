package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import uz.horecaos.platform.courier.application.CourierSettlementService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.DeliveryCostQueryService;
import uz.horecaos.platform.courier.application.PartnerInvoiceService;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.PartnerChargeType;
import uz.horecaos.platform.courier.domain.PayoutMethod;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
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
            CurrentActor currentActor) {
        this.engagements = engagements;
        this.shifts = shifts;
        this.cash = cash;
        this.adjustments = adjustments;
        this.settlement = settlement;
        this.deliveryCosts = deliveryCosts;
        this.partnerInvoices = partnerInvoices;
        this.ledger = ledger;
        this.currentActor = currentActor;
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
}
