package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OperatorTodayCountsRow;

/**
 * Staff 9.2d — "a manager cannot see how many orders an operator took today
 * from the person's own card". Unlike {@link MyWorkQueryService}, which is
 * deliberately self-scoped and has no argument for "whose orders" (see its
 * own doc), a manager reading a staff person's card is asking about someone
 * else on purpose, gated by {@code ORDER_READ} at tenant scope rather than
 * the caller's own identity.
 *
 * <p>Names are still out of scope here exactly as {@link
 * uz.horecaos.platform.reporting.application.OperatorAttribution}'s own doc
 * says for the leaderboard: this answers "how many", keyed by the Keycloak
 * subject already on the person's card, and resolving that subject to a
 * display name is the caller's own job (the same {@code StaffDisplayNames}
 * lookup {@code OrderDetailResponse} already uses).
 */
@Service
public class OperatorTodayCountsService {

    private final JdbcOrderStore orders;
    private final BusinessDayWindows businessDays;
    private final Clock clock;

    public OperatorTodayCountsService(JdbcOrderStore orders, BusinessDayWindows businessDays, Clock clock) {
        this.orders = orders;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /**
     * {@code operatorSubject}'s own created/accepted counts, for the tenant's
     * business day (ADR 0043) containing this call's instant.
     *
     * @param operatorSubject the staff person's Keycloak subject, as read off
     *                        their own card — never taken from the caller's
     *                        own token, unlike {@link MyWorkQueryService}
     */
    @Transactional(readOnly = true)
    public OperatorTodayCounts today(UUID tenantId, String operatorSubject) {
        BusinessDayWindows.Window window = businessDays.businessDayContaining(tenantId, clock.instant());
        OperatorTodayCountsRow row = orders.operatorTodayCounts(tenantId, operatorSubject, window.from(), window.to());
        return new OperatorTodayCounts(row.created(), row.accepted(), window);
    }

    /** @param window the business-day window the two counts were cut to */
    public record OperatorTodayCounts(long created, long accepted, BusinessDayWindows.Window window) {}
}
