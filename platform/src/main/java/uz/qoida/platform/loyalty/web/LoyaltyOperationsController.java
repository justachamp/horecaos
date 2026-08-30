package uz.qoida.platform.loyalty.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.ApprovalOutcome;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.loyalty.application.LoyaltyAdjustmentService;
import uz.qoida.platform.loyalty.application.LoyaltyAdjustmentService.AdjustmentCommand;
import uz.qoida.platform.loyalty.application.LoyaltyQueryService;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LiabilityRow;
import uz.qoida.platform.web.api.ApiMoney;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * What support and finance do with points (ADR 0046).
 *
 * <p>Two things and no more: read a customer's balances, and move one by hand
 * with a reason attached. There is no transfer endpoint. The adjustment body
 * takes one account and one signed amount and there is no variant that takes
 * two, so moving points between two people is two separate approved acts rather
 * than one call — which is what makes it countable on a report instead of
 * invisible inside one.
 *
 * <p>The liability report is here rather than in reporting because the number is
 * derived from the ledger and has to reconcile to it. It is broken out per brand
 * for a reason that is not presentational: a brand's outstanding points are the
 * liability of the legal entity that will honour them, and one tenant routinely
 * contains several taxpayers.
 */
@RestController
@RequestMapping("/api/v1/operations")
@Tag(name = "Loyalty operations", description = "Balances, manual adjustments, and liability")
public class LoyaltyOperationsController {

    private final LoyaltyQueryService loyalty;
    private final LoyaltyAdjustmentService adjustments;

    public LoyaltyOperationsController(LoyaltyQueryService loyalty,
            LoyaltyAdjustmentService adjustments) {
        this.loyalty = loyalty;
        this.adjustments = adjustments;
    }

    @GetMapping("/tenants/{tenantId}/customers/{customerId}/loyalty")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Every points balance one customer holds",
            description = "One per brand, each labelled by the brand that will honour it. Under "
                    + "TENANT_SHARED identity this is a combined read and never a combined pool: "
                    + "points earned at one brand cannot be spent at another.")
    public ResponseEntity<List<LoyaltyStorefrontController.BalanceResponse>> balances(
            @PathVariable UUID tenantId, @PathVariable UUID customerId) {
        return ResponseEntity.ok(loyalty.balancesOfCustomer(tenantId, customerId).stream()
                .map(LoyaltyStorefrontController.BalanceResponse::of)
                .toList());
    }

    @PostMapping("/tenants/{tenantId}/customers/{customerId}/loyalty/adjustments")
    @RequiresCapability(value = Capability.LOYALTY_ADJUST, scope = ScopeType.TENANT,
            mutating = true)
    @Operation(summary = "Credit or debit a balance by hand",
            description = "One account, one signed amount, one reason. Above the configured "
                    + "threshold it needs an ADR 0027 approval and returns PENDING until a second "
                    + "person decides it. There is no paired form and no transfer: two offsetting "
                    + "adjustments are two separate approved acts.")
    public ResponseEntity<AdjustmentResponse> adjust(@PathVariable UUID tenantId,
            @PathVariable UUID customerId, @RequestBody AdjustmentRequest request) {

        ApprovalOutcome outcome = adjustments.adjust(new AdjustmentCommand(tenantId,
                request.brandId(), customerId, request.amountMinor(), request.currency(),
                request.reasonCode(), request.reason(),
                ActorRef.user(request.actorSubject(), null), request.idempotencyKey(),
                request.correlationId()));

        return ResponseEntity.ok(AdjustmentResponse.of(outcome));
    }

    @GetMapping("/tenants/{tenantId}/reports/loyalty-liability")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Outstanding points, per brand",
            description = "What each brand would owe if every point were spent, and how much of "
                    + "it an unfinished checkout is currently holding. Never pooled into one "
                    + "tenant figure: the liability belongs to the brand's legal entity.")
    public ResponseEntity<List<LiabilityResponse>> liability(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(loyalty.liability(tenantId).stream()
                .map(LiabilityResponse::of)
                .toList());
    }

    /**
     * @param amountMinor signed whole som. There is no second account field on
     *                    this record, and adding one would be the transfer this
     *                    design refuses
     */
    public record AdjustmentRequest(
            @NotNull UUID brandId,
            long amountMinor,
            @NotBlank String currency,
            @NotBlank String reasonCode,
            @NotBlank String reason,
            @NotBlank String actorSubject,
            @NotBlank String idempotencyKey,
            String correlationId) {
    }

    /** @param status NOT_REQUIRED, PENDING, APPROVED, or DECLINED (ADR 0027) */
    public record AdjustmentResponse(String status, UUID approvalRequestId) {

        static AdjustmentResponse of(ApprovalOutcome outcome) {
            return switch (outcome) {
                case ApprovalOutcome.NotRequired ignored ->
                        new AdjustmentResponse("NOT_REQUIRED", null);
                case ApprovalOutcome.Pending pending ->
                        new AdjustmentResponse("PENDING", pending.requestId());
                case ApprovalOutcome.Approved approved ->
                        new AdjustmentResponse("APPROVED", approved.requestId());
                case ApprovalOutcome.Declined declined ->
                        new AdjustmentResponse("DECLINED", declined.requestId());
            };
        }
    }

    public record LiabilityResponse(UUID brandId, ApiMoney outstanding, ApiMoney held,
            long accountCount) {

        static LiabilityResponse of(LiabilityRow row) {
            return new LiabilityResponse(row.brandId(),
                    ApiMoney.of(row.outstandingMinor(), row.currency()),
                    ApiMoney.of(row.heldMinor(), row.currency()),
                    row.accountCount());
        }
    }
}
