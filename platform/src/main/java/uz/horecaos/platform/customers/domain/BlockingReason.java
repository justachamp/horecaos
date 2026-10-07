package uz.horecaos.platform.customers.domain;

/**
 * Why a voice contact attempt was refused (ADR 0111 §8): both journals -- the notification
 * dispatches and this one -- are attempts and both carry a reason when refused.
 */
public enum BlockingReason {
    BLACKLISTED,
    OUTSIDE_QUIET_HOURS,
    NO_CONSENT,
    WRONG_NUMBER
}
