package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OperatorTodayLeaderboardRow;

/**
 * Gap map row 0.1d: who is taking and confirming orders on the live board right
 * now -- the shift supervisor's band, a counting read of {@code ordering.orders}
 * like {@link OperatorTodayCountsService}'s single card and not a report.
 *
 * <p>Like that service it leaves the name to its caller. {@code ordering} holds
 * a Keycloak subject on an order and nothing personal about the person
 * (ADR 0029), and the one structural answer to "who is this" is the tenant's own
 * staff directory (ADR 0139), which the controller composes after the counts are
 * read. A leaderboard therefore cannot disagree with the audit log about who
 * someone is, and a subject the directory cannot name renders by its typed
 * fallback instead of failing the board.
 *
 * <p>The window is the tenant's own business day (ADR 0043) containing this
 * call's instant, not the UTC calendar date.
 */
@Service
public class OperatorTodayLeaderboardService {

    private final JdbcOrderStore orders;
    private final BusinessDayWindows businessDays;
    private final Clock clock;

    public OperatorTodayLeaderboardService(JdbcOrderStore orders, BusinessDayWindows businessDays, Clock clock) {
        this.orders = orders;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /**
     * @param locationId null for the whole brand, otherwise one branch
     */
    @Transactional(readOnly = true)
    public Leaderboard today(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
        BusinessDayWindows.Window window = businessDays.businessDayContaining(tenantId, clock.instant());
        List<OperatorTodayLeaderboardRow> rows =
                orders.operatorTodayLeaderboard(tenantId, brandId, locationId, window.from(), window.to());
        return new Leaderboard(rows, window);
    }

    public record Leaderboard(List<OperatorTodayLeaderboardRow> rows, BusinessDayWindows.Window window) {}
}
