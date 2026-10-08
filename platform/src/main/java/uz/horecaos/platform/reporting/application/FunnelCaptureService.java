package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.customers.api.CustomerRegistrationSource;
import uz.horecaos.platform.customers.api.CustomerRegistrationSource.RegistrationCount;
import uz.horecaos.platform.ordering.api.CartFunnelSource;
import uz.horecaos.platform.ordering.api.CartFunnelSource.CartDayCount;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;

/**
 * The one step the day close takes for the customer funnel (ADR 0167, Decision 3, row 7.6c).
 *
 * <p>Counts, from the live tables, before the sweeper deletes them. A cart is deleted ninety days
 * after it was last touched (ADR 0092), so a basket count not captured by then is gone for good --
 * the one stage of the funnel that cannot be backfilled, and the reason this runs inside the close
 * and not in a report at read time.
 *
 * <p>What it writes holds no subject. Carts and registrations arrive through two ports that return
 * a brand, a channel or an origin and an integer; orders are counted from the facts the close has
 * just derived. None of the three carries an account id, a guest reference hash or a customer
 * subject hash, so none can reach {@code reporting.fact_funnel_day}.
 *
 * <p><strong>"Not recorded" is NULL and never zero.</strong> A business day that began before the
 * retention cliff ({@link CartFunnelSource#retainedFrom}) may have lost abandoned carts; reading
 * what remains would undercount and look like a quiet day. Its basket and checkout counts are
 * written {@code NULL}, and {@link JdbcFunnelStore#upsertFunnelDay} never lets a {@code NULL}
 * replace a count already recorded -- so the repeat of a close over a day the sweeper has since
 * emptied leaves the good number where it was.
 *
 * <p>No cart source wired (a close in a test with no ordering module, say) is treated as "past
 * retention", not as "no carts": an unknown is not a zero.
 */
@Service
public class FunnelCaptureService {

    private final JdbcFunnelStore store;
    private final @Nullable CartFunnelSource carts;
    private final @Nullable CustomerRegistrationSource registrations;
    private final Clock clock;

    @Autowired
    public FunnelCaptureService(
            JdbcFunnelStore store,
            @Nullable CartFunnelSource carts,
            @Nullable CustomerRegistrationSource registrations,
            Clock clock) {
        this.store = store;
        this.carts = carts;
        this.registrations = registrations;
        this.clock = clock;
    }

    /**
     * Derives and stores one business day's funnel and registration rows. Idempotent on the primary
     * keys, so the close can run it again. Runs in the caller's transaction.
     *
     * @param orders the day's order facts, already derived by the close, so the "ordered" stage is
     *               the same population every other figure on the day is
     */
    public Result capture(UUID tenantId, LocalDate businessDate, BusinessDayBoundary boundary, List<OrderFact> orders) {
        Map<Key, Integer> ordered = new LinkedHashMap<>();
        for (OrderFact order : orders) {
            ordered.merge(new Key(order.brandId(), order.channelCode()), 1, Integer::sum);
        }
        return write(tenantId, businessDate, boundary, ordered);
    }

    /**
     * The same capture for a day closed before this existed, with "ordered" read from the stored
     * order facts instead of a derivation in hand (ADR 0167, "Close job and backfill").
     */
    public Result captureFromStoredFacts(UUID tenantId, LocalDate businessDate, BusinessDayBoundary boundary) {
        Map<Key, Integer> ordered = new LinkedHashMap<>();
        for (JdbcFunnelStore.OrderedCount count : store.readOrderedCounts(tenantId, businessDate)) {
            ordered.put(new Key(count.brandId(), count.channelCode()), count.ordered());
        }
        return write(tenantId, businessDate, boundary, ordered);
    }

    private Result write(
            UUID tenantId, LocalDate businessDate, BusinessDayBoundary boundary, Map<Key, Integer> ordered) {
        Instant from = boundary.startOf(businessDate);
        Instant to = boundary.endOf(businessDate);
        Instant now = clock.instant();

        int registrationRows = 0;
        if (registrations != null) {
            for (RegistrationCount count : registrations.registeredBetween(tenantId, from, to)) {
                store.upsertRegistrationDay(
                        tenantId,
                        count.brandId(),
                        businessDate,
                        count.origin(),
                        count.registered(),
                        boundary.version(),
                        now);
                registrationRows++;
            }
        }

        CartFunnelSource cartSource = carts;
        boolean cartsStillHeld = cartSource != null && !from.isBefore(cartSource.retainedFrom(tenantId));
        Map<Key, CartDayCount> cartCounts = new LinkedHashMap<>();
        if (cartSource != null && cartsStillHeld) {
            for (CartDayCount count : cartSource.countBetween(tenantId, from, to)) {
                cartCounts.put(new Key(count.brandId(), count.channelCode()), count);
            }
        }

        Set<Key> keys = new LinkedHashSet<>(ordered.keySet());
        keys.addAll(cartCounts.keySet());

        for (Key key : keys) {
            CartDayCount cart = cartCounts.get(key);
            Integer baskets = !cartsStillHeld ? null : cart == null ? 0 : cart.basketsStarted();
            Integer checkouts = !cartsStillHeld ? null : cart == null ? 0 : cart.checkoutsStarted();
            store.upsertFunnelDay(
                    tenantId,
                    key.brandId(),
                    businessDate,
                    key.channelCode(),
                    baskets,
                    checkouts,
                    ordered.getOrDefault(key, 0),
                    boundary.version(),
                    now);
        }
        return new Result(keys.size(), registrationRows, cartsStillHeld);
    }

    /**
     * @param funnelRows       rows written or confirmed in {@code fact_funnel_day}
     * @param registrationRows rows written or confirmed in {@code fact_registration_day}
     * @param cartsRecorded    false when the day was past the retention cliff and its basket and
     *                         checkout counts were left "not recorded"
     */
    public record Result(int funnelRows, int registrationRows, boolean cartsRecorded) {}

    private record Key(UUID brandId, String channelCode) {}
}
