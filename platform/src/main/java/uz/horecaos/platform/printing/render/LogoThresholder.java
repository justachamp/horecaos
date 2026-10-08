package uz.horecaos.platform.printing.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * A brand's logo as one bit at a roll's width (ADR 0154, Decision 3): the picture scaled to fit, laid over
 * white paper, and every pixel darker than mid-grey ink.
 *
 * <p>Pure and deterministic: the same bytes and the same width give the same dots, which is what lets the
 * result be cached by asset id and width. A picture that is not an image the JDK reads, or is absurdly
 * large, is not a logo and answers empty -- the receipt prints without one rather than not at all.
 */
public final class LogoThresholder {

    /** The logo takes at most this share of the roll's width, and no more than this many dots of height. */
    static final double MAXIMUM_WIDTH_SHARE = 0.6;

    static final int MAXIMUM_HEIGHT_DOTS = 160;

    /** A source wider or taller than this is refused before it is decoded into the heap. */
    static final int MAXIMUM_SOURCE_PIXELS = 4_000_000;

    private LogoThresholder() {}

    public static Optional<Bitmap1> threshold(byte[] imageBytes, int rollWidthDots) {
        BufferedImage source;
        try {
            var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes));
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            var reader = readers.next();
            reader.setInput(stream);
            if ((long) reader.getWidth(0) * reader.getHeight(0) > MAXIMUM_SOURCE_PIXELS) {
                return Optional.empty();
            }
            source = reader.read(0);
            reader.dispose();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        if (source == null || source.getWidth() < 1 || source.getHeight() < 1) {
            return Optional.empty();
        }
        double scale = Math.min(
                rollWidthDots * MAXIMUM_WIDTH_SHARE / source.getWidth(),
                (double) MAXIMUM_HEIGHT_DOTS / source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = fitted.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return Optional.of(Bitmap1.ofImage(fitted));
    }
}
