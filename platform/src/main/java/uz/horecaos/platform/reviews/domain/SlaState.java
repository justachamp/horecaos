package uz.horecaos.platform.reviews.domain;

/**
 * Where a case stands against its two clocks (ADR 0159, Decision 10), computed on read.
 *
 * <p>An open case is {@link #ON_TRACK}, {@link #AT_RISK} (in the last 20% of the clock that is still
 * running) or {@link #BREACHED}. A closed case is judged against when it was answered or closed, not
 * against now, so it is {@link #MET} or {@link #BREACHED} for good.
 */
public enum SlaState {
    ON_TRACK,
    AT_RISK,
    BREACHED,
    MET
}
