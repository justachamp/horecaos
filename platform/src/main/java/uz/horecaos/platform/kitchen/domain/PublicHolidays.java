package uz.horecaos.platform.kitchen.domain;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/**
 * Which dates the tenant's market flags as public holidays ({@code tenant.public_holidays}, ADR 0090): a
 * month and day that recur every year, or single dated occurrences. The headcount plan's sample uses it to
 * apply {@link HeadcountHolidayMode}; {@code reporting}'s own calendar does the same for the branch forecast,
 * and {@code kitchen} imports nothing from it.
 */
public record PublicHolidays(Set<MonthDay> recurring, Set<LocalDate> dated) {

    public static final PublicHolidays NONE = new PublicHolidays(Set.of(), Set.of());

    public PublicHolidays {
        recurring = Set.copyOf(recurring);
        dated = Set.copyOf(dated);
    }

    public boolean isHoliday(LocalDate date) {
        return dated.contains(date) || recurring.contains(MonthDay.from(date));
    }
}
