package uz.horecaos.platform.customers.domain;

/** What the operator says should happen after a contact attempt (ADR 0111 data model). */
public enum NextAction {
    CALL_AGAIN,
    AWAIT_GUEST,
    HAND_TO_BRANCH
}
