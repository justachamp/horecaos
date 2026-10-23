package uz.horecaos.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.media.api.MediaPurpose;

/** The shape a KIOSK_IDLE picture answers to (ADR 0187, D25). */
class MediaPurposeTests {

    @Test
    @DisplayName("a kiosk's idle picture is 9:16 and at most 1 MiB")
    void theLimitsAreTheDecisions() {
        assertThat(MediaPurpose.KIOSK_IDLE.ratioWidth()).isEqualTo(9);
        assertThat(MediaPurpose.KIOSK_IDLE.ratioHeight()).isEqualTo(16);
        assertThat(MediaPurpose.KIOSK_IDLE.maximumBytes()).isEqualTo(1_048_576L);
    }

    @Test
    @DisplayName(
            "a kiosk's idle picture is a JPEG, PNG or WebP and nothing else, whatever the platform stores for other assets")
    void theTypesAreAClosedSet() {
        MediaPurpose purpose = MediaPurpose.KIOSK_IDLE;
        assertThat(purpose.allowsContentType("image/jpeg")).isTrue();
        assertThat(purpose.allowsContentType("image/png")).isTrue();
        assertThat(purpose.allowsContentType("image/webp")).isTrue();
        assertThat(purpose.allowsContentType(" IMAGE/JPEG "))
                .as("case and padding")
                .isTrue();
        assertThat(purpose.allowsContentType("image/avif")).isFalse();
        assertThat(purpose.allowsContentType("image/gif")).isFalse();
        assertThat(purpose.allowsContentType("image/svg+xml")).isFalse();
        assertThat(purpose.allowsContentType("video/mp4")).isFalse();
        assertThat(purpose.allowsContentType("")).isFalse();
        assertThat(purpose.allowsContentType(null)).isFalse();
    }

    @Test
    @DisplayName("the ratio holds within one percent either way and not beyond")
    void theRatioHasAOnePercentTolerance() {
        MediaPurpose purpose = MediaPurpose.KIOSK_IDLE;
        assertThat(purpose.matchesRatio(1080, 1920)).isTrue();
        assertThat(purpose.matchesRatio(720, 1280)).isTrue();
        assertThat(purpose.matchesRatio(541, 960))
                .as("a crop one pixel wide of the ratio")
                .isTrue();
        assertThat(purpose.matchesRatio(548, 960)).as("1.5 percent wide").isFalse();
        assertThat(purpose.matchesRatio(1920, 1080))
                .as("the landscape of the same picture")
                .isFalse();
        assertThat(purpose.matchesRatio(1000, 1000)).isFalse();
        assertThat(purpose.matchesRatio(0, 1920)).isFalse();
        assertThat(purpose.matchesRatio(1080, 0)).isFalse();
    }

    @Test
    @DisplayName("a column value names a purpose, and null or an unknown one names none")
    void aColumnValueIsReadBack() {
        assertThat(MediaPurpose.ofColumn("KIOSK_IDLE")).contains(MediaPurpose.KIOSK_IDLE);
        assertThat(MediaPurpose.ofColumn(" kiosk_idle ")).contains(MediaPurpose.KIOSK_IDLE);
        assertThat(MediaPurpose.ofColumn(null)).isEmpty();
        assertThat(MediaPurpose.ofColumn("")).isEmpty();
        assertThat(MediaPurpose.ofColumn("LOBBY_MUSIC")).isEmpty();
    }
}
