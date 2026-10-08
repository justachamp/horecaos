package uz.horecaos.platform.kitchen.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The cook-headcount plan (ADR 0163 Decisions 3 and 5): a pure function over the station-load fact rows, the
 * tenant's productivity figures and the stations' own ceilings. No clock, no store, no Spring.
 *
 * <p>A cook count is {@code ceil(expected portions in the hour / the figure)}, per station and per operating
 * hour. The forecast is ADR 0043's: the mean of the sample's same-weekday dates (holiday-weighted under
 * {@link HeadcountHolidayMode#WEIGHT} exactly as the branch forecast does), an 80% band of 1.2816 sample
 * standard deviations of the raw counts either side, refused below three qualifying dates.
 *
 * <p><strong>It is a planning aid and never a roster.</strong> It names no person and is keyed to none; it
 * changes no ceiling and no release time; a station with no figure answers {@link Status#NO_POLICY} and no
 * number, and the pass ({@link StationRole#EXPO}) is never planned.
 */
public final class HeadcountPlanner {

    /** Fewer qualifying dates than this refuse every hour: the same floor {@code demand-history} keeps. */
    public static final int MINIMUM_DATES = 3;

    public static final int DEFAULT_SAMPLE_SIZE = 4;
    public static final int MAX_SAMPLE_SIZE = 12;

    /** The z of an 80% two-sided band, ADR 0043's {@code ForecastService.CONFIDENCE_Z}. */
    public static final double CONFIDENCE_Z = 1.2816;

    /** A flagged holiday counts for half an ordinary date under {@link HeadcountHolidayMode#WEIGHT}. */
    public static final double HOLIDAY_WEIGHT = 0.5;

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int PORTION_SCALE = 3;

    private HeadcountPlanner() {}

    /** Which dates make the sample: the newest {@code sampleSize} of the candidates, holidays per the mode. */
    public static List<LocalDate> sampleOf(
            List<LocalDate> candidatesNewestFirst,
            int sampleSize,
            HeadcountHolidayMode mode,
            Predicate<LocalDate> isHoliday) {
        return candidatesNewestFirst.stream()
                .filter(date -> mode != HeadcountHolidayMode.EXCLUDE || !isHoliday.test(date))
                .limit(sampleSize)
                .toList();
    }

    /** How many candidate dates the store should fetch so that EXCLUDE can still fill the sample. */
    public static int candidateLimit(int sampleSize, HeadcountHolidayMode mode) {
        return mode == HeadcountHolidayMode.INCLUDE ? sampleSize : Math.min(sampleSize * 3, 60);
    }

    public static Plan plan(Input input) {
        List<LocalDate> sampleDates = input.sampleDates();
        boolean enoughDates = sampleDates.size() >= MINIMUM_DATES;

        Map<UUID, Station> stationById = new HashMap<>();
        input.stations().stream()
                .filter(station -> station.role() != StationRole.EXPO)
                .forEach(station -> stationById.put(station.id(), station));

        // date -> station -> hour -> portions, only for planned stations and sample dates.
        Map<LocalDate, Map<UUID, Map<Integer, BigDecimal>>> loads = new HashMap<>();
        Set<Integer> activeHours = new TreeSet<>();
        for (LoadCell cell : input.loads()) {
            if (!stationById.containsKey(cell.stationId()) || !sampleDates.contains(cell.date())) {
                continue;
            }
            loads.computeIfAbsent(cell.date(), date -> new HashMap<>())
                    .computeIfAbsent(cell.stationId(), id -> new HashMap<>())
                    .merge(cell.hour(), cell.portions(), BigDecimal::add);
            if (cell.portions().signum() > 0) {
                activeHours.add(cell.hour());
            }
        }

        List<Station> planned = input.stations().stream()
                .filter(station -> station.role() != StationRole.EXPO)
                .sorted(Comparator.comparing(Station::code))
                .toList();

        List<Row> rows = new ArrayList<>();
        List<String> withoutFigure = new ArrayList<>();
        for (Station station : planned) {
            @Nullable Integer figure = input.figures().get(station.id());
            if (figure == null) {
                withoutFigure.add(station.code());
            }
            for (int hour : activeHours) {
                rows.add(rowOf(input, station, figure, hour, enoughDates, loads));
            }
        }

        List<RawCell> history = enoughDates ? List.of() : rawHistory(sampleDates, planned, loads);
        return new Plan(
                input.targetDate(),
                input.weekday(),
                sampleDates,
                enoughDates ? Status.OK : Status.TOO_FEW_DATES,
                List.copyOf(rows),
                enoughDates ? peakOf(rows) : null,
                List.copyOf(withoutFigure),
                history);
    }

    private static Row rowOf(
            Input input,
            Station station,
            @Nullable Integer figure,
            int hour,
            boolean enoughDates,
            Map<LocalDate, Map<UUID, Map<Integer, BigDecimal>>> loads) {

        if (figure == null) {
            return Row.withoutNumbers(
                    station, hour, Status.NO_POLICY, input.sampleDates().size());
        }
        if (!enoughDates) {
            return Row.withoutNumbers(
                            station,
                            hour,
                            Status.TOO_FEW_DATES,
                            input.sampleDates().size())
                    .withFigure(figure);
        }

        double weightedSum = 0;
        double weightSum = 0;
        List<Double> raw = new ArrayList<>(input.sampleDates().size());
        for (LocalDate date : input.sampleDates()) {
            double count = loads.getOrDefault(date, Map.of())
                    .getOrDefault(station.id(), Map.of())
                    .getOrDefault(hour, BigDecimal.ZERO)
                    .doubleValue();
            raw.add(count);
            double weight = input.holidayMode() == HeadcountHolidayMode.WEIGHT
                            && input.holidayDates().contains(date)
                    ? HOLIDAY_WEIGHT
                    : 1.0;
            weightedSum += count * weight;
            weightSum += weight;
        }
        double mean = weightedSum / weightSum;
        double rawMean = raw.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSquares =
                raw.stream().mapToDouble(v -> (v - rawMean) * (v - rawMean)).sum();
        double variance = raw.size() >= 2 ? sumSquares / (raw.size() - 1) : 0;
        double margin = CONFIDENCE_Z * Math.sqrt(variance);

        BigDecimal expected = portions(mean);
        BigDecimal low = portions(Math.max(0, mean - margin));
        BigDecimal high = portions(mean + margin);
        BigDecimal perCook = BigDecimal.valueOf(figure);
        @Nullable Integer ceiling = ceilingFor(input, station, hour);
        boolean plantLimited = ceiling != null && high.compareTo(BigDecimal.valueOf(ceiling)) > 0;

        return new Row(
                station.id(),
                station.code(),
                station.role(),
                hour,
                Status.OK,
                figure,
                expected,
                low,
                high,
                cooks(expected, perCook),
                cooks(high, perCook),
                ceiling,
                plantLimited,
                input.sampleDates().size());
    }

    /** The lowest ceiling of any window overlapping the operating hour's wall-clock span, or none. */
    private static @Nullable Integer ceilingFor(Input input, Station station, int hour) {
        int startMinute = input.businessDayStartMinute() + hour * 60;
        @Nullable Integer lowest = null;
        // The hour is [startMinute, startMinute + 60) from the business date's midnight: it can fall on the
        // next calendar day (a 09:00 business day's hour 17 is 02:00), and can straddle midnight when the
        // business day starts off the hour.
        for (int offset = 0; offset <= 2; offset++) {
            int segmentStart = Math.max(startMinute, offset * MINUTES_PER_DAY);
            int segmentEnd = Math.min(startMinute + 60, (offset + 1) * MINUTES_PER_DAY);
            if (segmentStart >= segmentEnd) {
                continue;
            }
            int weekday = ((input.weekday() - 1 + offset) % 7) + 1;
            int from = segmentStart - offset * MINUTES_PER_DAY;
            int to = segmentEnd - offset * MINUTES_PER_DAY;
            for (CapacityWindow window : input.windows()) {
                if (window.stationId().equals(station.id())
                        && window.weekday() == weekday
                        && window.startMinute() < to
                        && window.endMinute() > from
                        && (lowest == null || window.portionsPerHour() < lowest)) {
                    lowest = window.portionsPerHour();
                }
            }
        }
        return lowest;
    }

    private static @Nullable Peak peakOf(List<Row> rows) {
        Map<Integer, int[]> byHour = new HashMap<>();
        for (Row row : rows) {
            Integer expected = row.cooksExpected();
            Integer band = row.cooksAtPeakOfBand();
            if (row.status() != Status.OK || expected == null || band == null) {
                continue;
            }
            int[] sums = byHour.computeIfAbsent(row.hour(), hour -> new int[2]);
            sums[0] += expected;
            sums[1] += band;
        }
        return byHour.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, int[]>>comparingInt(entry -> entry.getValue()[0])
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(entry -> new Peak(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .orElse(null);
    }

    private static List<RawCell> rawHistory(
            List<LocalDate> sampleDates,
            List<Station> planned,
            Map<LocalDate, Map<UUID, Map<Integer, BigDecimal>>> loads) {
        List<RawCell> cells = new ArrayList<>();
        for (LocalDate date : sampleDates) {
            for (Station station : planned) {
                new TreeMap<>(loads.getOrDefault(date, Map.of()).getOrDefault(station.id(), Map.of()))
                        .forEach((hour, portions) -> cells.add(new RawCell(
                                date, station.code(), hour, portions.setScale(PORTION_SCALE, RoundingMode.HALF_UP))));
            }
        }
        return List.copyOf(cells);
    }

    private static BigDecimal portions(double value) {
        return BigDecimal.valueOf(value).setScale(PORTION_SCALE, RoundingMode.HALF_UP);
    }

    private static int cooks(BigDecimal portions, BigDecimal perCook) {
        return portions.divide(perCook, 0, RoundingMode.CEILING).intValueExact();
    }

    // ------------------------------------------------------------------------------------ shapes

    public enum Status {
        OK,
        NO_POLICY,
        TOO_FEW_DATES
    }

    /** A station as it is now: the plan covers current ACTIVE stations, whatever the old facts called them. */
    public record Station(UUID id, String code, StationRole role) {}

    /** One fact row: portions a station fired in an operating hour of a business date. */
    public record LoadCell(LocalDate date, UUID stationId, int hour, BigDecimal portions) {}

    /** One {@code kitchen.station_capacity} window, in minutes from local midnight of its weekday. */
    public record CapacityWindow(UUID stationId, int weekday, int startMinute, int endMinute, int portionsPerHour) {}

    /**
     * @param businessDayStartMinute the tenant's business-day start, minutes after local midnight; operating
     *                               hour 0 begins there
     * @param figures                station id to portions per cook-hour; a station absent has no figure
     */
    public record Input(
            LocalDate targetDate,
            int weekday,
            List<LocalDate> sampleDates,
            Set<LocalDate> holidayDates,
            HeadcountHolidayMode holidayMode,
            List<Station> stations,
            Map<UUID, Integer> figures,
            List<LoadCell> loads,
            List<CapacityWindow> windows,
            int businessDayStartMinute) {}

    /** The plan for one branch and weekday. {@code history} is the raw per-date portions, only when too thin. */
    public record Plan(
            LocalDate targetDate,
            int weekday,
            List<LocalDate> sampleDates,
            Status status,
            List<Row> rows,
            @Nullable Peak peak,
            List<String> stationsWithoutFigure,
            List<RawCell> history) {}

    public record Row(
            UUID stationId,
            String stationCode,
            StationRole stationRole,
            int hour,
            Status status,
            @Nullable Integer portionsPerCookHour,
            @Nullable BigDecimal expectedPortions,
            @Nullable BigDecimal lowPortions,
            @Nullable BigDecimal highPortions,
            @Nullable Integer cooksExpected,
            @Nullable Integer cooksAtPeakOfBand,
            @Nullable Integer stationCeiling,
            boolean plantLimited,
            int sampleSize) {

        static Row withoutNumbers(Station station, int hour, Status status, int sampleSize) {
            return new Row(
                    station.id(),
                    station.code(),
                    station.role(),
                    hour,
                    status,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    sampleSize);
        }

        Row withFigure(int figure) {
            return new Row(
                    stationId,
                    stationCode,
                    stationRole,
                    hour,
                    status,
                    figure,
                    expectedPortions,
                    lowPortions,
                    highPortions,
                    cooksExpected,
                    cooksAtPeakOfBand,
                    stationCeiling,
                    plantLimited,
                    sampleSize);
        }
    }

    /** The busiest hour across stations that have a figure, and the cooks it needs. */
    public record Peak(int hour, int cooksExpected, int cooksAtPeakOfBand) {}

    public record RawCell(LocalDate date, String stationCode, int hour, BigDecimal portions) {}
}
