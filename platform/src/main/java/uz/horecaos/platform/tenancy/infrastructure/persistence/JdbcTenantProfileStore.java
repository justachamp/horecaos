package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.domain.BusinessType;
import uz.horecaos.platform.tenancy.domain.TenantStatus;

/**
 * A tenant's country and business type, and the public holidays of the
 * countries the platform serves (ADR 0090).
 */
@Repository
public class JdbcTenantProfileStore {

    private final JdbcClient jdbc;

    public JdbcTenantProfileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every tenant with where it trades and what it is, by name. */
    public List<TenantProfile> all() {
        return jdbc.sql(SELECT + " ORDER BY display_name")
                .query(JdbcTenantProfileStore::profile)
                .list();
    }

    public Optional<TenantProfile> find(UUID tenantId) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", tenantId)
                .query(JdbcTenantProfileStore::profile)
                .optional();
    }

    /** Records a business type; false when it was already that. */
    public boolean setBusinessType(UUID tenantId, BusinessType type) {
        return jdbc.sql("""
                        UPDATE tenant.tenants SET business_type = :type, version = version + 1
                         WHERE id = :id AND business_type <> :type
                        """).param("id", tenantId).param("type", type.name()).update() == 1;
    }

    /** Records a country; false when it was already that. */
    public boolean setCountry(UUID tenantId, String countryCode) {
        return jdbc.sql("""
                        UPDATE tenant.tenants SET country_code = :country, version = version + 1
                         WHERE id = :id AND country_code <> :country
                        """).param("id", tenantId).param("country", countryCode).update() == 1;
    }

    // --------------------------------------------------------- holidays

    public List<PublicHoliday> holidays() {
        return jdbc.sql("""
                        SELECT id, country_code, name, month, day, holiday_date
                          FROM tenant.public_holidays
                         ORDER BY country_code, coalesce(holiday_date, make_date(2000, month, day)), name
                        """).query(JdbcTenantProfileStore::holiday).list();
    }

    public void insertHoliday(PublicHoliday holiday, String createdBy) {
        jdbc.sql("""
                        INSERT INTO tenant.public_holidays (id, country_code, name, month, day, holiday_date, created_by)
                        VALUES (:id, :country, :name, :month, :day, :date, :createdBy)
                        """)
                .param("id", holiday.id())
                .param("country", holiday.countryCode())
                .param("name", holiday.name())
                .param("month", holiday.month())
                .param("day", holiday.day())
                .param("date", holiday.date())
                .param("createdBy", createdBy)
                .update();
    }

    public Optional<PublicHoliday> findHoliday(UUID id) {
        return jdbc.sql(
                        "SELECT id, country_code, name, month, day, holiday_date FROM tenant.public_holidays WHERE id = :id")
                .param("id", id)
                .query(JdbcTenantProfileStore::holiday)
                .optional();
    }

    public boolean deleteHoliday(UUID id) {
        return jdbc.sql("DELETE FROM tenant.public_holidays WHERE id = :id")
                        .param("id", id)
                        .update()
                == 1;
    }

    /** One tenant's place and kind. */
    public record TenantProfile(
            UUID tenantId,
            String slug,
            String displayName,
            TenantStatus status,
            String countryCode,
            BusinessType businessType,
            String defaultCurrency,
            String defaultTimezone) {}

    /**
     * One public holiday: a month and day every year, or one date.
     *
     * @param month null for a dated holiday
     * @param day   null for a dated holiday
     * @param date  null for a recurring one
     */
    public record PublicHoliday(
            UUID id,
            String countryCode,
            String name,
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable LocalDate date) {}

    private static final String SELECT = """
            SELECT id, slug, display_name, status, country_code, business_type, default_currency,
                   default_timezone
              FROM tenant.tenants
            """;

    private static TenantProfile profile(ResultSet row, int number) throws SQLException {
        return new TenantProfile(
                row.getObject("id", UUID.class),
                row.getString("slug"),
                row.getString("display_name"),
                TenantStatus.valueOf(row.getString("status")),
                row.getString("country_code"),
                BusinessType.valueOf(row.getString("business_type")),
                row.getString("default_currency"),
                row.getString("default_timezone"));
    }

    private static PublicHoliday holiday(ResultSet row, int number) throws SQLException {
        return new PublicHoliday(
                row.getObject("id", UUID.class),
                row.getString("country_code"),
                row.getString("name"),
                row.getObject("month", Integer.class),
                row.getObject("day", Integer.class),
                row.getObject("holiday_date", LocalDate.class));
    }
}
