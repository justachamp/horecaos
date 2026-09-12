package uz.horecaos.platform.reporting.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 10.10b: a tenant's own weekend declaration and its own holiday list —
 * {@code tenant.business_calendars} and {@code tenant.business_calendar_holidays}
 * (V0258), additional to the platform's per-country {@code tenant.public_holidays}
 * (ADR 0090) and separate from {@code reporting.business_day_policies}'s boundary
 * (ADR 0043, read and written through {@link
 * uz.horecaos.platform.reporting.application.BusinessDayService} instead).
 *
 * <p>Reporting reading {@code tenant.*} directly rather than through a module
 * API is the established shape here, not a new crossing: {@link
 * JdbcReportingStore#findTenantTimezone} already does exactly this for the
 * same reason — the business-day boundary and now the calendar around it are
 * ADR 0043's subject even though the row lives in the tenant schema.
 */
@Repository
public class JdbcBusinessCalendarStore {

    private final JdbcClient jdbc;

    public JdbcBusinessCalendarStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the tenant has never declared a weekend — trades every day. */
    public List<Integer> weekendDays(UUID tenantId) {
        return jdbc.sql("SELECT weekend_days FROM tenant.business_calendars WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query((ResultSet row, int number) -> toIntList(row.getArray("weekend_days")))
                .optional()
                .orElseGet(List::of);
    }

    public void setWeekendDays(UUID tenantId, List<Integer> days) {
        Integer[] boxed = days.toArray(new Integer[0]);
        jdbc.sql("""
                        INSERT INTO tenant.business_calendars (tenant_id, weekend_days, updated_at)
                        VALUES (:tenantId, :days::smallint[], now())
                        ON CONFLICT (tenant_id) DO UPDATE
                        SET weekend_days = EXCLUDED.weekend_days, updated_at = now()
                        """).param("tenantId", tenantId).param("days", boxed).update();
    }

    public List<TenantHoliday> holidays(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, name, month, day, holiday_date
                          FROM tenant.business_calendar_holidays
                         WHERE tenant_id = :tenantId
                         ORDER BY coalesce(holiday_date, make_date(2000, month, day)), name
                        """)
                .param("tenantId", tenantId)
                .query(JdbcBusinessCalendarStore::holiday)
                .list();
    }

    public Optional<TenantHoliday> findHoliday(UUID tenantId, UUID holidayId) {
        return jdbc.sql("""
                        SELECT id, name, month, day, holiday_date
                          FROM tenant.business_calendar_holidays
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", holidayId)
                .query(JdbcBusinessCalendarStore::holiday)
                .optional();
    }

    public void insertHoliday(UUID tenantId, TenantHoliday holiday, String createdBy) {
        jdbc.sql("""
                        INSERT INTO tenant.business_calendar_holidays
                            (id, tenant_id, name, month, day, holiday_date, created_by)
                        VALUES (:id, :tenantId, :name, :month, :day, :date, :createdBy)
                        """)
                .param("id", holiday.id())
                .param("tenantId", tenantId)
                .param("name", holiday.name())
                .param("month", holiday.month())
                .param("day", holiday.day())
                .param("date", holiday.date())
                .param("createdBy", createdBy)
                .update();
    }

    public boolean deleteHoliday(UUID tenantId, UUID holidayId) {
        return jdbc.sql("DELETE FROM tenant.business_calendar_holidays WHERE tenant_id = :tenantId AND id = :id")
                        .param("tenantId", tenantId)
                        .param("id", holidayId)
                        .update()
                == 1;
    }

    private static List<Integer> toIntList(@Nullable Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (Object value : (Object[]) sqlArray.getArray()) {
            values.add(((Number) value).intValue());
        }
        return List.copyOf(values);
    }

    private static TenantHoliday holiday(ResultSet row, int number) throws SQLException {
        return new TenantHoliday(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getObject("month", Integer.class),
                row.getObject("day", Integer.class),
                row.getObject("holiday_date", LocalDate.class));
    }

    /**
     * One tenant-declared closure: a month and day every year, or one date —
     * the same recurring-or-dated shape {@code tenant.public_holidays} uses.
     *
     * @param month null for a dated holiday
     * @param day   null for a dated holiday
     * @param date  null for a recurring one
     */
    public record TenantHoliday(
            UUID id,
            String name,
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable LocalDate date) {}
}
