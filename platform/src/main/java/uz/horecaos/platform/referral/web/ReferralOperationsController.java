package uz.horecaos.platform.referral.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.referral.application.ReferralQueryService;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.BrandSummary;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * "Referrals actually happening" for a marketer (operations §6.6).
 *
 * <p>Deliberately read-only and separate from {@link ReferralPolicyController}:
 * this is what a marketer watches, not what they author, and it stays
 * reachable even for a brand whose program is retired — the redemptions a
 * retired program already produced are exactly the numbers a marketer asks
 * about after turning a program off.
 *
 * <p>No contact value crosses this boundary. Referrer and referee are opaque
 * {@code customerAccountId} references, per ADR 0029 — the same restraint
 * ADR 0046's own loyalty entry reads already apply.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/referrals")
@Tag(name = "Referrals", description = "Referral codes and redemptions actually happening at a brand")
public class ReferralOperationsController {

    private final ReferralQueryService referrals;

    public ReferralOperationsController(ReferralQueryService referrals) {
        this.referrals = referrals;
    }

    @GetMapping("/summary")
    @RequiresCapability(value = Capability.REFERRAL_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Codes issued, redemptions by status, and points paid out",
            description = "The marketer's headline numbers, computed from the same rows the "
                    + "redemption list below shows — never a separately maintained count that "
                    + "can drift from it.")
    public ResponseEntity<SummaryResponse> summary(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(SummaryResponse.of(referrals.summary(tenantId, brandId)));
    }

    @GetMapping("/redemptions")
    @RequiresCapability(value = Capability.REFERRAL_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every redemption at this brand, newest first",
            description = "PENDING, REWARDED, EXPIRED, and VOIDED together — the lineage a "
                    + "marketer or a support agent needs to answer \"why did my friend's code not "
                    + "pay out\".")
    public ResponseEntity<List<RedemptionResponse>> redemptions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(referrals.redemptions(tenantId, brandId).stream()
                .map(RedemptionResponse::of)
                .toList());
    }

    // ------------------------------------------------------------- wire shapes

    public record SummaryResponse(
            long codesIssued,
            long pendingRedemptions,
            long rewardedRedemptions,
            long closedRedemptions,
            long pointsPaidOutMinor) {

        static SummaryResponse of(BrandSummary row) {
            return new SummaryResponse(
                    row.codesIssued(),
                    row.pendingRedemptions(),
                    row.rewardedRedemptions(),
                    row.expiredOrVoidedRedemptions(),
                    row.pointsPaidOutMinor());
        }
    }

    public record RedemptionResponse(
            UUID id,
            UUID referrerCustomerAccountId,
            UUID refereeCustomerAccountId,
            String status,
            Instant redeemedAt,
            Instant expiresAt,
            @Nullable UUID qualifyingOrderId,
            @Nullable Instant rewardedAt,
            long referrerRewardMinor,
            long refereeRewardMinor,
            boolean referrerPaid,
            boolean refereePaid,
            @Nullable String referrerSkipReason) {

        static RedemptionResponse of(RedemptionRow row) {
            return new RedemptionResponse(
                    row.id(),
                    row.referrerCustomerAccountId(),
                    row.refereeCustomerAccountId(),
                    row.status(),
                    row.redeemedAt(),
                    row.expiresAt(),
                    row.qualifyingOrderId(),
                    row.rewardedAt(),
                    row.referrerRewardMinor(),
                    row.refereeRewardMinor(),
                    row.referrerEntryId() != null,
                    row.refereeEntryId() != null,
                    row.referrerSkipReason());
        }
    }
}
