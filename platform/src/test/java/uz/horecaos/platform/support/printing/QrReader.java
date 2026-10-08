package uz.horecaos.platform.support.printing;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.printing.render.Bitmap1;

/**
 * The test-only ZXing reader (ADR 0154, Testing): the text of the QR on a bitmap, from the bitmap alone.
 *
 * <p>ZXing's {@code javase} artifact would supply a luminance source for a {@code BufferedImage}; the
 * platform depends on {@code core} only, so this is the twenty lines that read a {@link Bitmap1}
 * directly. The point of a decoder in a test is that "the QR is the stored URL" is proved by something
 * that did not produce it.
 */
public final class QrReader {

    private QrReader() {}

    /** The QR's text, or empty when the bitmap holds no readable QR. */
    public static Optional<String> read(Bitmap1 bitmap) {
        try {
            return Optional.of(new QRCodeReader()
                    .decode(
                            new BinaryBitmap(new HybridBinarizer(new BitmapSource(bitmap))),
                            Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE))
                    .getText());
        } catch (NotFoundException | com.google.zxing.ChecksumException | com.google.zxing.FormatException unreadable) {
            return Optional.empty();
        }
    }

    private static final class BitmapSource extends LuminanceSource {
        private final Bitmap1 bitmap;

        BitmapSource(Bitmap1 bitmap) {
            super(bitmap.width(), bitmap.height());
            this.bitmap = bitmap;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            byte[] luminance = row == null || row.length < getWidth() ? new byte[getWidth()] : row;
            for (int x = 0; x < getWidth(); x++) {
                luminance[x] = (byte) (bitmap.get(x, y) ? 0 : 255);
            }
            return luminance;
        }

        @Override
        public byte[] getMatrix() {
            byte[] matrix = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                for (int x = 0; x < getWidth(); x++) {
                    matrix[y * getWidth() + x] = (byte) (bitmap.get(x, y) ? 0 : 255);
                }
            }
            return matrix;
        }
    }
}
