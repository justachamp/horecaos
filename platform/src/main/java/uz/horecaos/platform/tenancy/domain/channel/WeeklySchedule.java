package uz.horecaos.platform.tenancy.domain.channel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A named timetable, evaluated in local wall-clock time (ADR 0036).
 *
 * <p>Pure: no clock, no zone, no database. The caller converts an instant to the
 * location's local time through {@code tenant.locations.timezone} and asks this
 * object a question about a {@link LocalDateTime}. Keeping the conversion outside
 * is what lets every awkward case — an 18:00–02:00 window at 01:00, a dated
 * exception, the first opening thirteen days away — be tested without a database
 * and without waiting for the clock.
 */
public record WeeklySchedule(
        List<Rule> rules, Map<LocalDate, DatedException> exceptions, boolean acceptsScheduledOrders) {

    /**
     * How far forward {@link #nextOpeningAtOrAfter} will look.
     *
     * <p>A fortnight covers every weekly timetable and every plausible holiday
     * closure. Beyond it the honest answer is "we do not know when this reopens",
     * which is what an absent {@code next_available_at} means — better than a
     * confident date derived from a schedule that has one rule left over from a
     * seasonal menu.
     */
    private static final int SCAN_DAYS = 14;

    public WeeklySchedule {
        rules = List.copyOf(Objects.requireNonNull(rules, "Schedule rules are required"));
        exceptions = Map.copyOf(Objects.requireNonNull(exceptions, "Schedule exceptions are required"));
    }

    /** One weekly window. */
    public record Rule(int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {

        public Rule {
            if (dayOfWeek < 1 || dayOfWeek > 7) {
                throw new IllegalArgumentException(
                        "ISO-8601 day of week is 1 (Monday) to 7 (Sunday), got " + dayOfWeek);
            }
            Objects.requireNonNull(opensAt, "A rule needs an opening time");
            Objects.requireNonNull(closesAt, "A rule needs a closing time");
        }
    }

    /** A dated override of one calendar date. */
    public record DatedException(boolean closedAllDay, @Nullable LocalTime opensAt, @Nullable LocalTime closesAt) {

        public static DatedException closed() {
            return new DatedException(true, null, null);
        }

        public static DatedException open(LocalTime opensAt, LocalTime closesAt) {
            return new DatedException(
                    false,
                    Objects.requireNonNull(opensAt, "An open exception needs an opening time"),
                    Objects.requireNonNull(closesAt, "An open exception needs a closing time"));
        }
    }

    /** A concrete window on the calendar. Half-open: closing time is not open. */
    public record Window(LocalDateTime opensAt, LocalDateTime closesAt) {

        boolean contains(LocalDateTime moment) {
            return !moment.isBefore(opensAt) && moment.isBefore(closesAt);
        }
    }

    /** Whether a dated exception closes this date outright (resolver rule 5). */
    public boolean closedByExceptionOn(LocalDate date) {
        DatedException exception = exceptions.get(date);
        return exception != null && exception.closedAllDay();
    }

    /** Whether any window covers this moment (resolver rule 6). */
    public boolean isOpenAt(LocalDateTime moment) {
        return windowsAround(moment.toLocalDate()).stream().anyMatch(window -> window.contains(moment));
    }

    /**
     * The first moment at or after {@code moment} at which a window opens.
     *
     * <p>Empty when nothing opens within {@link #SCAN_DAYS}, which is how a
     * schedule with no rules at all — a timetable somebody created and never
     * filled in — reports "no idea" instead of a fabricated instant.
     */
    public Optional<LocalDateTime> nextOpeningAtOrAfter(LocalDateTime moment) {
        LocalDate from = moment.toLocalDate();
        return java.util.stream.IntStream.rangeClosed(0, SCAN_DAYS)
                .mapToObj(from::plusDays)
                .flatMap(date -> windowsOn(date).stream())
                .map(Window::opensAt)
                .filter(opening -> !opening.isBefore(moment))
                .min(LocalDateTime::compareTo);
    }

    /**
     * Windows that could cover {@code date}, including one that started the day
     * before.
     *
     * <p>This is the whole point of the after-midnight rule. A venue open
     * 18:00–02:00 stored as a naive range compares as {@code 18:00 <= t < 02:00},
     * which is empty, and the branch reads as shut all evening.
     */
    private List<Window> windowsAround(LocalDate date) {
        List<Window> windows = new ArrayList<>(windowsOn(date.minusDays(1)));
        windows.addAll(windowsOn(date));
        return windows;
    }

    /**
     * The windows starting on one calendar date.
     *
     * <p>A dated exception <em>replaces</em> the weekly rules for its date rather
     * than adding to them. Merging the two would mean a "we close early on the
     * 31st" exception left the normal evening window in place, which is the
     * opposite of what the operator asked for.
     */
    private List<Window> windowsOn(LocalDate date) {
        DatedException exception = exceptions.get(date);
        if (exception != null) {
            // The DatedException invariant: opensAt/closesAt are present exactly
            // when closedAllDay is false — enforced by closed() and open() above,
            // so a non-closedAllDay exception always carries both times.
            return exception.closedAllDay()
                    ? List.of()
                    : List.of(window(
                            date,
                            Objects.requireNonNull(exception.opensAt()),
                            Objects.requireNonNull(exception.closesAt())));
        }
        int day = date.getDayOfWeek().getValue();
        return rules.stream()
                .filter(rule -> rule.dayOfWeek() == day)
                .map(rule -> window(date, rule.opensAt(), rule.closesAt()))
                .toList();
    }

    private static Window window(LocalDate date, LocalTime opensAt, LocalTime closesAt) {
        LocalDateTime opening = date.atTime(opensAt);
        // closesAt <= opensAt means the window ends on the following day. Equal
        // times are therefore a full 24 hours rather than an instantaneous window
        // nobody could order in.
        LocalDateTime closing = closesAt.isAfter(opensAt)
                ? date.atTime(closesAt)
                : date.plusDays(1).atTime(closesAt);
        return new Window(opening, closing);
    }
}
