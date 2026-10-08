package uz.horecaos.platform.iam.api.terminals;

/**
 * A tenant's five terminal policy values, resolved (ADR 0155, Decision 7). Tenant-scope values: the only tenant write
 * path is Staff → Терминалы → Policy, and the capability is the control.
 *
 * @param pinLength        4 or 6: the length the <em>next</em> PIN set must have; an existing PIN keeps its own
 * @param autoLockSeconds  the idle limit when a device has no override of its own, 30 to 3,600
 * @param sessionMaxHours  a session ends after this many hours whatever happens, 1 to 16
 * @param rosterDisplay    how a colleague's name reads on a shared screen
 * @param attributionDefault the mode a newly approved device starts in
 */
public record TerminalPolicy(
        int pinLength,
        int autoLockSeconds,
        int sessionMaxHours,
        RosterDisplay rosterDisplay,
        AttributionMode attributionDefault) {

    /** First name and the initial of the last name, or the non-personal {@code S-0142}. Never a phone, photo or number. */
    public enum RosterDisplay {
        NAME_INITIAL,
        REFERENCE
    }

    /** How a person's unlock attributes an action taken at the screen. */
    public enum AttributionMode {
        DEVICE_ONLY,
        PERSON_OPTIONAL,
        PERSON_REQUIRED
    }

    public static final int DEFAULT_PIN_LENGTH = 6;
    public static final int DEFAULT_AUTO_LOCK_SECONDS = 300;
    public static final int DEFAULT_SESSION_MAX_HOURS = 12;

    /** The platform's own values, for code wired without a tenancy module. */
    public static TerminalPolicy defaults() {
        return new TerminalPolicy(
                DEFAULT_PIN_LENGTH,
                DEFAULT_AUTO_LOCK_SECONDS,
                DEFAULT_SESSION_MAX_HOURS,
                RosterDisplay.NAME_INITIAL,
                AttributionMode.DEVICE_ONLY);
    }
}
