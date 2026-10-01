package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How many orders a customer has already placed, for the Nth-order and
 * first-order conditions (ADR 0140).
 *
 * <p>Declared here and implemented by {@code ordering}, the same inverted
 * direction as {@code CustomerOrderActivityPort}: {@code ordering} already depends
 * on {@code pricing.api}, so a {@code pricing -> ordering} dependency would close a
 * cycle the module verifier refuses. The count is exact, not a projection -- it does
 * not read {@code marketing.customer_metrics}, which is eventually consistent -- so
 * two quick orders cannot both be "first".
 *
 * <p>Only called when an active promotion carries an {@code ORDER_SEQUENCE} or
 * {@code FIRST_ORDER} condition, and never for a guest.
 */
public interface CustomerOrderHistoryPort {

    /**
     * The account's orders at the brand, placed before {@code placedBefore} and not
     * {@code CANCELLED} or {@code REJECTED}.
     *
     * @param basis        whether to count across the brand or only on one channel
     * @param channelId    required for {@link Basis#CHANNEL}, ignored otherwise
     * @param excludingOrderId the order being amended, so it is not its own
     *                     predecessor; null for a cart that has no order yet
     */
    int countPriorOrders(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            Basis basis,
            @Nullable UUID channelId,
            Instant placedBefore,
            @Nullable UUID excludingOrderId);

    enum Basis {
        BRAND,
        CHANNEL
    }
}
