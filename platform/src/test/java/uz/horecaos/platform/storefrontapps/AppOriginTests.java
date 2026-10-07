package uz.horecaos.platform.storefrontapps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.horecaos.platform.storefrontapps.domain.AppOrigin;

/**
 * An origin is the only thing a public client is held to (ADR 0070), so what counts as the
 * same origin has to be exactly what a browser writes in its {@code Origin} header — no more
 * generous, which lets an impostor in, and no less, which locks the real app out.
 */
class AppOriginTests {

    @Test
    void anOriginIsComparedAsABrowserWritesIt() {
        assertThat(AppOrigin.parseAllowlistEntry("HTTPS://Shop.Example.UZ")).isEqualTo("https://shop.example.uz");
        assertThat(AppOrigin.parseAllowlistEntry("https://shop.example.uz:443")).isEqualTo("https://shop.example.uz");
        assertThat(AppOrigin.parseAllowlistEntry("https://shop.example.uz/")).isEqualTo("https://shop.example.uz");
        assertThat(AppOrigin.parseAllowlistEntry("https://shop.example.uz:8443"))
                .as("a non-default port is part of the origin")
                .isEqualTo("https://shop.example.uz:8443");
        assertThat(AppOrigin.parseAllowlistEntry("  https://shop.example.uz  ")).isEqualTo("https://shop.example.uz");
    }

    @Test
    void aSchemeOrPortThatDiffersIsADifferentOrigin() {
        assertThat(AppOrigin.parseAllowlistEntry("https://shop.example.uz"))
                .isNotEqualTo(AppOrigin.parseAllowlistEntry("https://shop.example.uz:8443"))
                .isNotEqualTo(AppOrigin.parseAllowlistEntry("https://www.shop.example.uz"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "shop.example.uz",
                "ftp://shop.example.uz",
                "https://",
                "https://shop.example.uz/menu",
                "https://shop.example.uz?x=1",
                "https://shop.example.uz#top",
                "https://user:pass@shop.example.uz",
                "https://*.example.uz",
                "http://shop.example.uz",
                "javascript:alert(1)"
            })
    void anEntryThatIsNotAnExactWebOriginIsRefused(String entry) {
        assertThatThrownBy(() -> AppOrigin.parseAllowlistEntry(entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"http://localhost:4200", "http://127.0.0.1:4200", "http://[::1]:4200", "http://shop.localhost"})
    void plainHttpIsForALoopbackDeveloperAndNobodyElse(String entry) {
        assertThat(AppOrigin.parseAllowlistEntry(entry)).startsWith("http://");
    }

    @Test
    void aRequestHeaderIsNeverTrustedEnoughToFailOn() {
        assertThat(AppOrigin.fromRequestValue(null)).isEmpty();
        assertThat(AppOrigin.fromRequestValue("")).isEmpty();
        assertThat(AppOrigin.fromRequestValue("null"))
                .as("what a sandboxed frame sends")
                .isEmpty();
        assertThat(AppOrigin.fromRequestValue("https://shop.example.uz/oops")).isEmpty();
        assertThat(AppOrigin.fromRequestValue("https://Shop.Example.uz")).contains("https://shop.example.uz");
    }

    @Test
    void aRefererContributesItsOriginAndNothingElse() {
        assertThat(AppOrigin.fromReferer("https://shop.example.uz/menu?table=4#top"))
                .isEqualTo(Optional.of("https://shop.example.uz"));
        assertThat(AppOrigin.fromReferer("https://shop.example.uz:8443/x")).contains("https://shop.example.uz:8443");
        assertThat(AppOrigin.fromReferer("not a url")).isEmpty();
        assertThat(AppOrigin.fromReferer(null)).isEmpty();
    }
}
