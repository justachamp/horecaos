package uz.horecaos.platform.printing.domain;

import java.util.Optional;

/** What a print agent last reported for a printer it advertises (DLE EOT status, ADR 0154). */
public enum PrinterStatus {
    ONLINE,
    OFFLINE,
    OUT_OF_PAPER,
    COVER_OPEN,
    ERROR,
    UNKNOWN;

    /**
     * Whether jobs may be claimed for a printer in this state. An offline printer stops being claimed
     * from, so a job does not burn attempts against a printer that is switched off; a printer that is
     * out of paper or has its cover open is claimed from no more than an offline one.
     */
    public boolean mayBeClaimedFrom() {
        return this == ONLINE || this == UNKNOWN;
    }

    public static Optional<PrinterStatus> parse(String value) {
        for (PrinterStatus status : values()) {
            if (status.name().equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
