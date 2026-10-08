package uz.horecaos.platform.printing.domain;

import java.util.Optional;

/** Why an attempt to print did not produce paper. The agent reports the first five; the platform writes the last. */
public enum FailureCode {
    PRINTER_OFFLINE,
    OUT_OF_PAPER,
    COVER_OPEN,
    TRANSPORT,
    RENDER,
    /** The lease ran out before a result arrived: the agent died, or lost the network, mid-job. */
    LEASE_LAPSED;

    public static Optional<FailureCode> parse(String value) {
        for (FailureCode code : values()) {
            if (code.name().equals(value)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}
