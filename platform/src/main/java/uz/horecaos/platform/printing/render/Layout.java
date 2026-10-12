package uz.horecaos.platform.printing.render;

import java.util.List;
import uz.horecaos.platform.printing.domain.TapeWidth;

/**
 * A document laid out on the cell grid, before any pixel is drawn (ADR 0154, Decision 3).
 *
 * <p>Layout is where every decision about wrapping, alignment and ordering is made, and it is
 * deterministic on every JVM -- unlike rasterised text, whose pixels depend on the font engine -- which
 * is why the rendering snapshots are snapshots of {@link #dump()} and the pixel tests assert only what
 * does not depend on the rasteriser (ink where a character is, a QR that decodes).
 *
 * @param rows in print order; {@code fiscal} marks the rows of the indivisible fiscal block
 */
public record Layout(TapeWidth tape, List<Row> rows) {

    public Layout {
        rows = List.copyOf(rows);
    }

    /** One band of the roll and whether it belongs to the fiscal block. */
    public record Row(Band band, boolean fiscal) {}

    public sealed interface Band permits TextBand, RuleBand, QrBand, ImageBand, GapBand, CutBand {
        /** Dots this band takes down the roll. */
        int heightDots();
    }

    /**
     * One line of text: already wrapped, already sanitised. {@code xCells} is the left margin in cells of
     * the band's own scale; each character occupies {@code scale} cells.
     */
    public record TextBand(String text, int scale, boolean bold, int xCells) implements Band {
        @Override
        public int heightDots() {
            return TapeWidth.CELL_HEIGHT_DOTS * scale;
        }
    }

    public record RuleBand(boolean heavy) implements Band {
        @Override
        public int heightDots() {
            return TapeWidth.CELL_WIDTH_DOTS;
        }
    }

    /** A QR code: {@code modules} drawn {@code moduleDots} square, with {@code quietModules} of paper around. */
    public record QrBand(boolean[][] modules, int moduleDots, int quietModules) implements Band {
        @Override
        public int heightDots() {
            return (modules.length + 2 * quietModules) * moduleDots;
        }

        public int sideDots() {
            return (modules.length + 2 * quietModules) * moduleDots;
        }
    }

    /** A picture already at the roll's resolution, centred. */
    public record ImageBand(Bitmap1 image) implements Band {
        @Override
        public int heightDots() {
            return image.height();
        }
    }

    /** Blank rows of the normal height. */
    public record GapBand(int rows) implements Band {
        @Override
        public int heightDots() {
            return TapeWidth.CELL_HEIGHT_DOTS * rows;
        }
    }

    /** Where the roll is cut; takes no paper of its own. */
    public record CutBand() implements Band {
        @Override
        public int heightDots() {
            return 0;
        }
    }

    public int heightDots() {
        return rows.stream().mapToInt(row -> row.band().heightDots()).sum();
    }

    /**
     * The layout as text, one line per band: the thing a rendering snapshot compares. A text band is its
     * characters at their cells ({@code [x2 B]} marks a scale and bold), a rule is dashes, a QR and a picture
     * are described, and a fiscal row starts with {@code F}.
     */
    public String dump() {
        int columns = tape.columns();
        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            text.append(row.fiscal() ? 'F' : ' ').append('|');
            switch (row.band()) {
                case TextBand band -> {
                    int cells = columns / band.scale();
                    String padded = " ".repeat(band.xCells()) + band.text();
                    text.append(String.format("%-" + cells + "s", padded));
                    text.append('|');
                    if (band.scale() > 1 || band.bold()) {
                        text.append(" [");
                        if (band.scale() > 1) {
                            text.append('x').append(band.scale());
                        }
                        if (band.scale() > 1 && band.bold()) {
                            text.append(' ');
                        }
                        if (band.bold()) {
                            text.append('B');
                        }
                        text.append(']');
                    }
                }
                case RuleBand band -> text.append((band.heavy() ? "=" : "-").repeat(columns));
                case QrBand band ->
                    text.append("[QR ")
                            .append(band.modules().length)
                            .append('x')
                            .append(band.modules().length)
                            .append(" modules, ")
                            .append(band.moduleDots())
                            .append(" dots each]");
                case ImageBand band ->
                    text.append("[IMAGE ")
                            .append(band.image().width())
                            .append('x')
                            .append(band.image().height())
                            .append(']');
                case GapBand band -> text.append("[GAP ").append(band.rows()).append(']');
                case CutBand band -> text.append("[CUT]");
            }
            text.append('\n');
        }
        return text.toString();
    }
}
