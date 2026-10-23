package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.MethodRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.SettlementRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.TenderRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * How the customer's own order is being paid: the points they spent and the money that settles the rest
 * (ADR 0046, ADR 0185). The order page's split.
 *
 * <p>This is the customer-safe face of the settlement the operations console reads as {@code payment[]}. Each
 * tender is its kind, its method code, its amount and its status, in the order they settle, and nothing a staff
 * member would be told for a stated purpose: no refunded figure, no intent, no loyalty reservation, no
 * registry row, no fiscal responsibility. The ownership check is the authorization decision and no ADR 0025
 * capability is declared, for the reason {@code StorefrontPaymentController} gives; the order must be the
 * caller's own account's, at the brand in the path, and a stranger's order and a missing one are answered with
 * the same words.
 *
 * <p>An order with no settlement (placed before the seam existed, or with a method this build cannot tender
 * against) answers 404 {@code NO_PAYMENT_SPLIT}, which a storefront reads as «nothing to show».
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Storefront payments", description = "How a customer's own order is being paid")
public class StorefrontPaymentSplitController {

    private final JdbcSettlementStore settlements;
    private final OrderDirectory orders;
    private final CurrentCustomer currentCustomer;

    public StorefrontPaymentSplitController(
            JdbcSettlementStore settlements, OrderDirectory orders, CurrentCustomer currentCustomer) {
        this.settlements = settlements;
        this.orders = orders;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/orders/{orderId}/payment-split")
    @CustomerOwned
    @Operation(
            summary = "How one of the caller's own orders is being paid",
            description = "The tenders of the order's settlement in the order they settle: POINTS (what the "
                    + "customer spent of their points) and MONEY (what a provider or the courier collects, with "
                    + "its method code). Status is the tender's: PLANNED, RESERVED, SETTLED, RELEASED (the points "
                    + "went back), REVERSED (refunded) or FAILED. Another customer's order and a missing one are "
                    + "both 404; an order with no settlement is 404 NO_PAYMENT_SPLIT.")
    public ResponseEntity<PaymentSplitResponse> orderPaymentSplit(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID orderId) {
        UUID accountId = currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .filter(summary -> brandId.equals(summary.brandId()))
                .filter(summary -> accountId.equals(summary.customerAccountId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
        SettlementRow settlement = settlements
                .findSettlement(tenantId, orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "This order has no payment to show",
                        java.util.Map.of("reason", "NO_PAYMENT_SPLIT")));
        List<PaymentSplitTenderResponse> tenders = settlements.tendersOf(tenantId, settlement.id()).stream()
                .map(tender -> PaymentSplitTenderResponse.of(tender, methodCode(tenantId, tender)))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PaymentSplitResponse(orderId, ApiMoney.of(order.totalMinor(), order.currency()), tenders));
    }

    private Optional<String> methodCode(UUID tenantId, TenderRow tender) {
        return settlements.findMethod(tenantId, tender.paymentMethodId()).map(MethodRow::code);
    }

    /**
     * @param total what the order is worth, which the tenders sum to
     * @param tenders in the order they settle: the points first, because that is how the stored plan is sequenced
     */
    public record PaymentSplitResponse(UUID orderId, ApiMoney total, List<PaymentSplitTenderResponse> tenders) {}

    /**
     * @param kind       {@code POINTS} or {@code MONEY}
     * @param methodCode the payment method of a {@code MONEY} tender (CASH, CLICK, PAYME...), null for points
     */
    public record PaymentSplitTenderResponse(
            String kind, @Nullable String methodCode, ApiMoney amount, String status) {

        static PaymentSplitTenderResponse of(TenderRow tender, Optional<String> methodCode) {
            return new PaymentSplitTenderResponse(
                    tender.settlesFromBalance() ? "POINTS" : "MONEY",
                    tender.settlesFromBalance() ? null : methodCode.orElse(null),
                    ApiMoney.of(tender.amountMinor(), tender.currency()),
                    tender.status().name());
        }
    }
}
