package uz.horecaos.platform.telemetry.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * When each named courier was last heard from, and nothing about where
 * (ADR 0045) — the roster's online indicator (IA 3.3, gap map row {@code
 * 3.3}: "no online status... no source exists on either side").
 *
 * <p>The same reduction {@link CourierProximityPort} already makes for the
 * identical reason, named on that interface's own doc: a position is read
 * through capability-gated HTTP at a location scope and nowhere else, so a
 * module outside {@code telemetry} gets an instant, never a coordinate.
 * "Online" is a courier module and console decision (how many minutes counts
 * as recent enough is an ADR 0030 policy field, not a telemetry rule), so
 * this port answers only the one fact that decision needs.
 *
 * <p><strong>Absent means "we do not know", never "offline".</strong> A
 * courier with no live row — no open duty session ever, or one closed long
 * enough ago that the retention sweep already removed the row — is missing
 * from the answer. A caller with a threshold decides what that means; this
 * port does not, the same stance {@link CourierProximityPort#metresFromBranch}
 * takes for a courier it cannot measure.
 */
public interface CourierLastSeenPort {

    /**
     * @return the {@code captured_at} of each courier's current live row,
     *         entries omitted rather than null-valued for a courier with none
     */
    Map<UUID, Instant> lastFixByCourier(UUID tenantId, Collection<UUID> courierIds);
}
