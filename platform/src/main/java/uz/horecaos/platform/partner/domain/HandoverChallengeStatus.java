package uz.horecaos.platform.partner.domain;

/** Where a handover challenge got to (ADR 0040). */
public enum HandoverChallengeStatus {
    PENDING,

    /** The correct value was entered. */
    VERIFIED,

    /**
     * A supervisor overrode it. Requires {@code marketplace.handover.bypass} and
     * writes an ADR 0027 audit fact naming the supervisor and the reason.
     */
    BYPASSED,

    /** {@code max_attempts} exhausted. A bypass is the only way past this. */
    FAILED,

    /** The order was cancelled, or the challenge aged out. */
    EXPIRED
}
