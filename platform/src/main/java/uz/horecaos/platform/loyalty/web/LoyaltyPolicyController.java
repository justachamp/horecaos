package uz.horecaos.platform.loyalty.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService.AccrualRuleDraft;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService.RedemptionPolicyDraft;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccrualRuleAuthoringRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.RedemptionPolicyAuthoringRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's own accrual rate and redemption cap (operations §6.3 Loyalty,
 * ADR 0046).
 *
 * <p>Reads declare {@code LOYALTY_READ}, the same capability the balance and
 * liability endpoints on {@link LoyaltyOperationsController} declare. Every
 * mutation declares {@code LOYALTY_POLICY_MANAGE} at {@code BRAND} scope,
 * exactly as that capability's own Javadoc prescribes — separate from
 * {@code LOYALTY_ADJUST} because these are the numbers, not one customer's
 * balance, and because raising an accrual rate is a tax decision under ADR
 * 0046's fiscal treatment of a redemption.
 *
 * <p><strong>There is no endpoint that returns a default.</strong> A brand
 * with no {@code ACTIVE} row here neither accrues nor redeems — that silence
 * is ADR 0046's own decision, not a gap this controller papers over with a
 * seeded value.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/loyalty")
@Tag(name = "Loyalty policy", description = "A brand's own accrual rate and redemption cap")
public class LoyaltyPolicyController {

    private final LoyaltyPolicyAuthoringService policies;

    public LoyaltyPolicyController(LoyaltyPolicyAuthoringService policies) {
        this.policies = policies;
    }

    // ------------------------------------------------------------- accrual

    @GetMapping("/accrual-rules")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every accrual rule this brand has authored",
            description = "DRAFT, ACTIVE and RETIRED rows together, newest first within each "
                    + "status — an authoring screen needs the lineage, not only what is live.")
    public ResponseEntity<List<AccrualRuleResponse>> accrualRules(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(policies.listAccrualRules(tenantId, brandId).stream()
                .map(AccrualRuleResponse::of)
                .toList());
    }

    @PostMapping("/accrual-rules")
    @RequiresCapability(value = Capability.LOYALTY_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft an accrual rule",
            description = "Always DRAFT. Nothing here accrues until a separate activation call "
                    + "promotes it, so a mistyped rate is caught before it touches a customer's "
                    + "order.")
    public ResponseEntity<AccrualRuleResponse> draftAccrualRule(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody DraftAccrualRuleRequest body) {

        AccrualRuleAuthoringRow drafted = policies.draftAccrualRule(
                tenantId,
                brandId,
                new AccrualRuleDraft(
                        body.scopeType(),
                        body.scopeId(),
                        body.rateBasisPoints(),
                        body.maxAccrualMinor(),
                        body.earnDelayHours(),
                        body.lotLifetimeDays(),
                        body.expiryWarningDays(),
                        body.validFrom(),
                        body.validUntil()));
        return ResponseEntity.ok(AccrualRuleResponse.of(drafted));
    }

    @PostMapping("/accrual-rules/{ruleId}/activate")
    @RequiresCapability(value = Capability.LOYALTY_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a drafted accrual rule live",
            description = "Retires whichever rule currently holds this exact scope (BRAND, or the "
                    + "same LOCATION/CHANNEL), then promotes the draft, in one transaction — a "
                    + "brand's live set never holds two rules resolving the same scope at once.")
    public ResponseEntity<Void> activateAccrualRule(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID ruleId) {
        policies.activateAccrualRule(tenantId, brandId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accrual-rules/{ruleId}/retire")
    @RequiresCapability(value = Capability.LOYALTY_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Withdraw a live rule, or discard a draft nobody activated",
            description = "A retired rule stops resolving from this instant. It is not deleted: "
                    + "every entry an active rule already produced keeps the rule it was earned "
                    + "under (ADR 0046 rule snapshotting).")
    public ResponseEntity<Void> retireAccrualRule(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID ruleId) {
        policies.retireAccrualRule(tenantId, brandId, ruleId);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------- redemption

    @GetMapping("/redemption-policies")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every redemption policy this brand has authored")
    public ResponseEntity<List<RedemptionPolicyResponse>> redemptionPolicies(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(policies.listRedemptionPolicies(tenantId, brandId).stream()
                .map(RedemptionPolicyResponse::of)
                .toList());
    }

    @PostMapping("/redemption-policies")
    @RequiresCapability(value = Capability.LOYALTY_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a redemption policy",
            description = "The share is capped at 9000 basis points by validation as well as by "
                    + "the database: points may never cover a whole order, because a "
                    + "zero-consideration sale has no fiscal path and no cash for a courier to "
                    + "collect.")
    public ResponseEntity<RedemptionPolicyResponse> draftRedemptionPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody DraftRedemptionPolicyRequest body) {

        RedemptionPolicyAuthoringRow drafted = policies.draftRedemptionPolicy(
                tenantId,
                brandId,
                new RedemptionPolicyDraft(
                        body.maxShareBasisPoints(),
                        body.minOrderMinor(),
                        body.excludesDeliveryFee(),
                        body.allowedChannels() == null ? List.of() : body.allowedChannels(),
                        body.validFrom(),
                        body.validUntil()));
        return ResponseEntity.ok(RedemptionPolicyResponse.of(drafted));
    }

    @PostMapping("/redemption-policies/{policyId}/activate")
    @RequiresCapability(value = Capability.LOYALTY_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a drafted redemption policy live",
            description = "Retires the brand's current policy, if any, then promotes the draft, "
                    + "in one transaction — a brand redeems against exactly one live cap.")
    public ResponseEntity<Void> activateRedemptionPolicy(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID policyId) {
        policies.activateRedemptionPolicy(tenantId, brandId, policyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/redemption-policies/{policyId}/retire")
    @RequiresCapability(value = Capability.LOYALTY_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Withdraw the live policy, or discard a draft nobody activated",
            description = "Once retired the brand redeems nothing until a new policy is "
                    + "activated — the same silence a brand that never authored one sees.")
    public ResponseEntity<Void> retireRedemptionPolicy(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID policyId) {
        policies.retireRedemptionPolicy(tenantId, brandId, policyId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param scopeType   {@code BRAND}, {@code LOCATION}, or {@code CHANNEL}
     * @param scopeId     required exactly when scopeType is not {@code BRAND}
     * @param rateBasisPoints basis points of the money-settled, fee-excluded order
     *                    value — 300 is 3%. There is no default; product and
     *                    finance confirm this number for the brand it applies to
     * @param maxAccrualMinor null for uncapped
     * @param validFrom   null takes effect immediately on activation
     */
    public record DraftAccrualRuleRequest(
            @NotNull @Pattern(regexp = "BRAND|LOCATION|CHANNEL")
            String scopeType,

            @Nullable UUID scopeId,
            @Min(0) @Max(10_000) int rateBasisPoints,
            @Nullable Long maxAccrualMinor,
            @PositiveOrZero int earnDelayHours,
            @Min(1) int lotLifetimeDays,
            @PositiveOrZero int expiryWarningDays,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    public record AccrualRuleResponse(
            UUID id,
            String scopeType,
            @Nullable UUID scopeId,
            int rateBasisPoints,
            @Nullable Long maxAccrualMinor,
            int earnDelayHours,
            int lotLifetimeDays,
            int expiryWarningDays,
            String status,
            int version,
            Instant validFrom,
            @Nullable Instant validUntil) {

        static AccrualRuleResponse of(AccrualRuleAuthoringRow row) {
            return new AccrualRuleResponse(
                    row.id(),
                    row.scopeType(),
                    row.scopeId(),
                    row.rateBasisPoints(),
                    row.maxAccrualMinor(),
                    row.earnDelayHours(),
                    row.lotLifetimeDays(),
                    row.expiryWarningDays(),
                    row.status(),
                    row.version(),
                    row.validFrom(),
                    row.validUntil());
        }
    }

    /**
     * @param maxShareBasisPoints 1-9000; points may never cover a whole order
     * @param allowedChannels     channel codes, not identifiers. Null or empty
     *                            both mean every channel — {@code
     *                            PointsRedemptionService} treats an empty list as
     *                            no restriction, so this field carries that
     *                            meaning through rather than inventing a second
     *                            way to say it
     */
    public record DraftRedemptionPolicyRequest(
            @Min(1) @Max(9_000) int maxShareBasisPoints,
            @PositiveOrZero long minOrderMinor,
            boolean excludesDeliveryFee,
            @Nullable List<String> allowedChannels,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    public record RedemptionPolicyResponse(
            UUID id,
            int maxShareBasisPoints,
            long minOrderMinor,
            boolean excludesDeliveryFee,
            List<String> allowedChannels,
            String status,
            int version,
            Instant validFrom,
            @Nullable Instant validUntil) {

        static RedemptionPolicyResponse of(RedemptionPolicyAuthoringRow row) {
            return new RedemptionPolicyResponse(
                    row.id(),
                    row.maxShareBasisPoints(),
                    row.minOrderMinor(),
                    row.excludesDeliveryFee(),
                    row.allowedChannels(),
                    row.status(),
                    row.version(),
                    row.validFrom(),
                    row.validUntil());
        }
    }
}
