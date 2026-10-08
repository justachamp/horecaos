package uz.horecaos.platform.iam.application.sessions;

/**
 * What the platform keeps about the device a staff member signed in from (ADR 0176 Decision 3):
 * three small closed sets, and nothing else.
 *
 * <p>The raw {@code User-Agent}, the address, a network prefix, a position and any device
 * identifier are not stored, logged or put on an event. A label is coarse on purpose -- two
 * Chrome-on-Windows sessions from different offices look the same, and the person is told when
 * each was last active and decides.
 */
public record DeviceLabel(Browser browser, Os os, Form form) {

    /** The label of a header the parser could not place. */
    public static final DeviceLabel UNKNOWN = new DeviceLabel(Browser.OTHER, Os.OTHER, Form.DESKTOP);

    public enum Browser {
        CHROME,
        SAFARI,
        FIREFOX,
        EDGE,
        YANDEX,
        SAMSUNG,
        OTHER
    }

    public enum Os {
        WINDOWS,
        MACOS,
        LINUX,
        ANDROID,
        IOS,
        CHROMEOS,
        OTHER
    }

    public enum Form {
        DESKTOP,
        PHONE,
        TABLET
    }
}
