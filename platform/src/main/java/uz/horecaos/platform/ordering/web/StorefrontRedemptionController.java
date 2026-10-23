package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.ordering.application.CartRedemptionOffers;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What the signed-in customer may spend of their points on the basket they are about to check out (ADR 0046,
 * ADR 0185).
 *
 * <p>ADR 0185 declines a redemption recorded after the order exists, so this read is where a customer learns what
 * they may redeem. It is answered for a cart, scoped to the caller's own account exactly as the cart read and the
 * payment-method read are: a cart id is not a bearer token, and a cart that is not the caller's, not at this
 * brand, or missing is one answer.
 *
 * <p>Read-only, and it holds nothing: the points are debited inside the checkout transaction. The authorization
 * decision is the ownership check and no ADR 0025 capability is declared, for the reason the rest of the
 * customer-owned storefront reads give: a customer holds no staff capability and is not meant to.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Storefront redemption", description = "What a customer may spend of their points on a basket")
public class StorefrontRedemptionController {

    private final CartRedemptionOffers cartOffers;
    private final CurrentCustomer currentCustomer;

    public StorefrontRedemptionController(CartRedemptionOffers cartOffers, CurrentCustomer currentCustomer) {
        this.cartOffers = cartOffers;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/carts/{cartId}/redemption-offer")
    @CustomerOwned
    @Operation(
            summary = "What this cart's customer may spend of their points on it",
            description = "Read from the quote the cart is bound to, so the maximum is the figure checkout will "
                    + "accept. A cart that has not been priced, or whose quote has expired, offers nothing and says "
                    + "so in refusal (CART_NOT_PRICED, QUOTE_EXPIRED). Spend the points by sending "
                    + "redeemFromBalanceMinor with the checkout; it is taken inside that transaction and never "
                    + "after the order exists (ADR 0185). refusal is a code: NO_ACCOUNT, REDEMPTION_NOT_ENABLED, "
                    + "CHANNEL_NOT_ELIGIBLE, ACCOUNT_NOT_ACTIVE, ORDER_NOT_ELIGIBLE, INSUFFICIENT_BALANCE or "
                    + "REDEMPTION_BLOCKED_BY_PROMOTION. Nothing is held.")
    public ResponseEntity<CartRedemptionOfferResponse> cartRedemptionOffer(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID cartId) {
        UUID accountId = currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
        CartRedemptionOffers.CartOffer offer = cartOffers
                .forCart(tenantId, brandId, accountId, cartId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such cart"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CartRedemptionOfferResponse(
                        offer.cartId(),
                        offer.priced(),
                        ApiMoney.of(offer.orderTotalMinor(), offer.currency()),
                        RedemptionOfferResponses.RedemptionOfferView.of(offer.offer())));
    }

    /**
     * @param priced     whether the cart has a live quote to offer against
     * @param orderTotal the figure the order would be placed at; the cash still owed is this less what is redeemed
     */
    public record CartRedemptionOfferResponse(
            UUID cartId, boolean priced, ApiMoney orderTotal, RedemptionOfferResponses.RedemptionOfferView offer) {}
}
