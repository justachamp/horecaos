package uz.horecaos.platform.loyalty.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Which lots a redemption takes, and how much from each (ADR 0046).
 *
 * <p>Oldest-expiry-first, then oldest-granted-first. The alternative — spending
 * the newest lot first, or treating the balance as one undifferentiated number —
 * expires as a block on one date, and the customer who earned steadily loses
 * everything at once and complains, correctly.
 *
 * <p>Decided once, at reservation, and stored. Recomputing the split at release
 * or refund time would return points to whichever lots looked oldest by then,
 * which is how an expiry date quietly moves and how a refund becomes a small
 * gift.
 *
 * <p>Pure, and deliberately so: this is the arithmetic a customer disputes, and
 * a function that reads no clock and no database is one a test can pin exactly.
 */
public record LotConsumption(UUID lotId, long amountMinor, Instant expiresAt) {

    public LotConsumption {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A lot consumption moves a positive amount");
        }
    }

    /** A lot as the planner sees it: an amount available and the two sort keys. */
    public record AvailableLot(UUID lotId, long remainingMinor, Instant expiresAt, Instant grantedAt) {}

    /**
     * Plans the consumption of {@code amountMinor} across the available lots.
     *
     * @throws InsufficientBalanceException when the lots cannot cover the amount.
     *         Refused rather than partially planned: a redemption that spends
     *         what it can and asks the customer for the rest is a checkout that
     *         charges a different figure from the one on the screen
     */
    public static List<LotConsumption> plan(List<AvailableLot> lots, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A redemption spends a positive amount");
        }

        List<AvailableLot> ordered = new ArrayList<>(lots);
        ordered.sort(Comparator.comparing(AvailableLot::expiresAt)
                .thenComparing(AvailableLot::grantedAt)
                // Ties broken on the identifier so two runs over the same lots
                // produce the same plan. Without it a customer refunded twice
                // could see two different lots restored.
                .thenComparing(AvailableLot::lotId));

        List<LotConsumption> plan = new ArrayList<>();
        long outstanding = amountMinor;
        for (AvailableLot lot : ordered) {
            if (outstanding == 0) {
                break;
            }
            long taken = Math.min(outstanding, lot.remainingMinor());
            if (taken <= 0) {
                continue;
            }
            plan.add(new LotConsumption(lot.lotId(), taken, lot.expiresAt()));
            outstanding -= taken;
        }

        if (outstanding > 0) {
            throw new InsufficientBalanceException(
                    "The available lots cover " + (amountMinor - outstanding) + " of a requested " + amountMinor);
        }
        return List.copyOf(plan);
    }

    /** Thrown when a redemption asks for more than the lots hold. */
    public static final class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String message) {
            super(message);
        }
    }
}
