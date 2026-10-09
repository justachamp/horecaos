package uz.horecaos.platform.workforce.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The tenant's business day (ADR 0043) as {@code workforce} needs it: which business date an instant
 * belongs to, and which instants a business date spans.
 *
 * <p>Declared here and implemented by {@code reporting}, the direction every other module's
 * business-day seam runs ({@code ordering}, {@code courier}, {@code customers}, {@code inventory}):
 * the arithmetic is {@code BusinessDayBoundary}'s and the resolution order is {@code
 * BusinessDayService}'s, and neither is restated in this module. A shift's business date is recorded
 * when it is opened and recomputed when its start is edited, so a payroll fact is keyed by the date
 * the tenant would say, not by a UTC calendar date.
 */
public interface AttendanceBusinessDay {

    LocalDate businessDateOf(UUID tenantId, Instant at);

    /** The instants {@code businessDate} spans for this tenant: {@code [from, to)}. */
    Window windowOf(UUID tenantId, LocalDate businessDate);

    record Window(Instant from, Instant to) {}
}
