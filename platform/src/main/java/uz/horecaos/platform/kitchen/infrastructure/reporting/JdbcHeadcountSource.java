package uz.horecaos.platform.kitchen.infrastructure.reporting;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.kitchen.application.port.HeadcountSource;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.CapacityWindow;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.LoadCell;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Station;
import uz.horecaos.platform.kitchen.domain.PublicHolidays;
import uz.horecaos.platform.kitchen.domain.StationRole;

/**
 * {@link HeadcountSource} as plain reads over {@code reporting.fact_station_load}, {@code
 * reporting.business_day_policies} and {@code tenant.*} (ADR 0163), the idiom the kitchen's other sources
 * use: no write, no import of another module's class.
 *
 * <p>Every statement carries the tenant. The business-day policy falls back to midnight in the tenant's
 * declared timezone exactly as {@code reporting}'s {@code BusinessDayService} does; a tenant with neither is a
 * provisioning bug and answers a refusal rather than UTC.
 */
@Component
public class JdbcHeadcountSource implements HeadcountSource {

    private final JdbcClient jdbc;

    public JdbcHeadcountSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean locationExists(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM tenant.locations
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("locationId", locationId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public BusinessDay businessDay(UUID tenantId) {
        Optional<BusinessDay> stored = jdbc.sql("""
                        SELECT timezone, business_day_start FROM reporting.business_day_policies
                        WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((ResultSet row, int number) -> new BusinessDay(
                        ZoneId.of(row.getString("timezone")), row.getObject("business_day_start", LocalTime.class)))
                .optional();
        if (stored.isPresent()) {
            return stored.get();
        }
        String zone = jdbc.sql("SELECT default_timezone FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("The tenant has no declared timezone"));
        return new BusinessDay(ZoneId.of(zone), LocalTime.MIDNIGHT);
    }

    @Override
    public List<LocalDate> candidateDates(
            UUID tenantId, UUID locationId, int weekday, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                        SELECT business_date
                        FROM reporting.fact_station_load
                        WHERE tenant_id = :tenantId AND location_id = :locationId
                          AND business_date BETWEEN :from AND :to
                          AND extract(isodow FROM business_date)::int = :weekday
                        GROUP BY business_date
                        HAVING sum(tickets) > 0
                        ORDER BY business_date DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", from)
                .param("to", to)
                .param("weekday", weekday)
                .param("limit", limit)
                .query(LocalDate.class)
                .list();
    }

    @Override
    public Loads loads(UUID tenantId, UUID locationId, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return new Loads(List.of(), 0);
        }
        List<LoadRow> rows = jdbc.sql("""
                        SELECT business_date, station_id, operating_hour, portions, metric_calculation_version
                        FROM reporting.fact_station_load
                        WHERE tenant_id = :tenantId AND location_id = :locationId AND business_date IN (:dates)
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("dates", dates)
                .query((ResultSet row, int number) -> new LoadRow(
                        new LoadCell(
                                row.getObject("business_date", LocalDate.class),
                                row.getObject("station_id", UUID.class),
                                row.getInt("operating_hour"),
                                row.getBigDecimal("portions")),
                        row.getInt("metric_calculation_version")))
                .list();
        int version = rows.stream().mapToInt(LoadRow::version).max().orElse(0);
        return new Loads(rows.stream().map(LoadRow::cell).toList(), version);
    }

    @Override
    public List<Station> stations(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                        SELECT id, code, role FROM kitchen.stations
                        WHERE tenant_id = :tenantId AND location_id = :locationId AND status = 'ACTIVE'
                        ORDER BY sort_order, code
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((ResultSet row, int number) -> new Station(
                        row.getObject("id", UUID.class),
                        row.getString("code"),
                        StationRole.require(row.getString("role"))))
                .list();
    }

    @Override
    public List<CapacityWindow> windows(UUID tenantId, UUID locationId) {
        // Minutes from local midnight: a window may end at 24:00, which LocalTime cannot hold.
        return jdbc.sql("""
                        SELECT station_id, weekday,
                               (extract(epoch FROM window_start) / 60)::int AS start_minute,
                               (extract(epoch FROM window_end) / 60)::int AS end_minute,
                               portions_per_hour
                        FROM kitchen.station_capacity
                        WHERE tenant_id = :tenantId AND location_id = :locationId
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((ResultSet row, int number) -> new CapacityWindow(
                        row.getObject("station_id", UUID.class),
                        row.getInt("weekday"),
                        row.getInt("start_minute"),
                        row.getInt("end_minute"),
                        row.getInt("portions_per_hour")))
                .list();
    }

    @Override
    public PublicHolidays holidays(UUID tenantId) {
        Optional<String> country = jdbc.sql("SELECT country_code FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional();
        if (country.isEmpty()) {
            return PublicHolidays.NONE;
        }
        Set<MonthDay> recurring = new HashSet<>();
        Set<LocalDate> dated = new HashSet<>();
        List<HolidayRow> rules = jdbc.sql(
                        "SELECT month, day, holiday_date FROM tenant.public_holidays WHERE country_code = :country")
                .param("country", country.get())
                .query((ResultSet row, int number) -> new HolidayRow(
                        row.getObject("month", Integer.class),
                        row.getObject("day", Integer.class),
                        row.getObject("holiday_date", LocalDate.class)))
                .list();
        for (HolidayRow rule : rules) {
            LocalDate date = rule.date();
            Integer month = rule.month();
            Integer day = rule.day();
            if (date != null) {
                dated.add(date);
            } else if (month != null && day != null) {
                recurring.add(MonthDay.of(month, day));
            }
        }
        return new PublicHolidays(recurring, dated);
    }

    private record LoadRow(LoadCell cell, int version) {}

    private record HolidayRow(
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable LocalDate date) {}
}
