package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The tenant's business-day boundary (ADR 0043), in the shape {@code
 * InventoryQuantityResetScheduler} needs it: the calendar date one instant
 * falls on, so a QUANTITY item's daily default resets on the tenant's own
 * trading day rather than the UTC calendar — gap map row 4.4c's own "auto-
 * reset at the tenant's business-day boundary".
 *
 * <p>A port, declared here and implemented by reporting — the same
 * direction-of-dependency {@code courier.api.BusinessDayWindows}, {@code
 * ordering.api.BusinessDayWindows} and {@code customers.api.BusinessDayWindows}
 * already use, and for the identical reason each of those ports' own doc
 * gives: the boundary is {@code reporting}'s (ADR 0043, {@code
 * BusinessDayService}), and a second interface exists per consumer module
 * rather than one shared {@code reporting.api} package so that each module
 * depends on exactly the one read it needs.
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

    /**
     * The first instant after {@code at} that falls on a later business date — "until the
     * end of the trading day" for a stop (ADR 0141 Decision 5), which a restaurant that
     * trades past midnight does not mean as "until 00:00".
     *
     * <p>Found by bisection over {@link #businessDateOf}, so any boundary the reporting
     * module draws — a 04:00 rollover, a timezone — is honoured without this port growing
     * a second method for the adapter to implement. A day is at most 25 hours (a DST
     * change), and the search is accurate to the second.
     */
    default Instant endOfBusinessDay(UUID tenantId, Instant at) {
        LocalDate today = businessDateOf(tenantId, at);
        Instant low = at;
        Instant high = at.plusSeconds(26L * 3600L);
        if (!businessDateOf(tenantId, high).isAfter(today)) {
            // A boundary that never advances within a day and a bit is a provisioning fault,
            // not a time this port can answer; the caller falls back to an explicit end.
            throw new IllegalStateException("The tenant's business day does not end within 26 hours");
        }
        while (high.getEpochSecond() - low.getEpochSecond() > 1) {
            Instant middle = Instant.ofEpochSecond((low.getEpochSecond() + high.getEpochSecond()) / 2);
            if (businessDateOf(tenantId, middle).isAfter(today)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return high;
    }
}
