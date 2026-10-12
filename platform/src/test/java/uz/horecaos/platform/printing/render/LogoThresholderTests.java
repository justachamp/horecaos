package uz.horecaos.platform.printing.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A logo becomes one bit at the roll's width (ADR 0154, Decision 3). The pictures here are drawn in the test
 * so that the expected dots are known: a black square on white paper must come out as ink where the square is
 * and nowhere else, and a picture that is not one must come out as nothing -- never as an exception, since
 * the receipt prints without a logo rather than not at all.
 */
class LogoThresholderTests {

    private static byte[] png(int width, int height, Color background, Color square) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(background);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(square);
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        return write(image, "png");
    }

    private static byte[] write(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    @DisplayName("a dark square on white comes out as ink in the square and paper around it")
    void aSquareIsInkWhereItWas() {
        Bitmap1 logo = LogoThresholder.threshold(png(200, 200, Color.WHITE, Color.BLACK), 576)
                .orElseThrow();

        assertThat(logo.width()).isEqualTo(logo.height());
        assertThat(logo.inkIn(logo.width() / 4 + 2, logo.height() / 4 + 2, logo.width() / 2 - 4, logo.height() / 2 - 4))
                .as("the middle of the square is solid ink")
                .isEqualTo((logo.width() / 2 - 4) * (logo.height() / 2 - 4));
        assertThat(logo.inkIn(0, 0, logo.width(), 2))
                .as("the margin above is paper")
                .isZero();
        assertThat(logo.get(0, 0)).isFalse();
    }

    @Test
    @DisplayName("a transparent picture is laid over white paper, not over black")
    void transparencyIsPaper() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(40, 40, 20, 20);
        graphics.dispose();

        Bitmap1 logo = LogoThresholder.threshold(write(image, "png"), 384).orElseThrow();

        assertThat(logo.inkCount()).as("only the opaque square inks").isPositive();
        assertThat(logo.get(0, 0)).as("the transparent corner is paper").isFalse();
    }

    @Test
    @DisplayName("a light grey square is paper and a dark grey one is ink: the threshold is mid-grey")
    void theThresholdIsMidGrey() {
        Bitmap1 light = LogoThresholder.threshold(png(100, 100, Color.WHITE, new Color(200, 200, 200)), 384)
                .orElseThrow();
        Bitmap1 dark = LogoThresholder.threshold(png(100, 100, Color.WHITE, new Color(60, 60, 60)), 384)
                .orElseThrow();

        assertThat(light.inkCount()).isZero();
        assertThat(dark.inkCount()).isPositive();
    }

    @Test
    @DisplayName("the logo fits within the share of the roll and the height ceiling, at both widths")
    void aLogoIsBounded() {
        Bitmap1 wide58 = LogoThresholder.threshold(png(1000, 100, Color.WHITE, Color.BLACK), 384)
                .orElseThrow();
        Bitmap1 tall80 = LogoThresholder.threshold(png(100, 1000, Color.WHITE, Color.BLACK), 576)
                .orElseThrow();

        assertThat(wide58.width()).isLessThanOrEqualTo((int) Math.round(384 * LogoThresholder.MAXIMUM_WIDTH_SHARE));
        assertThat(tall80.height()).isLessThanOrEqualTo(LogoThresholder.MAXIMUM_HEIGHT_DOTS);
        assertThat(tall80.width()).isLessThan(576);
    }

    @Test
    @DisplayName("the same picture and width always give the same dots")
    void itIsDeterministic() {
        byte[] picture = png(173, 91, Color.WHITE, Color.BLACK);

        assertThat(LogoThresholder.threshold(picture, 384)).isEqualTo(LogoThresholder.threshold(picture, 384));
    }

    @Test
    @DisplayName("bytes that are not a picture, an empty file and an absurdly large picture are no logo")
    void notALogoIsNothing() {
        assertThat(LogoThresholder.threshold("not a picture".getBytes(), 384)).isEmpty();
        assertThat(LogoThresholder.threshold(new byte[0], 384)).isEmpty();
        // 2100 x 2100 is above the decode ceiling: refused from the header, before the pixels are allocated.
        BufferedImage large = new BufferedImage(2100, 2100, BufferedImage.TYPE_BYTE_BINARY);
        Optional<Bitmap1> refused = LogoThresholder.threshold(write(large, "png"), 384);
        assertThat(2100L * 2100L).isGreaterThan(LogoThresholder.MAXIMUM_SOURCE_PIXELS);
        assertThat(refused).isEmpty();
        // The control: just under the ceiling is read.
        assertThat(LogoThresholder.threshold(png(1900, 2000, Color.WHITE, Color.BLACK), 384))
                .isPresent();
    }
}
