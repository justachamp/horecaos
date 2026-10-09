package uz.horecaos.platform.workforce.domain;

/**
 * A staff shift's lifecycle (ADR 0156 Decision 4). {@code OPEN -> CLOSED}, or {@code OPEN ->
 * AWAITING_APPROVAL -> CLOSED} when the absolute variance is over the threshold, and any
 * non-voided shift may be {@code VOIDED} with a reason; a shift is never deleted. A shift in
 * {@code AWAITING_APPROVAL} is excluded from payroll facts until a holder of {@code
 * staff.shift.approve} approves it.
 */
public enum ShiftStatus {
    OPEN,
    AWAITING_APPROVAL,
    CLOSED,
    VOIDED
}
