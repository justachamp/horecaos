package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The day's basket and checkout counts, for the customer funnel (ADR 0167, row 7.6c).
 *
 * <p>Implemented by {@code ordering}, read by {@code reporting}'s day close -- never a report
 * reading {@code ordering} tables (ADR 0023). It is the shape {@link AbandonedCartDirectory}
 * already has, with one difference that is the whole point: nothing here names a person. A
 * count carries a brand and a sales-channel code and two integers; no account id, no guest
 * reference hash, no cart id. The funnel stores counts, and a count that cannot be joined to a
 * person is not personal data (ADR 0167 Decision 3).
 *
 * <p>The counts are read from the live tables because the sweeper deletes an abandoned cart
 * ninety days after it was last touched (ADR 0092): a basket count not captured before then is
 * gone. {@link #retainedFrom} says where that cliff is, so the close can write "not recorded"
 * rather than a zero for a day it can no longer count.
 */
public interface CartFunnelSource {

    /**
     * The earliest instant whose carts are all still held. A business day starting before this
     * may have lost carts to the retention sweep, so a count read for it would understate and
     * must be recorded as "not recorded", never as the number found.
     */
    Instant retainedFrom(UUID tenantId);

    /**
     * Distinct carts whose first line was added inside {@code [from, to)}, and distinct carts
     * with a checkout attempt inside it, per brand and sales channel. A cart with several
     * lines, or several attempts, counts once; a guest cart and an account cart count alike.
     * A brand and channel with neither is absent from the answer.
     */
    List<CartDayCount> countBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive);

    /**
     * @param channelCode the sales channel's tenant-chosen code, the same one an order snapshots
     *                    (so a cart and the order it became fall in the same channel row)
     */
    record CartDayCount(UUID brandId, String channelCode, int basketsStarted, int checkoutsStarted) {}
}
