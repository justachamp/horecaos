package uz.horecaos.platform.fiscal.domain;

/** A terminal's own lifecycle, independent of its most recent health check. */
public enum FiscalTerminalStatus {
    /** Registered and offered as fiscal-capable, subject to its capability snapshot. */
    ACTIVE,
    /** Taken out of service by an operator; leaves the channel matrix immediately. */
    SUSPENDED,
    /**
     * Permanently withdrawn. Never deleted: a fiscal document issued through this
     * terminal must still resolve which box issued it years later.
     */
    RETIRED
}
