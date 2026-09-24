package uz.horecaos.platform.ordering.infrastructure.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.application.CartSaleWindowRules;

/**
 * Reads row 4.2g's per-item sale schedule live from {@code
 * catalog.item_sale_windows} (ADR 0019). See {@link CartSaleWindowRules}'s own
 * doc for why this reads live rather than the publication, and why the
 * weekly-window matching below is a second copy of {@code
 * catalog.domain.ItemSaleSchedule#isOnSaleAt} rather than a reuse of it — a
 * catalog-internal type ordering's module boundary does not let this package
 * import, exactly as {@link JdbcCartMenuRules}'s own doc explains for the
 * publication tables it reads.
 */
@Component
public class JdbcCartSaleWindowRules implements CartSaleWindowRules {

    private final JdbcClient jdbc;

    public JdbcCartSaleWindowRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isOnSaleAt(UUID tenantId, UUID locationId, UUID variantId, ZoneId zone, Instant at) {
        List<Window> windows = jdbc.sql("""
                SELECT day_of_week, opens_at, closes_at
                FROM catalog.item_sale_windows
                WHERE tenant_id = :tenantId AND location_id = :locationId AND variant_id = :variantId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .query((row, number) -> new Window(
                        row.getInt("day_of_week"),
                        row.getObject("opens_at", LocalTime.class),
                        row.getObject("closes_at", LocalTime.class)))
                .list();
        // An empty window list is unrestricted -- always on sale -- exactly as
        // ItemSaleSchedule's own class doc states: the absence of a binding is
        // not a restriction, it is the unscoped default.
        if (windows.isEmpty()) {
            return true;
        }
        LocalDateTime moment = LocalDateTime.ofInstant(at, zone);
        LocalDate date = moment.toLocalDate();
        return windowsStartingOn(windows, date.minusDays(1)).stream().anyMatch(w -> w.contains(moment))
                || windowsStartingOn(windows, date).stream().anyMatch(w -> w.contains(moment));
    }

    private static List<ConcreteWindow> windowsStartingOn(List<Window> windows, LocalDate date) {
        int day = date.getDayOfWeek().getValue();
        return windows.stream()
                .filter(w -> w.dayOfWeek() == day)
                .map(w -> concreteWindow(date, w.opensAt(), w.closesAt()))
                .toList();
    }

    private static ConcreteWindow concreteWindow(LocalDate date, LocalTime opensAt, LocalTime closesAt) {
        LocalDateTime opening = date.atTime(opensAt);
        // closesAt <= opensAt means the window ends the following day, exactly
        // as ItemSaleSchedule's own matching does for a late window such as
        // 22:00-02:00.
        LocalDateTime closing = closesAt.isAfter(opensAt)
                ? date.atTime(closesAt)
                : date.plusDays(1).atTime(closesAt);
        return new ConcreteWindow(opening, closing);
    }

    private record Window(int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {}

    /** Half-open: the closing instant itself is not on sale. */
    private record ConcreteWindow(LocalDateTime opensAt, LocalDateTime closesAt) {
        boolean contains(LocalDateTime moment) {
            return !moment.isBefore(opensAt) && moment.isBefore(closesAt);
        }
    }
}
