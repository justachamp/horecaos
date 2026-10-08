package uz.horecaos.platform.iam.application.sessions;

/**
 * Why the platform believes a recorded session ended (ADR 0176). A closed list, never typed by a
 * person; it is the {@code end_reason} column's check constraint, one for one.
 */
public enum EndReason {
    /** The person signed out of this device. */
    SIGNED_OUT,
    /** The person ended every session of the account from the sessions card. */
    SIGNED_OUT_EVERYWHERE,
    /** A completed password reset ended them (ADR 0098), recorded only when Keycloak confirmed it. */
    PASSWORD_RESET,
    /** The person ended this one session from the list. */
    ENDED_ONE,
    /** Keycloak no longer lists it and the idle window passed. */
    EXPIRED
}
