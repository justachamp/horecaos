package uz.horecaos.platform.voice.domain;

/**
 * An operator's availability (ADR 0064). Channel-neutral: nothing here names a
 * call, a queue, or a provider.
 */
public enum OperatorPresenceState {
    ONLINE,
    PAUSED,
    WRAP_UP,
    OFFLINE;

    /** A pause is the one state ADR 0027 needs a reason for beyond the ordinary user-actor rule. */
    public boolean requiresReason() {
        return this == PAUSED;
    }
}
