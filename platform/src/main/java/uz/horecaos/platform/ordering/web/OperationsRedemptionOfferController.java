package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.CartRedemptionOffers;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.OperatorRedemptionOffers;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The New order screen's points control: what the customer on the phone may spend of their points on the order
 * being taken, and the cash they would still owe (ADR 0046, ADR 0185, ADR 0039).
 *
 * <p>The operator's twin of {@link StorefrontRedemptionController}. The body is the one the quote and the order
 * take, so the basket is priced by the same path and the customer is offered against the figure the order would
 * be booked at. Nothing is written, nothing is reserved and the points are not touched: they are debited inside the
 * checkout transaction of {@code POST .../orders}, and only when the operator confirms the customer agreed.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Operations orders", description = "The operator's view of a location's orders")
public class OperationsRedemptionOfferController {

    private final OperatorRedemptionOffers offers;
    private final CurrentActor currentActor;

    public OperationsRedemptionOfferController(OperatorRedemptionOffers offers, CurrentActor currentActor) {
        this.offers = offers;
        this.currentActor = currentActor;
    }

    @PostMapping("/redemption-offer")
    @RequiresCapability(value = Capability.ORDER_PLACE, scope = ScopeType.LOCATION)
    @Idempotent
    @Operation(
            summary = "What the customer may spend of their points on the order being taken",
            description = "The same body as `POST .../orders` and `POST .../orders/quote`, priced by the same path and "
                    + "then undone, and the answer is what the identified customer could redeem against the "
                    + "figure the order would be booked at: their spendable points, the largest redemption the "
                    + "brand's rules permit, why it is zero when it is, and the cash the customer would still "
                    + "owe for the redemption in redeemFromBalanceMinor (null when that is not an amount the "
                    + "offer permits, with requestedRefusal saying so). Nothing is written and nothing is held. "
                    + "The points are spent by sending redeemFromBalanceMinor and "
                    + "operatorAttestsCustomerAgreement: true with `POST .../orders`, inside its transaction, and "
                    + "never after the order exists (ADR 0185). A POST because the basket is a body; "
                    + "`ORDER_PLACE` because the answer is a figure for a customer's would-be order. Needs an "
                    + "`Idempotency-Key`, which the screen mints fresh on every call.")
    public ResponseEntity<OperatorRedemptionOfferResponse> offer(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody OperationsOrderController.PlaceOrderRequest body) {
        try {
            OperatorRedemptionOffers.OperatorOffer made = offers.offer(body.toPricingCommand(
                    tenantId,
                    brandId,
                    locationId,
                    idempotencyKey,
                    currentActor.get().subject()));
            long requested = body.redeemFromBalanceMinor() == null ? 0L : body.redeemFromBalanceMinor();
            Long cash = requested == 0L
                    ? Long.valueOf(made.orderTotalMinor())
                    : CartRedemptionOffers.cashToCollect(made.orderTotalMinor(), made.offer(), requested);
            return ResponseEntity.ok(new OperatorRedemptionOfferResponse(
                    body.customerAccountId(),
                    ApiMoney.of(made.orderTotalMinor(), made.currency()),
                    ApiMoney.of(made.deliveryFeeMinor(), made.currency()),
                    RedemptionOfferResponses.RedemptionOfferView.of(made.offer()),
                    requested == 0L ? null : ApiMoney.of(requested, made.currency()),
                    requested == 0L || cash != null ? null : "EXCEEDS_MAXIMUM",
                    cash == null ? null : ApiMoney.of(cash, made.currency())));
        } catch (CartService.CartRefusedException refused) {
            throw StorefrontOrderingController.refusal(refused);
        } catch (CartService.StaleCartException impossible) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, impossible.getMessage());
        } catch (CartPricingPort.PricingRefusedException unpriced) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unpriced.getMessage(),
                    Map.of("reason", unpriced.code(), "subjectId", String.valueOf(unpriced.subjectId())));
        }
    }

    /**
     * @param requested        the redemption the request asked for, or null when it asked for none
     * @param requestedRefusal {@code EXCEEDS_MAXIMUM} when {@code requested} is above what the offer permits
     * @param cashToCollect    the order total less the accepted redemption, null when the request was refused
     */
    public record OperatorRedemptionOfferResponse(
            UUID customerAccountId,
            ApiMoney orderTotal,
            ApiMoney deliveryFee,
            RedemptionOfferResponses.RedemptionOfferView offer,
            @Nullable ApiMoney requested,
            @Nullable String requestedRefusal,
            @Nullable ApiMoney cashToCollect) {}
}
