package uz.horecaos.platform.telemetry.domain;

/**
 * The three states of a duty session (ADR 0045).
 *
 * <p>Deliberately not four. There is no "paused by the platform" state, because
 * every reason collection stops is somebody's decision that is recorded
 * elsewhere: the courier took a break, the courier signed off, or a manager
 * closed the shift. A state the platform enters on its own would be collection
 * stopping for a reason nobody can find afterwards.
 */
public enum DutySessionStatus {

    /** Collecting. */
    OPEN,

    /**
     * A break is running. ADR 0042 ends breaks and this module does not: a
     * manager who could resume collection could resume it for someone who is
     * still on their break.
     */
    SUSPENDED,

    /** Signed off. The live row is deleted an hour later. */
    CLOSED;

    public boolean isFinished() {
        return this == CLOSED;
    }

    /** Whether an observation arriving now is stored at all. */
    public boolean accepts() {
        return this == OPEN;
    }
}
