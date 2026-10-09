package uz.horecaos.platform.iam.application.sessions;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel.Browser;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel.Form;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel.Os;

/**
 * Maps a {@code User-Agent} header to a {@link DeviceLabel} by a short allow-list (ADR 0176
 * Decision 3).
 *
 * <p>Total: any string, including an oversized or hostile one, yields a label and never an
 * exception, and an unrecognised header is {@code OTHER}. The input is bounded before anything
 * reads it, and the header is not returned, logged or kept -- the label is the only thing that
 * leaves this class. Order of the checks is the design: the vendors that wear another vendor's
 * token (Edge, Opera, Yandex and Samsung all say {@code Chrome/}; Chrome says {@code Safari/})
 * are asked first.
 */
@Component
public class DeviceLabelParser {

    /** A real header is a few hundred characters; past this it is not one. */
    static final int MAXIMUM_INSPECTED_CHARACTERS = 512;

    public DeviceLabel parse(@Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DeviceLabel.UNKNOWN;
        }
        String header = userAgent.length() > MAXIMUM_INSPECTED_CHARACTERS
                ? userAgent.substring(0, MAXIMUM_INSPECTED_CHARACTERS)
                : userAgent;
        String lower = header.toLowerCase(Locale.ROOT);
        return new DeviceLabel(browser(lower), os(lower), form(lower));
    }

    private static Browser browser(String h) {
        if (h.contains("edga/") || h.contains("edgios/") || h.contains("edg/") || h.contains("edge/")) {
            return Browser.EDGE;
        }
        if (h.contains("yabrowser/")) {
            return Browser.YANDEX;
        }
        if (h.contains("samsungbrowser/")) {
            return Browser.SAMSUNG;
        }
        if (h.contains("opr/") || h.contains("opera") || h.contains("opt/")) {
            return Browser.OTHER;
        }
        if (h.contains("firefox/") || h.contains("fxios/")) {
            return Browser.FIREFOX;
        }
        if (h.contains("chrome/") || h.contains("crios/") || h.contains("chromium/")) {
            return Browser.CHROME;
        }
        if (h.contains("safari/") || h.contains("version/")) {
            return Browser.SAFARI;
        }
        return Browser.OTHER;
    }

    private static Os os(String h) {
        if (h.contains("windows")) {
            return Os.WINDOWS;
        }
        if (h.contains("android")) {
            return Os.ANDROID;
        }
        if (h.contains("iphone") || h.contains("ipad") || h.contains("ipod")) {
            return Os.IOS;
        }
        if (h.contains("cros")) {
            return Os.CHROMEOS;
        }
        if (h.contains("macintosh") || h.contains("mac os x")) {
            return Os.MACOS;
        }
        if (h.contains("linux") || h.contains("x11")) {
            return Os.LINUX;
        }
        return Os.OTHER;
    }

    private static Form form(String h) {
        if (h.contains("ipad") || h.contains("tablet") || (h.contains("android") && !h.contains("mobile"))) {
            return Form.TABLET;
        }
        if (h.contains("mobile") || h.contains("iphone") || h.contains("ipod")) {
            return Form.PHONE;
        }
        return Form.DESKTOP;
    }
}
