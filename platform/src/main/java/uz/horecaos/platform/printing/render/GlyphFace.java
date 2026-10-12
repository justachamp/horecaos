package uz.horecaos.platform.printing.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vendored monospaced face the renderer draws with (ADR 0154, Decision 3), and what it does with a
 * character the face cannot draw.
 *
 * <p>The face is Fira Code 5.002 under the SIL Open Font License (see {@code
 * printing/fonts/README.md} for where the files came from). Its advance is 600/1000 em, so a font size
 * of 20 is exactly the 12-dot cell the layout grid is made of, and a size of 20 times the text's scale
 * is a text that fills that many cells. Each character is drawn on its own at its own cell, so the
 * face's programming ligatures never apply and no text is ever positioned by the font's advances.
 *
 * <p><strong>The known gap.</strong> The face has no U+02BB (the turned comma of Uzbek {@code oʻ} and
 * {@code gʻ}) and no U+02BC. They are drawn with its U+2018 and U+2019, which are the same shapes at this
 * size, and {@link #has} answers for the character the reader typed, so the glyph test proves what
 * reaches the paper rather than what the font file happens to hold.
 *
 * <p><strong>A missing glyph is never silent on evidence.</strong> {@link #sanitize} either throws (a
 * fiscal evidence line: a wrong character on a receipt is a defect nobody sees) or replaces the
 * character with {@code ?} (a customer's free-text note, which may hold an emoji and must not stop a
 * kitchen ticket printing).
 */
public final class GlyphFace {

    /** Characters drawn with another character's glyph. Empty the day the face has them natively. */
    static final Map<Integer, Integer> SUBSTITUTES = Map.of(0x02BB, 0x2018, 0x02BC, 0x2019);

    private static final String REGULAR = "/printing/fonts/FiraCode-Regular.ttf";
    private static final String BOLD = "/printing/fonts/FiraCode-Bold.ttf";

    /** The font size, in dots, at which one character is one 12-dot cell wide. */
    private static final float SIZE_PER_SCALE = 20f;

    /** Where the baseline sits in a 24-dot row: room for an ascender above and a descender below. */
    private static final int BASELINE_PER_SCALE = 18;

    private static final GlyphFace INSTANCE = new GlyphFace();

    private final Font regular;
    private final Font bold;
    private final Map<Integer, Font> derived = new ConcurrentHashMap<>();

    private GlyphFace() {
        // Before any AWT class initialises: a server has no display, and an AWT that tried to find one
        // would fail on the first font. Set again here because the JVM flag may not be.
        System.setProperty("java.awt.headless", "true");
        this.regular = load(REGULAR);
        this.bold = load(BOLD);
    }

    public static GlyphFace instance() {
        return INSTANCE;
    }

    private static Font load(String resource) {
        try (InputStream stream = GlyphFace.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("The receipt face is missing: " + resource);
            }
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (FontFormatException | IOException failure) {
            throw new IllegalStateException("The receipt face could not be loaded: " + resource, failure);
        }
    }

    /** The font that draws a run at {@code scale} times the normal size. */
    public Font font(boolean bold, int scale) {
        return derived.computeIfAbsent(
                (bold ? 1000 : 0) + scale, key -> (bold ? this.bold : this.regular).deriveFont(SIZE_PER_SCALE * scale));
    }

    /** The baseline of a row of {@code scale} times the normal height, measured from its top. */
    public int baseline(int scale) {
        return BASELINE_PER_SCALE * scale;
    }

    /** The code point whose glyph is drawn for {@code codePoint}. */
    public int drawnAs(int codePoint) {
        return SUBSTITUTES.getOrDefault(codePoint, codePoint);
    }

    /** Whether {@code codePoint} reaches the paper as a real glyph rather than a missing-glyph box. */
    public boolean has(int codePoint) {
        if (codePoint == ' ') {
            return true;
        }
        return regular.canDisplay(drawnAs(codePoint)) && bold.canDisplay(drawnAs(codePoint));
    }

    /**
     * The text with every character this face cannot draw replaced.
     *
     * @param strict throw on one (evidence) instead of printing {@code ?} (free text)
     */
    public String sanitize(String text, boolean strict) {
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                safe.append('\n');
            } else if (Character.isISOControl(codePoint)) {
                safe.append(' ');
            } else if (has(codePoint)) {
                safe.appendCodePoint(codePoint);
            } else if (strict) {
                throw new UnrenderableTextException(codePoint);
            } else {
                safe.append('?');
            }
        });
        return safe.toString();
    }

    /** Draws one character with its cell's left edge at {@code x} and the row's top at {@code top}. */
    void draw(Graphics2D graphics, int codePoint, boolean bold, int scale, int x, int top) {
        graphics.setFont(font(bold, scale));
        graphics.drawString(new String(Character.toChars(drawnAs(codePoint))), x, top + baseline(scale));
    }

    /** A character the face cannot draw, on a line that may not carry a wrong one. */
    public static final class UnrenderableTextException extends RuntimeException {
        private final int codePoint;

        public UnrenderableTextException(int codePoint) {
            super("The receipt face has no glyph for U+"
                    + Integer.toHexString(codePoint).toUpperCase());
            this.codePoint = codePoint;
        }

        public int codePoint() {
            return codePoint;
        }
    }
}
