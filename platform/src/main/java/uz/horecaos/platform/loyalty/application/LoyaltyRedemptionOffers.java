package uz.horecaos.platform.loyalty.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.loyalty.api.PointsRedemptionPort;
import uz.horecaos.platform.ordering.api.RedemptionOfferPort;

/**
 * Answers ordering's question "what could this customer redeem against an order this size" from the redemption
 * rules loyalty already owns (ADR 0046, ADR 0185).
 *
 * <p>A translation and nothing more: the cap, the policy share, the minimum order, the earn delay and the
 * account's standing are all decided by {@link PointsRedemptionPort#quote}, the same code the checkout's
 * reservation re-checks inside its transaction. Having two readings of "how much may be redeemed" is how an
 * offer promises a figure the checkout then refuses, so there is one.
 */
@Service
public class LoyaltyRedemptionOffers implements RedemptionOfferPort {

    private final PointsRedemptionPort points;

    public LoyaltyRedemptionOffers(PointsRedemptionPort points) {
        this.points = points;
    }

    @Override
    public Offer offer(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            long orderTotalMinor,
            long deliveryFeeMinor,
            String currency,
            String channelCode) {
        PointsRedemptionPort.RedemptionOffer quoted = points.quote(new PointsRedemptionPort.RedemptionQuery(
                tenantId, brandId, customerAccountId, orderTotalMinor, deliveryFeeMinor, currency, channelCode));
        return new Offer(
                quoted.accountId(),
                quoted.availableMinor(),
                quoted.maximumMinor(),
                quoted.currency(),
                quoted.refusal());
    }
}
