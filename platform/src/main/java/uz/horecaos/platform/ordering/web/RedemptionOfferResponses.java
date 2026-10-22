package uz.horecaos.platform.ordering.web;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.api.RedemptionOfferPort;
import uz.horecaos.platform.web.api.ApiMoney;

/**
 * The wire form of a redemption offer, shared by the storefront's cart read and the operator's twin so that a
 * customer is shown the same figures on either side of the same counter (ADR 0185).
 *
 * <p>Money is an object of integer minor units and a currency (ADR 0031). Nothing here names the customer.
 */
final class RedemptionOfferResponses {

    private RedemptionOfferResponses() {}

    /**
     * What may be redeemed against one order.
     *
     * @param accountId  the points account the offer was made against, null when the customer holds none
     * @param available  what the account can spend today
     * @param maximum    the largest redemption this order permits; zero when {@code refusal} is set
     * @param refusal    a code that says why nothing is redeemable, or null
     */
    record RedemptionOfferView(
            @Nullable UUID accountId,
            ApiMoney available,
            ApiMoney maximum,
            boolean redeemable,
            @Nullable String refusal) {

        static RedemptionOfferView of(RedemptionOfferPort.Offer offer) {
            return new RedemptionOfferView(
                    offer.accountId(),
                    ApiMoney.of(offer.availableMinor(), offer.currency()),
                    ApiMoney.of(offer.maximumMinor(), offer.currency()),
                    offer.redeemable(),
                    offer.refusal());
        }
    }
}
