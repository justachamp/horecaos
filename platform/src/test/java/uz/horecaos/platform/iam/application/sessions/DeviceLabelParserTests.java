package uz.horecaos.platform.iam.application.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel.Browser;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel.Form;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel.Os;

/** ADR 0176 Decision 3: real header strings to the three closed labels, and no exception on a hostile one. */
class DeviceLabelParserTests {

    private final DeviceLabelParser parser = new DeviceLabelParser();

    @ParameterizedTest(name = "{0}")
    @DisplayName("a real header maps to its browser, system and form")
    @CsvSource(delimiter = '|', textBlock = """
                    Chrome on Windows | Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 | CHROME | WINDOWS | DESKTOP
                    Edge on Windows | Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0 | EDGE | WINDOWS | DESKTOP
                    Firefox on Linux | Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0 | FIREFOX | LINUX | DESKTOP
                    Safari on Mac | Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15 | SAFARI | MACOS | DESKTOP
                    Safari on iPhone | Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1 | SAFARI | IOS | PHONE
                    Safari on iPad | Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1 | SAFARI | IOS | TABLET
                    Chrome on iPhone | Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.153 Mobile/15E148 Safari/604.1 | CHROME | IOS | PHONE
                    Chrome on Android phone | Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 | CHROME | ANDROID | PHONE
                    Chrome on Android tablet | Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 | CHROME | ANDROID | TABLET
                    Samsung Internet | Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36 | SAMSUNG | ANDROID | PHONE
                    Yandex Browser | Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 YaBrowser/24.6.0 Yowser/2.5 Safari/537.36 | YANDEX | WINDOWS | DESKTOP
                    Chrome on ChromeOS | Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 | CHROME | CHROMEOS | DESKTOP
                    Opera is not one of the families | Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 OPR/111.0.0.0 | OTHER | WINDOWS | DESKTOP
                    """)
    void aRealHeader(String name, String header, Browser browser, Os os, Form form) {
        assertThat(parser.parse(header)).isEqualTo(new DeviceLabel(browser, os, form));
    }

    @Test
    @DisplayName("a missing, blank or unrecognised header is OTHER and OTHER on a desktop")
    void anUnplaceableHeader() {
        assertThat(parser.parse(null)).isEqualTo(DeviceLabel.UNKNOWN);
        assertThat(parser.parse("")).isEqualTo(DeviceLabel.UNKNOWN);
        assertThat(parser.parse("   ")).isEqualTo(DeviceLabel.UNKNOWN);
        assertThat(parser.parse("curl/8.7.1")).isEqualTo(DeviceLabel.UNKNOWN);
    }

    @Test
    @DisplayName("an oversized or control-character header does not throw, and only its first characters are read")
    void aHostileHeader() {
        String huge = "x".repeat(1_000_000) + " Chrome/126 Windows";
        String controls = "Mozilla/5.0 \u0000\u0001\u001b[31m (Windows NT 10.0) \r\n Chrome/126";

        assertThatCode(() -> parser.parse(huge)).doesNotThrowAnyException();
        assertThatCode(() -> parser.parse(controls)).doesNotThrowAnyException();
        assertThat(parser.parse(huge))
                .as("what lies past the inspected prefix is never read")
                .isEqualTo(DeviceLabel.UNKNOWN);
        assertThat(parser.parse(controls).browser()).isEqualTo(Browser.CHROME);
    }
}
