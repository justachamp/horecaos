package uz.horecaos.platform.assistant.domain;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import uz.horecaos.platform.tenancy.api.BranchDirectory.WeeklyWindow;

/**
 * A branch's weekly windows as one line a customer reads: consecutive days with
 * the same hours share a range ("Mon-Fri 09:00-23:00, Sat-Sun 10:00-00:00").
 *
 * <p>Local wall-clock time as the branch's schedule states it; the zone is the
 * branch's own and is deliberately not converted, because "open until 23:00" is
 * a fact about the branch's clock and a customer standing outside it reads the
 * branch's clock.
 */
public final class HoursText {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final Map<String, List<String>> DAYS = Map.of(
            "ru", List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"),
            "uz", List.of("Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya"),
            "en", List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"));

    private HoursText() {}

    public static String format(List<WeeklyWindow> windows, String locale) {
        List<String> names = names(locale);

        Map<Integer, List<String>> byDay = new TreeMap<>();
        windows.stream()
                .sorted(java.util.Comparator.comparingInt(WeeklyWindow::dayOfWeek)
                        .thenComparing(WeeklyWindow::opensAt))
                .forEach(window -> byDay.computeIfAbsent(window.dayOfWeek(), day -> new ArrayList<>())
                        .add(clock(window.opensAt()) + "-" + clock(window.closesAt())));

        List<String> parts = new ArrayList<>();
        int day = 1;
        while (day <= 7) {
            List<String> hours = byDay.get(day);
            if (hours == null) {
                day++;
                continue;
            }
            int last = day;
            while (last < 7 && hours.equals(byDay.get(last + 1))) {
                last++;
            }
            String range = last == day ? names.get(day - 1) : names.get(day - 1) + "-" + names.get(last - 1);
            parts.add(range + " " + String.join(", ", hours));
            day = last + 1;
        }
        return String.join("; ", parts);
    }

    /** One moment in the branch's own zone as "Tue 10:00" -- when it next opens, for a customer reading the branch's clock. */
    public static String dayAndTime(java.time.ZonedDateTime moment, String locale) {
        List<String> names = names(locale);
        return names.get(moment.getDayOfWeek().getValue() - 1) + " "
                + moment.toLocalTime().format(CLOCK);
    }

    /** A wall-clock time as the schedule states it: "09:00". */
    public static String time(java.time.ZonedDateTime moment) {
        return moment.toLocalTime().format(CLOCK);
    }

    private static List<String> names(String locale) {
        return java.util.Objects.requireNonNull(DAYS.getOrDefault(locale, DAYS.get("en")));
    }

    private static String clock(LocalTime time) {
        return time.format(CLOCK);
    }
}
