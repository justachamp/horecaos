package uz.horecaos.platform.courier.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.courier.domain.AccrualCalculator;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;

/**
 * What a courier is told they will earn, before they accept (ADR 0170 Decision 5, ADR 0042).
 *
 * <p>The quote is {@link AccrualCalculator#forDelivery} over the distance the offer is paid on and the rate
 * card that resolves for the courier's type at the branch, the same resolution {@code
 * CourierAccrualService.recordDelivery} makes at acceptance. It is the figure the delivery then accrues, because
 * both are the same function of the same stamp and the same card; it is not a promise about a card that is
 * edited between the offer and the acceptance (the accrual resolves at {@code acceptedAt}, as it always has).
 *
 * <p>An offer for which no card resolves carries no quote. A courier without a resolvable card is not offered a
 * grouped order at all ({@code InternalFleetAdapter}), so the case that remains is a direct order, whose
 * failure today is at delivery ("a delivery cannot be accrued against nothing"), and which this surfaces early
 * by showing no figure rather than an invented one.
 */
@Service
public class CourierOfferQuoteService {

    private final JdbcCourierRateCardStore rateCards;
    private final JdbcCourierStore couriers;

    public CourierOfferQuoteService(JdbcCourierRateCardStore rateCards, JdbcCourierStore couriers) {
        this.rateCards = rateCards;
        this.couriers = couriers;
    }

    /**
     * @param distanceMeters the order's own branch-to-door distance; null when the plan has none (no quote)
     * @param runMergeDistanceMeters the run distance of a {@code RUN_INCREMENT} offer; null for {@code DIRECT}
     */
    public Optional<Quote> quote(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID courierId,
            @Nullable Integer distanceMeters,
            @Nullable Integer runMergeDistanceMeters,
            Instant at) {

        if (distanceMeters == null) {
            return Optional.empty();
        }
        Optional<UUID> type = couriers.findCourier(tenantId, courierId).map(JdbcCourierStore.CourierRow::courierTypeId);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        Optional<RateCard> card = rateCards.resolve(tenantId, brandId, locationId, type.get(), at);
        if (card.isEmpty()) {
            return Optional.empty();
        }
        int paid = distancePaid(distanceMeters, runMergeDistanceMeters);
        long total = AccrualCalculator.forDelivery(card.get(), paid).totalMinor();
        return Optional.of(new Quote(total, card.get().currency(), paid));
    }

    /** The distance an offer is paid on: its own, or the shorter run distance when it is smaller. */
    public static int distancePaid(int directDistanceMeters, @Nullable Integer runMergeDistanceMeters) {
        return runMergeDistanceMeters == null
                ? directDistanceMeters
                : Math.min(directDistanceMeters, runMergeDistanceMeters);
    }

    /**
     * @param amountMinor    what the order earns, in the card's currency's minor unit
     * @param distanceMeters the distance it is paid on, so the app can say «paid on 400 m, the extra distance»
     */
    public record Quote(long amountMinor, String currency, int distanceMeters) {}
}
