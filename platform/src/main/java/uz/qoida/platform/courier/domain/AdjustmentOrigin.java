package uz.qoida.platform.courier.domain;

/** Whether an entry came from a versioned rule, a person, or the platform. */
public enum AdjustmentOrigin {

    RULE,
    MANUAL,
    SYSTEM
}
