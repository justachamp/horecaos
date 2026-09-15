package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.MixSliceRow;

/**
 * IA 0.2a — the caller's own "today", read the one way this build can answer
 * it honestly: self-scoped, with no staff directory.
 *
 * <p>Every other actor-facing read the gap map lists (the live-board
 * leaderboard, the staff person record) is blocked on the staff-identity ADR
 * — a name has nowhere to come from. This one is not: an operator asking "how
 * many orders did I take today, by channel" never needs a name, only the
 * subject the console already authenticated them as. That is the whole reason
 * `0.2` can ship a page today while `0.2c`/`0.2d` stay deferred beside it.
 *
 * <p><strong>There is no overload that takes anyone else's subject.</strong>
 * Every caller of {@link #channelMixForCaller} passes the value {@code
 * CurrentActor.get().subject()} produced, and the only way a request could
 * name a different operator is a request parameter the web layer refuses
 * before this class is ever reached (see {@code
 * OperationsOrderController#myWorkChannelMix}) — this class's own API simply
 * has nowhere to put a second, "whose orders" argument. No future capability
 * grant widens this: widening it would mean adding a parameter here, not
 * discovering one already existed.
 */
@Service
public class MyWorkQueryService {

    private final JdbcOrderStore orders;
    private final BusinessDayWindows businessDays;
    private final Clock clock;

    public MyWorkQueryService(JdbcOrderStore orders, BusinessDayWindows businessDays, Clock clock) {
        this.orders = orders;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /**
     * {@code subject}'s own orders, created today (the tenant's own business
     * day, ADR 0043), grouped by sales channel.
     *
     * @param subject the caller's own Keycloak subject — never anyone else's
     */
    @Transactional(readOnly = true)
    public ChannelMix channelMixForCaller(UUID tenantId, UUID locationId, String subject) {
        BusinessDayWindows.Window window = businessDays.businessDayContaining(tenantId, clock.instant());
        List<MixSliceRow> rows = orders.myWorkChannelMix(tenantId, locationId, subject, window.from(), window.to());
        return new ChannelMix(window, rows);
    }

    /** {@link #channelMixForCaller}'s answer: the window it was cut to, and the channel bars. */
    public record ChannelMix(BusinessDayWindows.Window window, List<MixSliceRow> channels) {}
}
