package uz.qoida.platform.migration.domain;

/**
 * Where reads for one capability in one scope are served from (ADR 0024).
 *
 * <p>{@link #SHADOW_COMPARE} is the value that is easy to misread. It reads both
 * systems and records the differences, but the caller still receives the legacy
 * answer: the ADR is explicit that shadow differences are recorded without
 * returning target data. A shadow read that leaked the target's answer would be
 * a silent, unapproved cutover for whoever happened to make that request.
 */
public enum ReadMode {

    /** Legacy answers. The target is not consulted. */
    LEGACY,

    /** Both are read and compared; legacy still answers the caller. */
    SHADOW_COMPARE,

    /** A bounded share of traffic is answered by the target. */
    CANARY_TARGET,

    /** The target answers. Legacy is no longer in the read path. */
    TARGET;

    /** Whether data the caller receives can come from the target. */
    public boolean servesTarget() {
        return this == CANARY_TARGET || this == TARGET;
    }

    /** Whether the target is read at all, including reads whose result is only compared. */
    public boolean touchesTarget() {
        return this != LEGACY;
    }
}
