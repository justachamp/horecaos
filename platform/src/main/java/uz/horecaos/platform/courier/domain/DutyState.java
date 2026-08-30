package uz.horecaos.platform.courier.domain;

/**
 * The courier's state within an open shift (ADR 0042).
 *
 * <p>{@link #AT_CAPACITY} and {@link #UNREACHABLE} are derived from the active
 * assignment count and telemetry staleness and are never settable. A settable
 * derived state is one that will eventually disagree with what it derives from,
 * and the disagreement shows up as a dispatcher assigning work to somebody whose
 * phone has been off for an hour.
 */
public enum DutyState {
    AVAILABLE,
    ON_BREAK,
    AT_CAPACITY,
    UNREACHABLE;

    public boolean settableByCourier() {
        return this == AVAILABLE || this == ON_BREAK;
    }
}
