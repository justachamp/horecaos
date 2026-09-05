package uz.horecaos.platform.voice.domain;

/** Which side dialed. Screen-pop only ever fires for {@link #INBOUND}. */
public enum CallDirection {
    INBOUND,
    OUTBOUND
}
