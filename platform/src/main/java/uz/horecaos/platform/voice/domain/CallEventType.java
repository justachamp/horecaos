package uz.horecaos.platform.voice.domain;

/**
 * The normalized call-event vocabulary ADR 0064 settles on: offered, answered,
 * ended, missed, transferred. Every provider adapter — hosted SIP/PBX webhook
 * or Asterisk-class event-socket — translates its own wire shape into exactly
 * these five, and nothing downstream (screen-pop, presence, ADR 0043 facts,
 * call-to-order provenance) ever sees a provider-specific event name.
 */
public enum CallEventType {
    /** A call is ringing. This is the screen-pop trigger. */
    OFFERED,
    /** An operator picked up. */
    ANSWERED,
    /** The call is over, regardless of how it started. */
    ENDED,
    /** Offered, and nobody answered before it stopped ringing. */
    MISSED,
    /** Moved to another line or operator mid-call. */
    TRANSFERRED
}
