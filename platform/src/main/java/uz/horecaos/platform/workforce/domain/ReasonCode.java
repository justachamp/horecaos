package uz.horecaos.platform.workforce.domain;

/**
 * The closed list of reasons a manager gives for authoring, correcting, approving or voiding hours
 * (ADR 0156 Decision 7): never typed, so a reason can be counted and an audit fact can be read
 * without a free-text field that holds a person's name. Required on every manager-authored or
 * past-dated entry, on every correction, on every approval and on every void.
 */
public enum ReasonCode {
    /** The person worked and did not clock in. */
    FORGOT_CLOCK_IN,
    /** The person worked and did not clock out. */
    FORGOT_CLOCK_OUT,
    /** The console or a device was unavailable. */
    SYSTEM_PROBLEM,
    /** The rota changed after it was published. */
    PLAN_CHANGED,
    /** Two people swapped. */
    SHIFT_SWAP,
    /** Someone covered an absence or a shortage. */
    COVERED_SHORTAGE,
    /** The shift or entry was a mistake. */
    ENTERED_IN_ERROR,
    /** The same shift was recorded twice. */
    DUPLICATE,
    /** The person did not work this shift. */
    DID_NOT_WORK,
    /** An approver looked and the recorded hours are right. */
    WORKED_AS_RECORDED,
    /** A published entry was cancelled because the person is unavailable. */
    PERSON_UNAVAILABLE,
    /** A published entry was cancelled because the branch is closed or the plan is dropped. */
    SCHEDULE_CHANGED
}
