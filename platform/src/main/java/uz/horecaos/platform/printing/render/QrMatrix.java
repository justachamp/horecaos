package uz.horecaos.platform.printing.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The modules of a QR code (ADR 0154, Decision 5), encoded by ZXing core.
 *
 * <p>The renderer draws the modules as filled squares and never through the font, so a QR is exact on
 * every JVM and every machine. Error correction level M: a thermal head smudges, and a phone has to read
 * a receipt that has been folded into a pocket, but a URL of a hundred characters at level H would no
 * longer fit a 58 mm roll at a scale a phone can read.
 */
public final class QrMatrix {

    private QrMatrix() {}

    /** {@code true} is a dark module. Square, with no quiet zone: the layout adds that. */
    public static boolean[][] encode(String data) {
        try {
            BitMatrix matrix = new QRCodeWriter()
                    .encode(
                            data,
                            BarcodeFormat.QR_CODE,
                            1,
                            1,
                            Map.of(
                                    EncodeHintType.ERROR_CORRECTION,
                                    ErrorCorrectionLevel.M,
                                    EncodeHintType.CHARACTER_SET,
                                    StandardCharsets.UTF_8.name(),
                                    EncodeHintType.MARGIN,
                                    0));
            boolean[][] modules = new boolean[matrix.getHeight()][matrix.getWidth()];
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    modules[y][x] = matrix.get(x, y);
                }
            }
            return modules;
        } catch (WriterException failure) {
            throw new IllegalArgumentException("That text does not fit a QR code", failure);
        }
    }
}
