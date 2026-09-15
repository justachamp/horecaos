package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.Set;
import uz.horecaos.platform.reporting.domain.HolidayMode;

/**
 * 7.8b: how much a sample date counts toward {@code demand-history}'s average
 * (and the forecast model that draws on the same sample), given {@link
 * HolidayMode}.
 *
 * <p>{@code INCLUDE} and {@code EXCLUDE} both resolve to full weight here —
 * {@code EXCLUDE} does its work earlier, by dropping the date from the sample
 * entirely in {@code JdbcReportingStore#readDemandHistory}, so every date this
 * class is ever asked about is one that stayed in. Only {@code WEIGHT} can
 * answer anything other than 1.0.
 */
public final class HolidayAwareness {

    private HolidayAwareness() {}

    /**
     * A flagged holiday date counts for this fraction of an ordinary date's
     * weight under {@link HolidayMode#WEIGHT} — never full weight (a holiday
     * genuinely trades differently) and never zero (it still happened, and a
     * manager who wants it gone entirely has {@link HolidayMode#EXCLUDE}).
     * One documented constant rather than a tenant-tunable setting, the same
     * conservative-default choice {@code ReportingController}'s own {@code
     * DEMAND_SAMPLE_DEFAULT}/{@code DEMAND_SAMPLE_MAX} already make without an
     * ADR — see this wave's report for the open question of whether a product
     * owner wants this configurable later.
     */
    public static final double HOLIDAY_WEIGHT = 0.5;

    /** The weight one sample date contributes to a weighted average, given the mode and which dates are flagged. */
    public static double weightOf(LocalDate date, HolidayMode mode, Set<LocalDate> holidayDates) {
        return mode == HolidayMode.WEIGHT && holidayDates.contains(date) ? HOLIDAY_WEIGHT : 1.0;
    }
}
