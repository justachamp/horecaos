package uz.horecaos.platform.courier.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The tenant's business-day boundary (ADR 0043), in the shape {@code
 * CourierAccrualService} needs it: the calendar date one instant falls on,
 * to stamp {@code courier_assignment_earnings.business_date} on the
 * tenant's own trading day rather than the UTC calendar.
 *
 * <p>A port, declared here and implemented by reporting — the same
 * direction-of-dependency {@code ordering.api.BusinessDayWindows} and
 * {@code customers.api.BusinessDayWindows} already use, and for the
 * identical reason each of those ports' own doc gives: the boundary is
 * {@code reporting}'s (ADR 0043, {@code BusinessDayService}), and a second
 * interface exists per consumer module rather than one shared {@code
 * reporting.api} package so that each module depends on exactly the one
 * read it needs. This one returns a {@link LocalDate} rather than an
 * instant window because that is what a persisted column needs — the other
 * two ports answer a live range query instead, a different shape for a
 * different question.
 *
 * <p>Before this port existed, {@code CourierAccrualService.recordDelivery}
 * computed {@code businessDate} as a plain UTC calendar date — a second,
 * unregistered notion of a business day that disagreed with every fact
 * {@code DayCloseService} derives (all of which go through this same
 * boundary) for the five hours between UTC midnight and midnight in a
 * Tashkent tenant's own zone. A delivery completed at 02:00 local time
 * stamped the previous day's date, and {@code reporting.fact_delivery} —
 * which trusted that stamped date rather than re-deriving it — silently
 * dropped the earning, since the wrong day's close had usually already run
 * by the time the right day's close looked for it.
 */
public interface BusinessDayWindows {

    /**
     * The business date {@code at} falls on, for this tenant's own boundary
     * and timezone.
     *
     * @throws IllegalStateException if the tenant has no timezone at all, which
     *                               is a provisioning fault rather than a
     *                               condition a caller can recover from
     */
    LocalDate businessDateOf(UUID tenantId, Instant at);
}
