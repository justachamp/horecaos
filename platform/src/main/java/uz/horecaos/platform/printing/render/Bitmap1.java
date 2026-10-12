package uz.horecaos.platform.printing.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * A 1-bit picture: the one thing the renderer produces (ADR 0154, Decision 3).
 *
 * <p>Packed row by row, most significant bit first, a set bit meaning ink -- the layout ESC/POS
 * {@code GS v 0} takes, so the same bytes are the preview, the browser's image and the raster band of
 * the artifact. There is no native-text mode and no printer code page: a bitmap removes the question of
 * which glyphs a printer model can draw, and a wrong glyph on a fiscal evidence line is silent.
 */
public final class Bitmap1 {

    private final int width;
    private final int height;
    private final byte[] data;

    private Bitmap1(int width, int height, byte[] data) {
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public static Bitmap1 blank(int width, int height) {
        if (width < 1 || height < 0) {
            throw new IllegalArgumentException("A bitmap has a positive width and a non-negative height");
        }
        return new Bitmap1(width, height, new byte[rowBytes(width) * height]);
    }

    /** Wraps packed rows (the bytes of {@link #packed()}). */
    public static Bitmap1 ofPacked(int width, int height, byte[] packed) {
        if (packed.length != rowBytes(width) * height) {
            throw new IllegalArgumentException("A " + width + "x" + height + " bitmap is " + rowBytes(width) * height
                    + " bytes, not " + packed.length);
        }
        return new Bitmap1(width, height, packed.clone());
    }

    /** Thresholds a picture at mid-grey: dark pixels are ink. */
    public static Bitmap1 ofImage(BufferedImage image) {
        Bitmap1 bitmap = blank(image.getWidth(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                // Luma over white: a transparent pixel is paper, not ink.
                double luma =
                        (0.299 * red + 0.587 * green + 0.114 * blue) * alpha / 255.0 + 255.0 * (255 - alpha) / 255.0;
                if (luma < 128.0) {
                    bitmap.set(x, y);
                }
            }
        }
        return bitmap;
    }

    public static int rowBytes(int width) {
        return (width + 7) / 8;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int rowBytes() {
        return rowBytes(width);
    }

    public boolean get(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }
        return (data[y * rowBytes() + (x >> 3)] & (0x80 >> (x & 7))) != 0;
    }

    public void set(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        data[y * rowBytes() + (x >> 3)] |= (byte) (0x80 >> (x & 7));
    }

    /** The packed rows, as ESC/POS raster data. */
    public byte[] packed() {
        return data.clone();
    }

    /** How many dots are ink. A blank cell region and a drawn glyph differ by this. */
    public int inkCount() {
        int count = 0;
        for (byte value : data) {
            count += Integer.bitCount(value & 0xFF);
        }
        return count;
    }

    /** Ink inside the rectangle {@code [x, x+w) x [y, y+h)}. */
    public int inkIn(int x, int y, int w, int h) {
        int count = 0;
        for (int row = y; row < y + h; row++) {
            for (int column = x; column < x + w; column++) {
                if (get(column, row)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Black ink on white paper, for the console preview and for the PNG a browser prints. */
    public BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, Math.max(height, 1), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, y < height && get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }

    public byte[] toPng() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(toImage(), "png", out)) {
                throw new IllegalStateException("No PNG writer is available");
            }
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** One text row per dot row, {@code #} for ink: a bitmap a test failure can show. */
    public String toAscii() {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                text.append(get(x, y) ? '#' : '.');
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Bitmap1 that
                && width == that.width
                && height == that.height
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Bitmap1[" + width + "x" + height + ", ink " + inkCount() + "]";
    }
}
