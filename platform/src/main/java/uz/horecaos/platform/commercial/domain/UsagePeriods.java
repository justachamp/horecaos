package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.api.UsagePeriod;

/**
 * Which window a movement counts against (ADR 0021).
 *
 * <p>Computed in the tenant's own timezone, never in UTC. Asia/Tashkent is
 * UTC+5, so a month boundary read in UTC closes a tenant's period at 19:00 on
 * the last day of the previous month; five hours of real orders then land in the
 * wrong invoice and the tenant's own order list is the thing that proves it.
 *
 * <p>The period is fixed at record time and stored on the movement rather than
 * derived at read time. A tenant that changes timezone has not changed what
 * happened last March.
 */
public final class UsagePeriods {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * The far end of a standing limit's single period. A concrete instant rather
     * than null, so the period's own "ends after it starts" rule holds without a
     * special case, and so a range query needs no {@code IS NULL} branch.
     */
    private static final Instant NEVER = Instant.parse("9999-12-31T00:00:00Z");

    private UsagePeriods() {
    }

    /**
     * The period {@code at} falls into.
     *
     * @param reset        the entitlement's reset period
     * @param at           the instant the metered thing happened
     * @param zone         the tenant's timezone
     * @param billingStart the live subscription's current period start, or null
     * @param billingEnd   the live subscription's current period end, or null
     */
    public static UsagePeriod of(ResetPeriod reset, Instant at, ZoneId zone,
            Instant billingStart, Instant billingEnd) {

        return switch (reset) {
            case NONE -> new UsagePeriod(UsagePeriod.LIFETIME, Instant.EPOCH, NEVER);
            case DAILY -> {
                LocalDate date = at.atZone(zone).toLocalDate();
                yield new UsagePeriod(
                        DAY.format(date),
                        date.atStartOfDay(zone).toInstant(),
                        date.plusDays(1).atStartOfDay(zone).toInstant());
            }
            case MONTHLY -> monthly(at, zone);
            case BILLING_PERIOD -> {
                // A subscription that has not started yet, or a tenant with none
                // at all, still consumes things that have to be counted
                // somewhere. The calendar month is the honest fallback: it is
                // reproducible, and when a subscription later starts, the earlier
                // usage stays in the period it was measured in rather than
                // silently moving.
                if (billingStart == null || billingEnd == null
                        || at.isBefore(billingStart) || !at.isBefore(billingEnd)) {
                    yield monthly(at, zone);
                }
                yield new UsagePeriod(DAY.format(billingStart.atZone(zone).toLocalDate()),
                        billingStart, billingEnd);
            }
        };
    }

    /**
     * The next billing period after one that has closed, advanced by whole
     * months. Used to roll a subscription forward without proration, which this
     * module does not do and ADR 0013 does not do either.
     */
    public static Instant advance(Instant periodStart, ZoneId zone, int months) {
        ZonedDateTime local = periodStart.atZone(zone);
        return local.plusMonths(months).toInstant();
    }

    /** Whether an instant sits inside a closed period, for a late-event check. */
    public static boolean isLate(UsagePeriod period, Instant recordedAt) {
        return recordedAt.isAfter(period.end())
                && ChronoUnit.SECONDS.between(period.end(), recordedAt) > 0;
    }

    private static UsagePeriod monthly(Instant at, ZoneId zone) {
        LocalDate first = at.atZone(zone).toLocalDate().withDayOfMonth(1);
        return new UsagePeriod(
                MONTH.format(first),
                first.atStartOfDay(zone).toInstant(),
                first.plusMonths(1).atStartOfDay(zone).toInstant());
    }
}
