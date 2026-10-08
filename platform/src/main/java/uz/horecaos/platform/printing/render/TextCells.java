package uz.horecaos.platform.printing.render;

import java.util.ArrayList;
import java.util.List;
import uz.horecaos.platform.printing.domain.PrintDocument;

/**
 * Text measured and wrapped in cells, never in pixels (ADR 0154, Decision 3).
 *
 * <p>A cell is a code point. Wrapping breaks on spaces where it can and in the middle of a word where it
 * must, so a 48-character receipt reference on a 32-column roll still prints, on two lines, rather than
 * being cut.
 */
public final class TextCells {

    private TextCells() {}

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** {@code text} cut into lines of at most {@code width} cells. An empty text is one empty line. */
    public static List<String> wrap(String text, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("A line has at least one cell");
        }
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            wrapParagraph(paragraph, width, lines);
        }
        return lines;
    }

    private static void wrapParagraph(String paragraph, int width, List<String> lines) {
        String remaining = paragraph.strip();
        if (remaining.isEmpty()) {
            lines.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        int lineLength = 0;
        for (String word : remaining.split("\\s+")) {
            int wordLength = length(word);
            int needed = lineLength == 0 ? wordLength : lineLength + 1 + wordLength;
            if (needed <= width) {
                if (lineLength > 0) {
                    line.append(' ');
                }
                line.append(word);
                lineLength = needed;
                continue;
            }
            if (lineLength > 0) {
                lines.add(line.toString());
                line.setLength(0);
                lineLength = 0;
            }
            // A word longer than a whole line is broken where the line ends.
            int[] points = word.codePoints().toArray();
            int index = 0;
            while (points.length - index > width) {
                lines.add(new String(points, index, width));
                index += width;
            }
            line.append(new String(points, index, points.length - index));
            lineLength = points.length - index;
        }
        if (lineLength > 0) {
            lines.add(line.toString());
        }
    }

    /** {@code text} padded on the left so it sits in a line of {@code width} cells. */
    public static String align(String text, int width, PrintDocument.Align align) {
        int length = length(text);
        if (length >= width) {
            return text;
        }
        return " ".repeat(offset(length, width, align)) + text;
    }

    /** The cells of left margin that put {@code length} cells at {@code align} in a line of {@code width}. */
    public static int offset(int length, int width, PrintDocument.Align align) {
        if (length >= width) {
            return 0;
        }
        return switch (align) {
            case LEFT -> 0;
            case CENTER -> (width - length) / 2;
            case RIGHT -> width - length;
        };
    }
}
