package uz.qoida.platform.courier.domain;

/** The shift lifecycle (ADR 0042). */
public enum ShiftStatus {

    OPEN,
    CLOSE_REQUESTED,
    RECONCILING,

    /**
     * Closed with hours that need a manager. Auto-closed shifts always land
     * here: paying an unreviewed self-opened shift pays somebody who opened the
     * app at home.
     */
    AWAITING_APPROVAL,

    CLOSED,
    AUTO_CLOSED,
    SETTLED;

    public boolean live() {
        return this == OPEN || this == CLOSE_REQUESTED || this == RECONCILING;
    }
}
