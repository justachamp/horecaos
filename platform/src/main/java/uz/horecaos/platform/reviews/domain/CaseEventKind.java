package uz.horecaos.platform.reviews.domain;

/** One line of a case's append-only timeline (ADR 0159, Decision 8). */
public enum CaseEventKind {
    OPENED,
    ASSIGNED,
    STARTED,
    RELEASED,
    NOTE,
    COMPENSATION_ATTACHED,
    FIRST_RESPONSE_BREACHED,
    RESOLUTION_BREACHED,
    RESOLVED,
    CLOSED_UNRESOLVED
}
