package uz.horecaos.platform.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort.Redemption;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Row 7.9a: how much a customer account has been discounted, tenant-wide
 * (ADR 0072). {@code CustomerDiscountHistoryPort}'s only HTTP adapter,
 * called by two consumers — the customer detail pane (the {@code P40} wave,
 * not yet merged) and the marketing reports screen (this wave).
 *
 * <p>Tenant-scoped rather than brand-scoped, matching the port: a customer
 * account can hold redemptions against more than one brand under the same
 * tenant, and the question this screen answers — "how much has this
 * customer been discounted" — means everywhere in the tenant.
 *
 * <p>{@code PRICING_READ}, the same capability {@code PromoCodeController}
 * already declares for reading a coupon's own redemptions — this is the
 * same fact, sliced by customer instead of by coupon.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/customers/{customerAccountId}/discount-history")
@Tag(name = "Customer discount history", description = "Row 7.9a: per-customer coupon discount totals (ADR 0072)")
public class CustomerDiscountHistoryController {

    private final CustomerDiscountHistoryPort discountHistory;

    public CustomerDiscountHistoryController(CustomerDiscountHistoryPort discountHistory) {
        this.discountHistory = discountHistory;
    }

    @GetMapping
    @RequiresCapability(Capability.PRICING_READ)
    @Operation(
            summary = "Every coupon-gated discount this customer has ever held, tenant-wide",
            description = "Reservation, redemption and release rows across every brand this "
                    + "customer has ordered from, newest first, plus how much of it actually paid "
                    + "out — the totalsRedeemed breakdown sums only REDEEMED rows, grouped by "
                    + "currency because a multi-brand tenant can run more than one. Account, "
                    + "coupon and order ids only; no contact value crosses this endpoint and none "
                    + "can.")
    public ResponseEntity<CustomerDiscountHistoryResponse> history(
            @PathVariable UUID tenantId, @PathVariable UUID customerAccountId) {

        List<Redemption> redemptions = discountHistory.history(tenantId, customerAccountId);

        Map<String, Long> totalsByCurrency = new TreeMap<>();
        for (Redemption redemption : redemptions) {
            if (redemption.status() == Redemption.Status.REDEEMED) {
                totalsByCurrency.merge(redemption.currency(), redemption.amountMinor(), Long::sum);
            }
        }

        return ResponseEntity.ok(new CustomerDiscountHistoryResponse(
                redemptions.stream().map(CustomerCouponRedemptionResponse::of).toList(),
                totalsByCurrency.entrySet().stream()
                        .map(entry -> new CurrencyTotalResponse(entry.getKey(), entry.getValue()))
                        .toList()));
    }

    // ------------------------------------------------------------- wire shapes

    /** @param totalsRedeemed one entry per currency actually redeemed; empty when nothing ever redeemed */
    public record CustomerDiscountHistoryResponse(
            List<CustomerCouponRedemptionResponse> redemptions, List<CurrencyTotalResponse> totalsRedeemed) {}

    public record CurrencyTotalResponse(String currency, long amountMinor) {}

    /**
     * @param codeHint null only if the issuing coupon's own hint was never set
     * @param orderId  null until the reservation redeems; a released reservation never gets one at all
     */
    public record CustomerCouponRedemptionResponse(
            UUID redemptionId,
            UUID brandId,
            UUID couponId,
            @Nullable String codeHint,
            UUID promotionId,
            String promotionName,
            @Nullable UUID orderId,
            String status,
            long amountMinor,
            String currency,
            Instant reservedAt,
            @Nullable Instant redeemedAt,
            @Nullable Instant releasedAt) {

        static CustomerCouponRedemptionResponse of(Redemption redemption) {
            return new CustomerCouponRedemptionResponse(
                    redemption.redemptionId(),
                    redemption.brandId(),
                    redemption.couponId(),
                    redemption.codeHint(),
                    redemption.promotionId(),
                    redemption.promotionName(),
                    redemption.orderId(),
                    redemption.status().name(),
                    redemption.amountMinor(),
                    redemption.currency(),
                    redemption.reservedAt(),
                    redemption.redeemedAt(),
                    redemption.releasedAt());
        }
    }
}
