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
}
