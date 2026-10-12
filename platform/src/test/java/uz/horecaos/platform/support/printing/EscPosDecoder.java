package uz.horecaos.platform.support.printing;

import java.util.ArrayList;
import java.util.List;
import uz.horecaos.platform.printing.render.Bitmap1;

/**
 * Reads back the ESC/POS the platform hands an agent: what a printer would do with the bytes (ADR 0154).
 *
 * <p>A test fixture and not production code: the platform only ever writes these bytes. It understands
 * exactly the commands {@code EscPosEncoder} emits -- initialise, {@code GS v 0} raster bands, {@code
 * ESC d} and the cut -- and refuses anything else, so a framing change is a failing test and never a
 * silently different page. The bands are stacked into one bitmap, which is what the paper would show.
 */
public final class EscPosDecoder {

    /** What a printer made of a stream of bytes. */
    public record Printed(Bitmap1 bitmap, int bands, int feedLines, boolean cut, boolean initialised) {}

    private EscPosDecoder() {}

    public static Printed decode(byte[] bytes) {
        boolean initialised = false;
        boolean cut = false;
        int feed = 0;
        int widthBytes = -1;
        List<byte[]> rows = new ArrayList<>();
        int bands = 0;
        int index = 0;
        while (index < bytes.length) {
            int first = bytes[index] & 0xFF;
            int second = index + 1 < bytes.length ? bytes[index + 1] & 0xFF : -1;
            if (first == 0x1B && second == 0x40) {
                initialised = true;
                index += 2;
            } else if (first == 0x1B && second == 0x64) {
                feed += bytes[index + 2] & 0xFF;
                index += 3;
            } else if (first == 0x1D && second == 0x56) {
                // GS V m [n]: function 66 takes one more byte.
                int function = bytes[index + 2] & 0xFF;
                cut = true;
                index += function == 66 ? 4 : 3;
            } else if (first == 0x1D && second == 0x76 && (bytes[index + 2] & 0xFF) == 0x30) {
                int rowBytes = (bytes[index + 4] & 0xFF) | ((bytes[index + 5] & 0xFF) << 8);
                int height = (bytes[index + 6] & 0xFF) | ((bytes[index + 7] & 0xFF) << 8);
                if (widthBytes >= 0 && widthBytes != rowBytes) {
                    throw new IllegalStateException("The bands of one page have one width");
                }
                widthBytes = rowBytes;
                index += 8;
                for (int row = 0; row < height; row++) {
                    byte[] line = new byte[rowBytes];
                    System.arraycopy(bytes, index, line, 0, rowBytes);
                    rows.add(line);
                    index += rowBytes;
                }
                bands++;
            } else {
                throw new IllegalStateException(
                        "Unknown ESC/POS command at byte " + index + ": 0x" + Integer.toHexString(first));
            }
        }
        if (widthBytes < 0) {
            widthBytes = 1;
        }
        byte[] packed = new byte[widthBytes * rows.size()];
        for (int row = 0; row < rows.size(); row++) {
            System.arraycopy(rows.get(row), 0, packed, row * widthBytes, widthBytes);
        }
        return new Printed(Bitmap1.ofPacked(widthBytes * 8, rows.size(), packed), bands, feed, cut, initialised);
    }
}
