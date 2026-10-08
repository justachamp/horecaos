package uz.horecaos.platform.customers.domain;

/**
 * What came of one voice contact (ADR 0111 §8).
 *
 * <p>{@link #BLOCKED} is an attempt that was refused before it could be made, and carries a
 * {@link BlockingReason}; the other four are what happened when it was.
 */
public enum ContactOutcome {
    CONNECTED,
    NO_ANSWER,
    DECLINED,
    VOICEMAIL,
    BLOCKED
}
