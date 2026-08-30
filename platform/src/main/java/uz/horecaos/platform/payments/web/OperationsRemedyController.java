package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.payments.api.EntitlementBenefit;
import uz.horecaos.platform.payments.api.EntitlementScope;
import uz.horecaos.platform.payments.settlement.ExecutionChannel;
import uz.horecaos.platform.payments.settlement.JdbcRemedyStore.RemedyRow;
import uz.horecaos.platform.payments.settlement.JdbcRemedyStore.RemedyTotals;
import uz.horecaos.platform.payments.settlement.OrderRemedyService;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.FutureDiscountCommand;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.RefundCommand;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.RemedyOutcome;
import uz.horecaos.platform.payments.settlement.RemedyType;
import uz.horecaos.platform.payments.settlement.VerificationState;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What support and finance do about an order that went wrong (ADR 0013 as
 * amended 2026-08-25, ADR 0027, ADR 0031).
 *
 * <p>Three remedies and no fourth: give money back, reimburse the delivery fee,
 * or grant a discount on a future order. Each is its own endpoint rather than one
 * endpoint with a {@code type} field, because the three take different inputs —
 * a refund needs a cabinet reference, a future discount needs a window and a use
 * count — and a single body carrying the union of all three would validate none
 * of them properly.
 *
 * <p><strong>None of these endpoints moves money.</strong> A refund is recorded
 * <em>after</em> an operator has performed it in Click's or Payme's cabinet, and
 * the platform never calls a provider. The response therefore reports what part
 * of the amount the platform actually settled itself and what part it is taking
 * somebody's word for, so that a console can say so on screen rather than showing
 * a refund that looks executed.
 *
 * <p>The capability split follows what the acts actually are.
 * {@code REFUND_REQUEST} creates a remedy: it is a support power, and whether the
 * remedy takes effect at once or waits for a second pair of eyes is the ADR 0027
 * threshold's business rather than the capability's. {@code REFUND_EXECUTE}
 * guards the one remaining executive act in a design where nothing executes a
 * payment — declaring that money HorecaOS asserted had moved really did — which
 * belongs to finance and not to the person who made the assertion.
 *
 * <p><strong>{@code REFUND_APPROVE} is deliberately not declared here, because
 * there is no approve endpoint in this controller.</strong> {@code ApprovalService.decide}
 * takes a request id and nothing else, so a payments-local approve endpoint behind
 * {@code REFUND_APPROVE} would let a refund approver decide a loyalty adjustment
 * or an onboarding step by pasting a different id. The approvals console belongs
 * to the audit module, which is the only place that can scope a decision to the
 * action code it was raised under. Until it exists, an over-threshold remedy
 * returns {@code PENDING} and the maker resubmits the identical request once it is
 * decided — which resolves to {@code APPROVED} and applies exactly once, the flow
 * {@code LoyaltyAdjustmentService} already uses.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Order remedies", description = "Refunds, delivery-fee reimbursements, and future-discount entitlements")
public class OperationsRemedyController {

    /**
     * How long an attestation is left alone before it appears on the worklist.
     *
     * <p>A refund recorded this morning has had no chance to appear in anybody's
     * settlement file, and a worklist that lists it teaches its readers to skim.
     */
    private static final Duration DEFAULT_SETTLING_PERIOD = Duration.ofHours(24);

    private final OrderRemedyService remedies;
    private final CurrentActor currentActor;

    public OperationsRemedyController(OrderRemedyService remedies, CurrentActor currentActor) {
        this.remedies = remedies;
        this.currentActor = currentActor;
    }

    @PostMapping("/orders/{orderId}/refunds")
    @RequiresCapability(value = Capability.REFUND_REQUEST, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Record a refund that was performed in the provider's cabinet",
            description = "Full and partial are the same call: the amount is whatever is being "
                    + "given back, capped cumulatively at what the tenders settled. Nothing here "
                    + "contacts Click or Payme. The response separates the part the platform "
                    + "settled itself -- a points reversal, which reconciles -- from the part it "
                    + "is recording on an operator's word, which does not until a settlement "
                    + "file says so.")
    public ResponseEntity<RemedyResponse> recordRefund(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody RefundRequest body) {

        return ResponseEntity.ok(RemedyResponse.of(remedies.recordRefund(body.toCommand(tenantId, orderId, actor()))));
    }

    @PostMapping("/orders/{orderId}/delivery-fee-reimbursements")
    @RequiresCapability(value = Capability.REFUND_REQUEST, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Reimburse the delivery fee, in full or in part",
            description = "Recorded under its own remedy type, never as a refund with a note: a "
                    + "tenant asking what late delivery cost them last month is asking for these "
                    + "rows and not for the refunds beside them. Bounded by the settled tenders "
                    + "and, where the platform can establish it, by the fee actually charged.")
    public ResponseEntity<RemedyResponse> reimburseDeliveryFee(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody RefundRequest body) {

        return ResponseEntity.ok(
                RemedyResponse.of(remedies.recordDeliveryFeeReimbursement(body.toCommand(tenantId, orderId, actor()))));
    }

    @PostMapping("/orders/{orderId}/future-discounts")
    @RequiresCapability(value = Capability.REFUND_REQUEST, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Grant a discount on the customer's next N orders",
            description = "Applies to the subtotal, the delivery fee, or both, for a bounded "
                    + "number of uses inside a bounded window. Costs nothing today, so it is not "
                    + "capped by the settled tenders; what an approver weighs is the exposure -- "
                    + "uses times the per-use maximum.")
    public ResponseEntity<RemedyResponse> grantFutureDiscount(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody FutureDiscountRequest body) {

        return ResponseEntity.ok(
                RemedyResponse.of(remedies.grantFutureDiscount(body.toCommand(tenantId, orderId, actor()))));
    }

    @PostMapping("/remedies/{remedyId}/verification")
    @RequiresCapability(value = Capability.REFUND_EXECUTE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Confirm or dispute an attested refund against an outside source",
            description = "The only way a recorded refund stops being an assertion. Records what "
                    + "corroborated it -- a settlement line, a bank statement -- and is refused "
                    + "on a remedy that has already been verified or disputed, so a second "
                    + "reconciliation run cannot overwrite a dispute with a confirmation.")
    public ResponseEntity<VerificationResponse> verify(
            @PathVariable UUID tenantId, @PathVariable UUID remedyId, @Valid @RequestBody VerificationRequest body) {

        boolean recorded = remedies.recordVerification(
                tenantId, remedyId, body.state(), body.source(), actor(), body.reason(), body.correlationId());
        return ResponseEntity.ok(new VerificationResponse(recorded));
    }

    @GetMapping("/orders/{orderId}/remedies")
    @RequiresCapability(value = Capability.PAYMENT_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Every remedy granted on one order")
    public ResponseEntity<List<RemedyResponse>> remediesOfOrder(
            @PathVariable UUID tenantId, @PathVariable UUID orderId) {

        return ResponseEntity.ok(remedies.remediesOfOrder(tenantId, orderId).stream()
                .map(RemedyResponse::of)
                .toList());
    }

    @GetMapping("/remedies/unverified")
    @RequiresCapability(value = Capability.PAYMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Refunds the platform asserted and nothing has corroborated",
            description = "The reconciliation gap this design creates, as a list. Every row is "
                    + "money the ledger says left a merchant account on one person's word. "
                    + "Oldest first, because age is the signal: an attestation from this morning "
                    + "is ordinary and one from six weeks ago that no settlement file matched is "
                    + "a refund that may never have happened.")
    public Page<RemedyResponse> unverified(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) Integer settlingHours,
            @RequestParam(required = false) Integer limit) {

        Duration settling =
                settlingHours == null ? DEFAULT_SETTLING_PERIOD : Duration.ofHours(Math.max(0, settlingHours));
        return Page.last(remedies.unverifiedAttestations(tenantId, settling, Page.limitOrDefault(limit)).stream()
                .map(RemedyResponse::of)
                .toList());
    }

    @GetMapping("/reports/remedies")
    @RequiresCapability(value = Capability.PAYMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Remedy totals, per type, split by who moved the money",
            description = "Never one number. Refunds of goods, delivery-fee reimbursements and "
                    + "future discounts are separate lines, and within each the attested money "
                    + "-- asserted by an operator, unobserved by the platform -- is reported "
                    + "apart from the money the platform settled itself.")
    public ResponseEntity<List<RemedyTotalsResponse>> report(
            @PathVariable UUID tenantId, @RequestParam Instant from, @RequestParam Instant to) {

        return ResponseEntity.ok(remedies.totalsByType(tenantId, from, to).stream()
                .map(RemedyTotalsResponse::of)
                .toList());
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    // ----------------------------------------------------------- payloads

    /**
     * @param executedBy the person who performed the refund in the provider's
     *                   cabinet, which is frequently not the person recording it.
     *                   Kept apart from the recording actor so an investigation can
     *                   tell the two apart
     * @param executedAt when they say it was done, which is not when it was typed
     *                   in here
     */
    public record RefundRequest(
            @Positive long amountMinor,
            @NotBlank @Size(max = 3) String currency,
            @NotBlank @Size(max = 48) String reasonCode,
            @NotBlank @Size(max = 500) String reason,
            ExecutionChannel channel,
            @Size(max = 128) String providerReference,
            @Size(max = 128) String executedBy,
            Instant executedAt,
            @NotBlank @Size(max = 255) String idempotencyKey,
            @Size(max = 128) String correlationId) {

        RefundCommand toCommand(UUID tenantId, UUID orderId, ActorRef actor) {
            return new RefundCommand(
                    tenantId,
                    orderId,
                    amountMinor,
                    currency,
                    reasonCode,
                    reason,
                    channel,
                    providerReference,
                    executedBy,
                    executedAt,
                    actor,
                    idempotencyKey,
                    correlationId);
        }
    }

    /**
     * @param percentBasisPoints set for a percentage discount; a maximum is then
     *                           mandatory
     * @param validForDays       the window. Bounded rather than open-ended
     */
    public record FutureDiscountRequest(
            @NotNull EntitlementScope appliesTo,
            @NotNull EntitlementBenefit benefit,
            Integer percentBasisPoints,
            Long amountMinor,
            Long maximumMinor,
            @Min(1) int uses,
            @Min(1) int validForDays,
            @NotBlank @Size(max = 48) String reasonCode,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 255) String idempotencyKey,
            @Size(max = 128) String correlationId) {

        FutureDiscountCommand toCommand(UUID tenantId, UUID orderId, ActorRef actor) {
            return new FutureDiscountCommand(
                    tenantId,
                    orderId,
                    appliesTo,
                    benefit,
                    percentBasisPoints,
                    amountMinor,
                    maximumMinor,
                    uses,
                    Duration.ofDays(validForDays),
                    reasonCode,
                    reason,
                    actor,
                    idempotencyKey,
                    correlationId);
        }
    }

    public record VerificationRequest(
            @NotNull VerificationState state,
            @NotBlank @Size(max = 128) String source,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 128) String correlationId) {}

    /** @param recorded false when the remedy had already been verified or disputed */
    public record VerificationResponse(boolean recorded) {}

    /**
     * @param approvalStatus       NOT_REQUIRED, PENDING, APPROVED or DECLINED
     *                             (ADR 0027). On PENDING nothing was written and
     *                             nothing moved
     * @param attestedMoney        the part of the amount the platform is recording
     *                             on an operator's word and cannot verify
     * @param platformSettledMoney the part the platform performed itself and can
     *                             prove from its own ledger
     * @param deliveryFeeBasis     the fee the reimbursement was checked against, or
     *                             null when no fee ceiling could be established
     */
    public record RemedyResponse(
            String approvalStatus,
            UUID approvalRequestId,
            UUID remedyId,
            RemedyType remedyType,
            UUID orderId,
            ApiMoney amount,
            ApiMoney attestedMoney,
            ApiMoney platformSettledMoney,
            String settlementBasis,
            String verificationState,
            ExecutionChannel executionChannel,
            String providerReference,
            String executedBy,
            Instant executedAt,
            String recordedBy,
            Instant recordedAt,
            ApiMoney deliveryFeeBasis) {

        static RemedyResponse of(RemedyOutcome outcome) {
            String status =
                    switch (outcome.approval()) {
                        case ApprovalOutcome.NotRequired ignored -> "NOT_REQUIRED";
                        case ApprovalOutcome.Pending ignored -> "PENDING";
                        case ApprovalOutcome.Approved ignored -> "APPROVED";
                        case ApprovalOutcome.Declined ignored -> "DECLINED";
                    };
            UUID requestId =
                    switch (outcome.approval()) {
                        case ApprovalOutcome.NotRequired ignored -> null;
                        case ApprovalOutcome.Pending pending -> pending.requestId();
                        case ApprovalOutcome.Approved approved -> approved.requestId();
                        case ApprovalOutcome.Declined declined -> declined.requestId();
                    };
            if (!outcome.recorded()) {
                return new RemedyResponse(
                        status, requestId, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null);
            }
            RemedyResponse recorded = of(outcome.remedy());
            return new RemedyResponse(
                    status,
                    requestId,
                    recorded.remedyId(),
                    recorded.remedyType(),
                    recorded.orderId(),
                    recorded.amount(),
                    recorded.attestedMoney(),
                    recorded.platformSettledMoney(),
                    recorded.settlementBasis(),
                    recorded.verificationState(),
                    recorded.executionChannel(),
                    recorded.providerReference(),
                    recorded.executedBy(),
                    recorded.executedAt(),
                    recorded.recordedBy(),
                    recorded.recordedAt(),
                    recorded.deliveryFeeBasis());
        }

        static RemedyResponse of(RemedyRow row) {
            return new RemedyResponse(
                    "NOT_REQUIRED",
                    row.approvalRequestId(),
                    row.id(),
                    row.remedyType(),
                    row.orderId(),
                    ApiMoney.of(row.amountMinor(), row.currency()),
                    ApiMoney.of(row.attestedMoneyMinor(), row.currency()),
                    ApiMoney.of(row.platformSettledMinor(), row.currency()),
                    row.settlementBasis().name(),
                    row.verificationState().name(),
                    row.executionChannel(),
                    row.providerReference(),
                    row.executedBy(),
                    row.executedAt(),
                    row.recordedBy(),
                    row.recordedAt(),
                    row.deliveryFeeBasisMinor() == null
                            ? null
                            : ApiMoney.of(row.deliveryFeeBasisMinor(), row.currency()));
        }
    }

    /**
     * @param unverified how much of the attested money on these rows nothing has
     *                   corroborated. Reported per type, and never folded into the
     *                   total beside it
     */
    public record RemedyTotalsResponse(
            RemedyType remedyType,
            long remedyCount,
            ApiMoney amount,
            ApiMoney attestedMoney,
            ApiMoney platformSettledMoney,
            ApiMoney unverified) {

        static RemedyTotalsResponse of(RemedyTotals totals) {
            return new RemedyTotalsResponse(
                    totals.remedyType(),
                    totals.remedyCount(),
                    ApiMoney.of(totals.amountMinor(), totals.currency()),
                    ApiMoney.of(totals.attestedMoneyMinor(), totals.currency()),
                    ApiMoney.of(totals.platformSettledMinor(), totals.currency()),
                    ApiMoney.of(totals.unverifiedMinor(), totals.currency()));
        }
    }
}
