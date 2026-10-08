package uz.horecaos.platform.printing.render;

import java.io.ByteArrayOutputStream;

/**
 * Frames a bitmap as ESC/POS (ADR 0154, Decision 3): the raster band of the bytes the platform hands an
 * agent, and nothing else. There is no native-text mode and no code page, so the same bitmap is the
 * preview, the image a browser prints and the paper.
 *
 * <p>{@code ESC @} to initialise, then {@code GS v 0} in bands (a set bit prints), then {@code ESC d} to
 * feed the last band clear of the head and {@code GS V 66 0} to feed to the cutter and cut. The agent
 * writes these bytes to the printer and reads its status; it holds no template and renders nothing.
 */
public final class EscPosEncoder {

    /** Rows per {@code GS v 0} command. Small printers buffer little; 128 rows of 80 mm is 9 KB. */
    static final int BAND_ROWS = 128;

    /** Lines fed after the last band, so the cut does not slice the last text row. */
    static final int FEED_BEFORE_CUT = 4;

    /** The protocol tag a job records, so a reprint explains itself and a future framing is a new value. */
    public static final String PROTOCOL = "ESCPOS_RASTER_1";

    public byte[] encode(Bitmap1 bitmap, boolean cut) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bitmap.rowBytes() * bitmap.height() + 64);
        out.write(0x1B);
        out.write(0x40);
        byte[] packed = bitmap.packed();
        int rowBytes = bitmap.rowBytes();
        for (int top = 0; top < bitmap.height(); top += BAND_ROWS) {
            int rows = Math.min(BAND_ROWS, bitmap.height() - top);
            out.write(0x1D);
            out.write(0x76);
            out.write(0x30);
            out.write(0x00);
            out.write(rowBytes & 0xFF);
            out.write((rowBytes >> 8) & 0xFF);
            out.write(rows & 0xFF);
            out.write((rows >> 8) & 0xFF);
            out.write(packed, top * rowBytes, rows * rowBytes);
        }
        out.write(0x1B);
        out.write(0x64);
        out.write(FEED_BEFORE_CUT);
        if (cut) {
            out.write(0x1D);
            out.write(0x56);
            out.write(0x42);
            out.write(0x00);
        }
        return out.toByteArray();
    }
}
