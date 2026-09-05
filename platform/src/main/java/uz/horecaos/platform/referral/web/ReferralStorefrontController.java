package uz.horecaos.platform.referral.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.referral.application.ReferralCodeService;
import uz.horecaos.platform.referral.application.ReferralQueryService;
import uz.horecaos.platform.referral.application.ReferralRedemptionService;
import uz.horecaos.platform.referral.application.ReferralRedemptionService.RedeemCommand;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.CodeRow;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * What a customer can do with a referral code, as themselves (operations
 * §6.6 Referrals).
 *
 * <p>Authorised by account ownership ({@link CustomerOwned}), the same
 * standing {@code StorefrontCustomerController} already uses and which ADR
 * 0046's own storefront loyalty endpoints do not: those still declare a
 * staff {@code LOYALTY_READ} capability no customer principal can hold. This
 * controller does not repeat that gap — a customer reading their own
 * referral code, or redeeming a friend's, is exercising ownership of their
 * own account, not delegated staff authority, so it is authorised the way
 * {@code StorefrontCustomerController} already establishes for exactly this
 * kind of self-service action.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/referrals")
@Tag(name = "Referrals", description = "A customer's own referral code, and redeeming a friend's")
public class ReferralStorefrontController {

    private final ReferralCodeService codes;
    private final ReferralRedemptionService redemptions;
    private final ReferralQueryService referrals;
    private final CurrentCustomer currentCustomer;

    public ReferralStorefrontController(
            ReferralCodeService codes,
            ReferralRedemptionService redemptions,
            ReferralQueryService referrals,
            CurrentCustomer currentCustomer) {
        this.codes = codes;
        this.redemptions = redemptions;
        this.referrals = referrals;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/me")
    @CustomerOwned
    @Operation(
            summary = "The caller's own referral code, and their own redemption if they used one",
            description = "Mints the code on first read rather than requiring a separate create "
                    + "call — a customer's own code is a durable identity, not a resource with a "
                    + "creation step worth exposing.")
    public ResponseEntity<MyReferralResponse> myReferral(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        CodeRow code = codes.myCode(tenantId, brandId, accountId);
        RedemptionRow redemption =
                referrals.myRedemption(tenantId, brandId, accountId).orElse(null);
        return ResponseEntity.ok(MyReferralResponse.of(code, redemption));
    }

    @PostMapping("/redemptions")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Redeem a friend's referral code",
            description = "Refused when the code is the caller's own (self-referral), when this "
                    + "account has already redeemed a code (at most one, ever, per brand), or when "
                    + "the brand runs no referral program right now. Nothing is credited by this "
                    + "call itself: the reward fires later, on the caller's first order to reach "
                    + "COMPLETED.")
    public ResponseEntity<RedemptionResponse> redeem(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody RedeemRequest body) {
        UUID accountId = accountId(tenantId, brandId);
        RedemptionRow redemption = redemptions.redeem(new RedeemCommand(tenantId, brandId, accountId, body.code()));
        return ResponseEntity.ok(RedemptionResponse.of(redemption));
    }

    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    // ------------------------------------------------------------- wire shapes

    public record RedeemRequest(@NotBlank String code) {}

    public record MyReferralResponse(String code, @Nullable RedemptionResponse redeemedAs) {

        static MyReferralResponse of(CodeRow code, @Nullable RedemptionRow redemption) {
            return new MyReferralResponse(code.code(), redemption == null ? null : RedemptionResponse.of(redemption));
        }
    }

    public record RedemptionResponse(
            String status,
            Instant redeemedAt,
            Instant expiresAt,
            @Nullable Instant rewardedAt) {

        static RedemptionResponse of(RedemptionRow row) {
            return new RedemptionResponse(row.status(), row.redeemedAt(), row.expiresAt(), row.rewardedAt());
        }
    }
}
