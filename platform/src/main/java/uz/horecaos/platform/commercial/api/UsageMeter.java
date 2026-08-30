package uz.horecaos.platform.commercial.api;

/**
 * Records what a tenant consumed (ADR 0021).
 *
 * <p>The port every product module calls. It appends and never updates, and it
 * is idempotent on the movement's source pair, so a caller may record the same
 * fact as many times as its own retry policy demands.
 *
 * <p>Metering is deliberately not an entitlement check. A module records that an
 * order happened; whether that order was inside an allowance is a separate
 * question asked by a separate port at a separate moment. Fusing the two would
 * mean that switching enforcement on changed what gets measured, and then the
 * evidence for the switch would be destroyed by the switch.
 */
public interface UsageMeter {

    /**
     * Appends a movement.
     *
     * @return true when it was recorded, false when this exact movement had
     *         already been recorded and was therefore ignored
     */
    boolean record(UsageMovement movement);
}
