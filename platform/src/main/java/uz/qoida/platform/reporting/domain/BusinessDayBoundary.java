package uz.qoida.platform.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Which business day an instant falls on (ADR 0043).
 *
 * <p>The business day here is not the calendar day. A restaurant that closes at
 * 02:00 and sees those orders filed under the next date concludes the report is
 * broken, so the boundary is stored on the tenant and computed once, at write
 * time, onto every fact — rather than being assumed by a hundred queries, each of
 * which is a chance to assume it differently.
 *
 * <p>Uzbekistan is UTC+5 with no daylight saving, which removes the entire class
 * of bugs where a business day is 23 or 25 hours long. The arithmetic below still
 * goes through {@link ZonedDateTime} rather than a fixed offset, because a tenant
 * timezone is a stored string and the first tenant outside Uzbekistan would
 * otherwise be a silent hour out.
 *
 * @param zone  the tenant's timezone
 * @param start the local wall-clock time the day begins, {@code 00:00} by default
 * @param version the boundary regime. Stamped onto every fact so a range that
 *                spans a boundary change can be refused rather than answered by
 *                mixing two definitions of the same Tuesday
 */
public record BusinessDayBoundary(ZoneId zone, LocalTime start, int version) {

    public static final LocalTime DEFAULT_START = LocalTime.MIDNIGHT;

    public BusinessDayBoundary {
        Objects.requireNonNull(zone, "A business day needs a timezone");
        Objects.requireNonNull(start, "A business day needs a start time");
        if (version < 1) {
            throw new IllegalArgumentException("A boundary version starts at 1, was " + version);
        }
        // A boundary at a whole minute is what an operator can state and verify.
        // Seconds would be unenforceable in the UI and invisible in the data.
        if (start.getSecond() != 0 || start.getNano() != 0) {
            throw new IllegalArgumentException(
                    "A business day starts on a whole minute, was " + start);
        }
    }

    /** Midnight in the tenant's own zone: what a merchant assumes until they say otherwise. */
    public static BusinessDayBoundary midnight(ZoneId zone) {
        return new BusinessDayBoundary(zone, DEFAULT_START, 1);
    }

    /**
     * The business date an instant belongs to.
     *
     * <p>An instant before the day's start belongs to the previous date. For a
     * 09:00 tenant, 08:59 is still yesterday and 09:01 is today; for a midnight
     * tenant the business date is the local calendar date, which is the case that
     * has to keep working unchanged.
     */
    public LocalDate dateOf(Instant instant) {
        Objects.requireNonNull(instant, "An instant is required");
        ZonedDateTime local = instant.atZone(zone);
        return local.toLocalTime().isBefore(start)
                ? local.toLocalDate().minusDays(1)
                : local.toLocalDate();
    }

    /** The instant the given business date begins, inclusive. */
    public Instant startOf(LocalDate businessDate) {
        return businessDate.atTime(start).atZone(zone).toInstant();
    }

    /** The instant the given business date ends, exclusive. */
    public Instant endOf(LocalDate businessDate) {
        return startOf(businessDate.plusDays(1));
    }
}
