package uz.horecaos.platform.printing.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * A thermal roll: its physical width and the dots a printer head has across it (ADR 0154, Decision 3).
 *
 * <p>Text is laid out on a fixed grid of 12-dot cells, so a 58 mm and an 80 mm roll differ only in the
 * number of columns: 32 on 384 dots, 48 on 576.
 */
public enum TapeWidth {
    MM_58(58, 384),
    MM_80(80, 576);

    /** Dots across one character cell. */
    public static final int CELL_WIDTH_DOTS = 12;

    /** Dots down one character row at the normal size. */
    public static final int CELL_HEIGHT_DOTS = 24;

    private final int millimetres;
    private final int dots;

    TapeWidth(int millimetres, int dots) {
        this.millimetres = millimetres;
        this.dots = dots;
    }

    public int millimetres() {
        return millimetres;
    }

    public int dots() {
        return dots;
    }

    public int columns() {
        return dots / CELL_WIDTH_DOTS;
    }

    public static Optional<TapeWidth> ofMillimetres(int millimetres) {
        return Arrays.stream(values())
                .filter(width -> width.millimetres == millimetres)
                .findFirst();
    }

    public static TapeWidth require(int millimetres) {
        return ofMillimetres(millimetres)
                .orElseThrow(() -> new IllegalArgumentException("A roll is 58 or 80 mm wide, not " + millimetres));
    }
}
