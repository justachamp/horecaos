package uz.horecaos.platform.printing.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.printing.render.Layout.GapBand;
import uz.horecaos.platform.printing.render.Layout.ImageBand;
import uz.horecaos.platform.printing.render.Layout.QrBand;
import uz.horecaos.platform.printing.render.Layout.RuleBand;
import uz.horecaos.platform.printing.render.Layout.TextBand;

/**
 * Draws a {@link Layout} to a 1-bit bitmap with {@code java.awt} in headless mode (ADR 0154, Decision 3).
 *
 * <p>Text is drawn one character at a time at its own cell, unantialiased and then thresholded, so the
 * result is ink or paper and nothing in between. Rules and QR modules are filled rectangles and a logo is
 * copied, so only text depends on the font engine -- which is why the tests assert text by the ink in its
 * cells and everything else exactly.
 */
public final class RasterRenderer {

    private static final int LIGHT_RULE_DOTS = 2;
    private static final int HEAVY_RULE_DOTS = 4;

    private final GlyphFace face;

    public RasterRenderer() {
        this(GlyphFace.instance());
    }

    RasterRenderer(GlyphFace face) {
        this.face = face;
    }

    public Bitmap1 render(Layout layout) {
        TapeWidth tape = layout.tape();
        int width = tape.dots();
        int height = layout.heightDots();
        BufferedImage image = new BufferedImage(width, Math.max(height, 1), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, image.getHeight());
            graphics.setColor(Color.BLACK);

            int top = 0;
            for (Layout.Row row : layout.rows()) {
                switch (row.band()) {
                    case TextBand band -> drawText(graphics, band, top);
                    case RuleBand band -> {
                        int thickness = band.heavy() ? HEAVY_RULE_DOTS : LIGHT_RULE_DOTS;
                        graphics.fillRect(0, top + (TapeWidth.CELL_WIDTH_DOTS - thickness) / 2, width, thickness);
                    }
                    case QrBand band -> drawQr(graphics, band, top, width);
                    case ImageBand band -> drawImage(graphics, band.image(), top, width);
                    case GapBand band -> {}
                    case Layout.CutBand band -> {}
                }
                top += row.band().heightDots();
            }
        } finally {
            graphics.dispose();
        }
        return threshold(image, width, height);
    }

    private void drawText(Graphics2D graphics, TextBand band, int top) {
        int cellDots = TapeWidth.CELL_WIDTH_DOTS * band.scale();
        int index = 0;
        for (int offset = 0; offset < band.text().length(); ) {
            int codePoint = band.text().codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint != ' ') {
                face.draw(graphics, codePoint, band.bold(), band.scale(), (band.xCells() + index) * cellDots, top);
            }
            index++;
        }
    }

    private void drawQr(Graphics2D graphics, QrBand band, int top, int width) {
        int left = (width - band.sideDots()) / 2;
        int origin = band.quietModules() * band.moduleDots();
        for (int y = 0; y < band.modules().length; y++) {
            for (int x = 0; x < band.modules()[y].length; x++) {
                if (band.modules()[y][x]) {
                    graphics.fillRect(
                            left + origin + x * band.moduleDots(),
                            top + origin + y * band.moduleDots(),
                            band.moduleDots(),
                            band.moduleDots());
                }
            }
        }
    }

    private void drawImage(Graphics2D graphics, Bitmap1 picture, int top, int width) {
        int left = (width - picture.width()) / 2;
        for (int y = 0; y < picture.height(); y++) {
            for (int x = 0; x < picture.width(); x++) {
                if (picture.get(x, y)) {
                    graphics.fillRect(left + x, top + y, 1, 1);
                }
            }
        }
    }

    private static Bitmap1 threshold(BufferedImage image, int width, int height) {
        Bitmap1 bitmap = Bitmap1.blank(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((image.getRaster().getSample(x, y, 0)) < 128) {
                    bitmap.set(x, y);
                }
            }
        }
        return bitmap;
    }
}
