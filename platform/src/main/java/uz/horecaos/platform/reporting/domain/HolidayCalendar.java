package uz.horecaos.platform.reporting.domain;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 7.8b: which of a country's {@code tenant.public_holidays} rows a given date
 * matches — a fixed date recurring every year ({@code month}/{@code day}), or
 * one dated occurrence for a holiday that moves and is entered per year (see
 * that table's own doc, V0203/ADR 0090).
 *
 * <p>Built once per read from every rule for the tenant's country and reused
 * across every candidate date, rather than a query per date: the candidate
 * pool this class checks against is at most a few dozen rows (see {@code
 * JdbcReportingStore#readDemandHistory}'s own overfetch), and Uzbekistan's own
 * seed list (V0203) is seven rows — checking membership in two small
 * in-memory sets is simpler to get right than a join that has to handle both
 * shapes in one predicate.
 */
public final class HolidayCalendar {

    /** No country recorded, or the country has no rows — never a reason to fail a read, just nothing is ever flagged. */
    public static final HolidayCalendar EMPTY = new HolidayCalendar(Set.of(), Set.of());

    private final Set<MonthDay> recurring;
    private final Set<LocalDate> dated;

    private HolidayCalendar(Set<MonthDay> recurring, Set<LocalDate> dated) {
        this.recurring = recurring;
        this.dated = dated;
    }

    public static HolidayCalendar of(List<Rule> rules) {
        Objects.requireNonNull(rules, "A calendar needs its rule list, empty or not");
        if (rules.isEmpty()) {
            return EMPTY;
        }
        Set<MonthDay> recurring = new java.util.HashSet<>();
        Set<LocalDate> dated = new java.util.HashSet<>();
        for (Rule rule : rules) {
            if (rule.date() != null) {
                dated.add(rule.date());
            } else {
                Objects.requireNonNull(rule.month(), "A recurring rule needs a month");
                Objects.requireNonNull(rule.day(), "A recurring rule needs a day");
                // February 29 recurring rules are not seeded today (V0203) and
                // MonthDay itself refuses day 29 outside a leap year lookup, so
                // this cannot throw for the data this class is built from.
                recurring.add(MonthDay.of(rule.month(), rule.day()));
            }
        }
        return new HolidayCalendar(Set.copyOf(recurring), Set.copyOf(dated));
    }

    /** True when {@code date} matches a fixed month/day every year, or is itself one of the dated occurrences. */
    public boolean isHoliday(LocalDate date) {
        return dated.contains(date) || recurring.contains(MonthDay.from(date));
    }

    /** One {@code tenant.public_holidays} row: either a recurring month/day, or one dated occurrence — never both, matching that table's own {@code ck_public_holiday_shape}. */
    public record Rule(
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable LocalDate date) {

        public Rule {
            boolean recurring = month != null && day != null && date == null;
            boolean isDated = month == null && day == null && date != null;
            if (recurring == isDated) {
                throw new IllegalArgumentException(
                        "A holiday rule is either a recurring month/day or one dated occurrence, never both or neither");
            }
        }
    }
}
