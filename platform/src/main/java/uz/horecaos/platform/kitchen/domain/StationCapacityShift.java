package uz.horecaos.platform.kitchen.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * How much earlier a ticket must release to survive its station's throughput
 * ceiling (ADR 0041, V0144's {@code kitchen.station_capacity}).
 *
 * <p>Pure, like {@code pos.domain.ScheduleCadence} and {@code
 * fulfillment.domain.tariff.TariffTimeRule}, which this class's window
 * arithmetic mirrors exactly and for the same reason: no database, no clock, no
 * Spring, so the answer is provably a function of its arguments and
 * {@code KitchenModuleBoundaryTests}-style rules never have to reach this class
 * to know it does not depend on infrastructure.
 *
 * <h2>What "reached" means, and what it does not</h2>
 *
 * <p>ADR 0041's own words: "A ceiling shifts {@code release_at}; it never
 * rejects an order... If shifting would push the ticket past {@code
 * target_ready_at - prep_estimate}, release happens anyway and raises an
 * operational exception: a kitchen that quietly holds a ticket to protect its
 * own throughput number produces a late order nobody was warned about." That
 * sentence fixes the direction: a reached ceiling only ever pulls {@code
 * release_at} <em>earlier</em> than the plain {@code target_ready_at -
 * prep_estimate} baseline, buying the ticket more time to clear the station's
 * queue before it is due. It never delays a release past that baseline to pace
 * load — the failure mode the ADR names by name, and the reason a naive
 * "smooth the bursts by holding tickets back" design is rejected here rather
 * than built.
 *
 * <h2>What counts as load</h2>
 *
 * <p>Portions — {@code ticket_items.quantity} summed per station — not tickets
 * and not line count. A ticket with one line at quantity five is five plates
 * off the grill, and a ceiling stated as "40 plates an hour" is a ceiling on
 * plates, not on how many customers ordered them.
 *
 * <h2>The formula</h2>
 *
 * <p>{@code offsetSeconds} is the lead time such that, at the station's own
 * rate, the portion of demand that overflows the ceiling's window gets a head
 * start proportional to the overflow: {@code overage / (portionsPerHour /
 * 3600)}. A station exactly at its ceiling needs no offset; a station already
 * double-booked for the hour needs an hour's worth of extra lead per multiple
 * over. This is a heuristic, not a queueing simulation — ADR 0041 sketches
 * {@code station_queue_offset} without a formula, and a full discrete-event
 * model of one kitchen's pass is disproportionate machinery for a number a
 * manager already sets by eye on the capacity screen. What it guarantees is the
 * one property that matters: more overage asks for more lead time, monotonically,
 * and no overage asks for none.
 */
public final class StationCapacityShift {

    private StationCapacityShift() {}

    /**
     * The UTC bounds of one calendar occurrence of a local weekday+time window.
     *
     * <p>{@code at} anchors which calendar date the window falls on — the same
     * local date {@code at} itself falls on, in {@code zone}. The caller is
     * responsible for having already matched {@code at}'s local weekday against
     * the window's own {@code weekday} column; this method does not check it
     * again, because a window is only ever looked up for the day it was matched
     * against.
     */
    public static WindowOccurrence occurrence(Instant at, ZoneId zone, LocalTime windowStart, LocalTime windowEnd) {
        LocalDate date = at.atZone(zone).toLocalDate();
        // ZonedDateTime.of, not Instant arithmetic, for the reason
        // ScheduleCadence gives at length: a local time resolves against the
        // zone's rules for its own date, which is what keeps a window's length
        // correct on the one or two days a year a zone's offset changes. Most
        // tenants are in Asia/Tashkent, which has observed no daylight saving
        // since 1995 and would never exercise this, but the first tenant
        // outside it must not inherit an hour of silent drift twice a year.
        ZonedDateTime start = ZonedDateTime.of(date, windowStart, zone);
        ZonedDateTime end = ZonedDateTime.of(date, windowEnd, zone);
        return new WindowOccurrence(start.toInstant(), end.toInstant());
    }

    /**
     * The extra lead time one station's ceiling asks of this ticket, in
     * addition to {@code target_ready_at - prep_estimate}.
     *
     * @param portionsPerHour   the ceiling, {@code kitchen.station_capacity}'s
     *                          own column. Never negative by the database's own
     *                          check constraint; treated as "no ceiling" if it
     *                          somehow were, rather than dividing by it
     * @param windowDuration    the configured window's length. Ceiling windows
     *                          need not be exactly one hour, so the ceiling is
     *                          first scaled to the window before comparing
     * @param committedPortions portions this station has already promised to
     *                          produce inside this occurrence of the window,
     *                          across every other non-voided ticket
     * @param thisTicketPortions this ticket's own portions at this station
     * @return zero when the ceiling still has room for this ticket; otherwise
     *         the number of seconds this ticket's release must move earlier
     */
    public static long offsetSeconds(
            int portionsPerHour, Duration windowDuration, long committedPortions, long thisTicketPortions) {

        if (portionsPerHour <= 0 || windowDuration.isZero() || windowDuration.isNegative()) {
            return 0;
        }
        double capacityForWindow = portionsPerHour * (windowDuration.toSeconds() / 3600.0);
        double projected = committedPortions + thisTicketPortions;
        if (projected <= capacityForWindow) {
            return 0;
        }
        double overage = projected - capacityForWindow;
        double ratePerSecond = portionsPerHour / 3600.0;
        return (long) Math.ceil(overage / ratePerSecond);
    }

    public record WindowOccurrence(Instant start, Instant end) {

        public Duration duration() {
            return Duration.between(start, end);
        }
    }
}
