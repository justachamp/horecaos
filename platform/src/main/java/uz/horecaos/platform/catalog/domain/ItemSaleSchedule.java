package uz.horecaos.platform.catalog.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Row 4.2g: a variant's own weekly sale windows, evaluated in local wall-clock
 * time. Pure -- no clock, no zone, no database -- exactly the same discipline
 * {@code tenancy.domain.channel.WeeklySchedule} follows and for the same
 * reason: the caller converts an instant to the location's local time through
 * {@code tenant.locations.timezone} and asks this object a question about a
 * {@link LocalDateTime}, so every awkward case (an 18:00-06:00 breakfast
 * window read at 05:00, no windows at all) is testable without a database.
 *
 * <p>Catalog does not import {@code tenancy}'s domain package -- module
 * boundaries expose only {@code api}, not {@code domain} -- so this is a
 * second, narrower copy rather than a reuse: weekly windows only, no dated
 * exceptions. {@code tenant.service_schedule_exceptions} already answers "is
 * the branch itself closed today" for every item at once; an item does not
 * need its own calendar on top of that.
 *
 * <p>An empty window list is unrestricted -- always on sale -- which is the
 * default for every variant that has never had a window added, and is exactly
 * today's unchanged behaviour.
 */
public record ItemSaleSchedule(List<Window> windows) {

    public ItemSaleSchedule {
        windows = List.copyOf(Objects.requireNonNull(windows, "windows is required"));
    }

    /** One weekly window, ISO-8601 numbered: 1 = Monday through 7 = Sunday. */
    public record Window(int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {

        public Window {
            if (dayOfWeek < 1 || dayOfWeek > 7) {
                throw new IllegalArgumentException(
                        "ISO-8601 day of week is 1 (Monday) to 7 (Sunday), got " + dayOfWeek);
            }
            Objects.requireNonNull(opensAt, "A window needs an opening time");
            Objects.requireNonNull(closesAt, "A window needs a closing time");
        }
    }

    /**
     * Whether the variant is on sale at this local moment.
     *
     * <p>An empty schedule is always on sale -- see the class doc. Otherwise the
     * moment must fall inside at least one window, including one that opened
     * the day before and crosses midnight (a 22:00-02:00 late window read at
     * 01:00), the same after-midnight handling {@code WeeklySchedule} documents
     * at length for exactly the same reason: a naive same-day range compares
     * {@code 22:00 <= t < 02:00} as empty.
     */
    public boolean isOnSaleAt(LocalDateTime moment) {
        if (windows.isEmpty()) {
            return true;
        }
        LocalDate date = moment.toLocalDate();
        return windowsStartingOn(date.minusDays(1)).stream().anyMatch(w -> w.contains(moment))
                || windowsStartingOn(date).stream().anyMatch(w -> w.contains(moment));
    }

    private List<ConcreteWindow> windowsStartingOn(LocalDate date) {
        int day = date.getDayOfWeek().getValue();
        return windows.stream()
                .filter(w -> w.dayOfWeek() == day)
                .map(w -> concreteWindow(date, w.opensAt(), w.closesAt()))
                .toList();
    }

    private static ConcreteWindow concreteWindow(LocalDate date, LocalTime opensAt, LocalTime closesAt) {
        LocalDateTime opening = date.atTime(opensAt);
        // closesAt <= opensAt means the window ends the following day -- a
        // breakfast window open 06:00-11:00 stays a same-day range, while a
        // late window open 22:00-02:00 is read correctly rather than as empty.
        LocalDateTime closing = closesAt.isAfter(opensAt)
                ? date.atTime(closesAt)
                : date.plusDays(1).atTime(closesAt);
        return new ConcreteWindow(opening, closing);
    }

    /** Half-open: the closing instant itself is not on sale. */
    private record ConcreteWindow(LocalDateTime opensAt, LocalDateTime closesAt) {
        boolean contains(LocalDateTime moment) {
            return !moment.isBefore(opensAt) && moment.isBefore(closesAt);
        }
    }
}
