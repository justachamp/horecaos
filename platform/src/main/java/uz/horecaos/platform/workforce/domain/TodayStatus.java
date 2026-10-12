package uz.horecaos.platform.workforce.domain;

/**
 * The derived status of one person on «Сегодня» (ADR 0156, Specification), in the spec's order of
 * urgency. Never stored: computed from the roster, the shifts, the tenant's business day and, for
 * {@link #WORKING_WITHOUT_SHIFT}, order attribution.
 */
public enum TodayStatus {
    /** A published entry started more than {@code workforce.roster.late_after_minutes} ago with no shift. */
    LATE,
    /** A published entry for today ended with no shift. A future day never reads as missed. */
    MISSED_TODAY,
    /**
     * The person created or accepted an order at this branch today and has no open shift. Only order
     * attribution can raise it, so a person who only cooks never does; the screen says so. Its only
     * effect is that the manager can open or correct a shift afterwards -- never a refusal of work.
     */
    WORKING_WITHOUT_SHIFT,
    /** A shift open longer than {@code workforce.shift.max_open_hours}; never closed by a job. */
    OPEN_TOO_LONG,
    /** A shift is open. */
    WORKING,
    /** A shift closed today. */
    CLOSED,
    /** A published entry later today that has not started, or not yet late. */
    SCHEDULED
}
