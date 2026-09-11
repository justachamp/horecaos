package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.LocationCountsRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.MixSliceRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderCountsRow;

/**
 * What the Home live board (IA 0.1) reads, in as few round trips as the screen
 * can be built from.
 *
 * <p>Its own service rather than three more methods on {@link
 * OrderQueryService}, because the board asks a different question from the order
 * queue: the queue wants one location's orders, the board wants a brand's
 * counters, its two mixes and its branch leaderboard, all cut to the same period
 * and all consistent with each other at the instant they were read. Holding
 * that together is this class's whole job — in particular, it resolves the
 * period <em>once</em> and passes the same window to every read below it, so the
 * counters and the branch table can never be cut at two different boundaries.
 *
 * <p><strong>One read per tick.</strong> The console used to build this screen
 * from a counts call, a 200-order page for the mixes, and one further counts
 * call per branch — eleven requests for a ten-branch tenant, every ten seconds.
 * {@link #forBrand} answers all of it at once.
 */
@Service
public class LiveBoardQueryService {

    private final JdbcOrderStore orders;
    private final BusinessDayWindows businessDays;
    private final Clock clock;

    public LiveBoardQueryService(JdbcOrderStore orders, BusinessDayWindows businessDays, Clock clock) {
        this.orders = orders;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /** One location's counters and mixes, cut to {@code period}. */
    @Transactional(readOnly = true)
    public LocationLiveBoard forLocation(UUID tenantId, UUID brandId, UUID locationId, OrderCountsPeriod period) {
        Window window = windowFor(tenantId, period);
        OrderCountsRow counts = orders.counts(tenantId, brandId, locationId, window.from(), window.to());
        List<MixSliceRow> mix = orders.activeMix(tenantId, brandId, locationId);
        return new LocationLiveBoard(window, counts, mix);
    }

    /**
     * The whole brand: its totals, its branch leaderboard and its two mixes.
     *
     * <p>The totals are read as their own aggregate rather than summed from the
     * per-location rows. Summing would agree today and stop agreeing the moment
     * a location is archived or a brand-level order exists that belongs to no
     * branch, and a board whose headline number is derived from its own table is
     * a board that cannot be checked against anything.
     */
    @Transactional(readOnly = true)
    public BrandLiveBoard forBrand(UUID tenantId, UUID brandId, OrderCountsPeriod period) {
        Window window = windowFor(tenantId, period);
        OrderCountsRow totals = orders.counts(tenantId, brandId, null, window.from(), window.to());
        List<LocationCountsRow> locations = orders.countsByLocation(tenantId, brandId, window.from(), window.to());
        List<MixSliceRow> mix = orders.activeMix(tenantId, brandId, null);
        return new BrandLiveBoard(window, totals, locations, mix);
    }

    private Window windowFor(UUID tenantId, OrderCountsPeriod period) {
        return switch (period) {
            case ALL_TIME -> new Window(null, null);
            case BUSINESS_DAY -> {
                BusinessDayWindows.Window resolved = businessDays.businessDayContaining(tenantId, clock.instant());
                yield new Window(resolved.from(), resolved.to());
            }
        };
    }

    /**
     * The resolved period, nulls and all.
     *
     * <p>Not {@link BusinessDayWindows.Window}, which refuses nulls by
     * construction: "no bound at all" is a real answer here and needs a type that
     * can hold it, whereas a business day that had started after it ended would
     * be a fault.
     */
    public record Window(@Nullable Instant from, @Nullable Instant to) {}

    /** {@link #forLocation}'s answer. */
    public record LocationLiveBoard(Window window, OrderCountsRow counts, List<MixSliceRow> mix) {}

    /** {@link #forBrand}'s answer. */
    public record BrandLiveBoard(
            Window window, OrderCountsRow totals, List<LocationCountsRow> locations, List<MixSliceRow> mix) {}
}
