package uz.horecaos.platform.telemetry.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How far each of these couriers is from a branch, in metres (ADR 0045).
 *
 * <p>A distance and never a coordinate, and that is the whole reason this
 * interface exists rather than a getter for the live position. ADR 0042's
 * dispatch ranking prefers the nearer courier; it does not need to know where
 * anybody is, and this module's rule is that a position is read through
 * capability-gated HTTP at a location scope and nowhere else. A metre count
 * against a branch the caller already named is a circle, not a pin — the same
 * reduction {@code fulfillment.api.InternalFleetPort.FleetCandidate} already
 * carries onwards as {@code metresFromBranch}.
 *
 * <p><strong>Absent means "we do not know", never "far".</strong> A courier is
 * missing from the answer when they have no live row at all — no open duty
 * session, or a break that suspended collection — when their last fix is older
 * than {@code LivePositionRules.MAXIMUM_STALENESS}, when it is coarser than the
 * map accuracy floor, or when the branch itself has no coordinate. ADR 0014's
 * ranking sorts a null last for exactly this reason: an unknown position is not
 * evidence of being close, and a 900 m accuracy circle ranked as a 200 m
 * distance is the same confident lie the map refuses to draw.
 */
public interface CourierProximityPort {

    /**
     * How far each named courier currently is from one branch.
     *
     * @param locationId the branch to measure from. Its published coordinate is
     *                   the one on {@code tenant.locations}, in clear because a
     *                   restaurant's address is advertised by the merchant
     * @return metres per courier, entries omitted rather than null-valued
     */
    Map<UUID, Integer> metresFromBranch(UUID tenantId, UUID locationId, Collection<UUID> courierIds);

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }
}
