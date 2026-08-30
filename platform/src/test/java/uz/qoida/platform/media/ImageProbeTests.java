package uz.qoida.platform.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.media.domain.ImageProbe;
import uz.qoida.platform.media.domain.ProbedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading an image's own header (ADR 0010).
 *
 * <p>Real encoder output for the two formats the JDK can write, hand-built
 * headers for the two it cannot. The hand-built ones are not a shortcut: they
 * pin the byte offsets this parser reads, which is exactly what a fixture
 * produced by the same code under test could not do.
 */
class ImageProbeTests {

    @Test
    @DisplayName("a real PNG reports its type and dimensions")
    void readsPng() throws IOException {
        ProbedImage probed = ImageProbe.probe(encode("png", 640, 360)).orElseThrow();

        assertThat(probed.contentType()).isEqualTo("image/png");
        assertThat(probed.widthPx()).isEqualTo(640);
        assertThat(probed.heightPx()).isEqualTo(360);
        // TYPE_INT_RGB is written as 8-bit truecolour, so three bytes a pixel.
        assertThat(probed.decodedBytesPerPixel()).isEqualTo(3);
        assertThat(probed.decodedBytes()).isEqualTo(640L * 360 * 3);
    }

    @Test
    @DisplayName("the same pixel count costs eight times as much at 16-bit RGBA")
    void readsPngSampleDepthAndChannels() {
        // The whole defect in one assertion. These two headers declare the same
        // 8000x5000 — the same forty megapixels, the same verdict from any limit
        // that counts pixels — and differ by the two bytes a probe that stopped
        // at byte 24 never read.
        ProbedImage eightBitRgb = ImageProbe.probe(pngHeader(8000, 5000, 8, 2)).orElseThrow();
        ProbedImage sixteenBitRgba = ImageProbe.probe(pngHeader(8000, 5000, 16, 6)).orElseThrow();

        assertThat(eightBitRgb.pixels()).isEqualTo(sixteenBitRgba.pixels());
        assertThat(eightBitRgb.decodedBytes()).isEqualTo(120_000_000L);
        assertThat(sixteenBitRgba.decodedBytes()).isEqualTo(320_000_000L);
    }

    @Test
    @DisplayName("every PNG colour type reports what its decoded pixel costs")
    void readsEveryPngColourType() {
        // Greyscale, truecolour, indexed, greyscale+alpha, truecolour+alpha —
        // one, three, one, two and four channels, times the sample size.
        assertThat(bytesPerPixel(pngHeader(10, 10, 8, 0))).isEqualTo(1);
        assertThat(bytesPerPixel(pngHeader(10, 10, 8, 2))).isEqualTo(3);
        assertThat(bytesPerPixel(pngHeader(10, 10, 8, 3))).isEqualTo(1);
        assertThat(bytesPerPixel(pngHeader(10, 10, 8, 4))).isEqualTo(2);
        assertThat(bytesPerPixel(pngHeader(10, 10, 8, 6))).isEqualTo(4);
        assertThat(bytesPerPixel(pngHeader(10, 10, 16, 0))).isEqualTo(2);
        assertThat(bytesPerPixel(pngHeader(10, 10, 16, 6))).isEqualTo(8);
        // Sub-byte depths pack, and are counted at a byte a pixel deliberately:
        // this is a ceiling, and erring upward on a 1-bit image costs nothing.
        assertThat(bytesPerPixel(pngHeader(10, 10, 1, 0))).isEqualTo(1);
    }

    @Test
    @DisplayName("a PNG header the spec does not permit is refused rather than guessed at")
    void rejectsIllegalPngHeaderCombinations() {
        // 16-bit indexed and 2-bit truecolour are not PNG. A parser that shrugged
        // and picked a number would be guessing at how much memory the decoder
        // is about to ask for.
        assertThat(ImageProbe.probe(pngHeader(10, 10, 16, 3))).isEmpty();
        assertThat(ImageProbe.probe(pngHeader(10, 10, 2, 2))).isEmpty();
        assertThat(ImageProbe.probe(pngHeader(10, 10, 8, 5))).isEmpty();
    }

    @Test
    @DisplayName("a PNG truncated after its dimensions is refused, not read as 8-bit")
    void rejectsPngTruncatedBeforeItsSampleDepth() {
        // Twenty-four bytes: signature, chunk length, IHDR, width, height, and
        // nothing else. The old guard was `length < 24`, which both admitted this
        // and did not guarantee byte 24 existed to be read.
        byte[] truncated = java.util.Arrays.copyOf(pngHeader(8000, 5000, 16, 6), 24);

        assertThat(ImageProbe.probe(truncated)).isEmpty();
    }

    @Test
    @DisplayName("a real JPEG reports its type and dimensions")
    void readsJpeg() throws IOException {
        ProbedImage probed = ImageProbe.probe(encode("jpg", 800, 600)).orElseThrow();

        assertThat(probed.contentType()).isEqualTo("image/jpeg");
        assertThat(probed.widthPx()).isEqualTo(800);
        assertThat(probed.heightPx()).isEqualTo(600);
        // Three components at 8-bit precision, both read from the frame header:
        // the component count is the byte after the dimensions, and the parser
        // used to stop one byte short of it.
        assertThat(probed.decodedBytesPerPixel()).isEqualTo(3);
    }

    @Test
    @DisplayName("a greyscale JPEG costs a third of what a colour one does")
    void readsJpegComponentCount() throws IOException {
        BufferedImage grey = new BufferedImage(800, 600, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(grey, "jpg", bytes);

        ProbedImage probed = ImageProbe.probe(bytes.toByteArray()).orElseThrow();

        assertThat(probed.decodedBytesPerPixel()).isEqualTo(1);
        assertThat(probed.decodedBytes()).isEqualTo(800L * 600);
    }

    @Test
    @DisplayName("the frame header is found past the metadata segments, not searched for")
    void walksPastMetadataSegments() throws IOException {
        // An APP1 segment carrying bytes that spell a start-of-frame marker. A
        // parser that scanned for 0xFFC0 would read this segment's contents as
        // the image's dimensions; walking the segment lengths steps over it.
        byte[] jpeg = encode("jpg", 300, 200);
        byte[] withExif = new byte[jpeg.length + 20];
        System.arraycopy(jpeg, 0, withExif, 0, 2);
        withExif[2] = (byte) 0xFF;
        withExif[3] = (byte) 0xE1;
        withExif[4] = 0x00;
        withExif[5] = 0x12;
        withExif[6] = (byte) 0xFF;
        withExif[7] = (byte) 0xC0;
        withExif[8] = 0x00;
        withExif[9] = 0x11;
        // The segment counts its own length field, so it spans indices 4..21 and
        // the original stream resumes at 22.
        System.arraycopy(jpeg, 2, withExif, 22, jpeg.length - 2);

        ProbedImage probed = ImageProbe.probe(withExif).orElseThrow();

        assertThat(probed.widthPx()).isEqualTo(300);
        assertThat(probed.heightPx()).isEqualTo(200);
    }

    @Test
    @DisplayName("a lossless WebP reports its packed dimensions")
    void readsLosslessWebp() {
        byte[] webp = new byte[32];
        write(webp, 0, "RIFF");
        write(webp, 8, "WEBP");
        write(webp, 12, "VP8L");
        webp[20] = 0x2F;
        // Width - 1 in the low fourteen bits, height - 1 in the next fourteen.
        int packed = (256 - 1) | ((128 - 1) << 14);
        webp[21] = (byte) packed;
        webp[22] = (byte) (packed >>> 8);
        webp[23] = (byte) (packed >>> 16);
        webp[24] = (byte) (packed >>> 24);

        ProbedImage probed = ImageProbe.probe(webp).orElseThrow();

        assertThat(probed.contentType()).isEqualTo("image/webp");
        assertThat(probed.widthPx()).isEqualTo(256);
        assertThat(probed.heightPx()).isEqualTo(128);
        // WebP is 8-bit throughout, and every decoder in circulation hands back
        // RGBA whatever the chunk on the wire was.
        assertThat(probed.decodedBytesPerPixel()).isEqualTo(4);
    }

    @Test
    @DisplayName("an AVIF reports the dimensions from its ispe box")
    void readsAvif() {
        byte[] avif = new byte[64];
        write(avif, 4, "ftyp");
        write(avif, 8, "avif");
        write(avif, 40, "ispe");
        writeInt(avif, 48, 1024);
        writeInt(avif, 52, 768);

        ProbedImage probed = ImageProbe.probe(avif).orElseThrow();

        assertThat(probed.contentType()).isEqualTo("image/avif");
        assertThat(probed.widthPx()).isEqualTo(1024);
        assertThat(probed.heightPx()).isEqualTo(768);
        // No av1C in the prefix, so the depth is unknown and the answer is the
        // worst case — 12-bit with alpha — rather than a comfortable default.
        assertThat(probed.decodedBytesPerPixel()).isEqualTo(8);
    }

    @Test
    @DisplayName("an AVIF reports its bit depth from av1C, which ispe does not carry")
    void readsAvifBitDepth() {
        // ispe is dimensions and nothing else, so an AVIF probe that stopped
        // there would have exactly the hole IHDR's bytes 24 and 25 closed.
        assertThat(bytesPerPixel(avifWith(0x00))).isEqualTo(4);          // 8-bit colour
        assertThat(bytesPerPixel(avifWith(0x40))).isEqualTo(8);          // 10-bit colour
        assertThat(bytesPerPixel(avifWith(0x60))).isEqualTo(8);          // 12-bit colour
        assertThat(bytesPerPixel(avifWith(0x10))).isEqualTo(1);          // 8-bit monochrome
        assertThat(bytesPerPixel(avifWith(0x50))).isEqualTo(2);          // 10-bit monochrome
    }

    @Test
    @DisplayName("HTML is not an image however it is labelled")
    void rejectsHtml() {
        byte[] html = "<html><script>fetch('https://evil.example')</script></html>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(ImageProbe.probe(html)).isEmpty();
    }

    @Test
    @DisplayName("an SVG is not an image header, whatever it claims to be")
    void rejectsSvg() {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(ImageProbe.probe(svg)).isEmpty();
    }

    @Test
    @DisplayName("a PNG signature with nothing behind it is not accepted")
    void rejectsTruncatedPng() {
        // The signature is eight bytes anyone can copy. Without a parseable IHDR
        // there is nothing to serve, so a header-only file is not an image.
        byte[] truncated = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R'};

        assertThat(ImageProbe.probe(truncated)).isEmpty();
    }

    @Test
    @DisplayName("an empty or absent prefix yields nothing rather than a guess")
    void rejectsNothing() {
        assertThat(ImageProbe.probe(new byte[0])).isEmpty();
        assertThat(ImageProbe.probe(null)).isEmpty();
    }

    @Test
    @DisplayName("a header declaring a decompression bomb is read without decoding it")
    void readsBombDimensionsWithoutDecoding() {
        // 50,000 square at 16-bit RGBA is twenty gigabytes of raster. The point
        // of reading IHDR rather than decoding is that this costs twenty-six
        // bytes to discover.
        ProbedImage probed = ImageProbe.probe(pngHeader(50_000, 50_000, 16, 6)).orElseThrow();

        assertThat(probed.pixels()).isEqualTo(2_500_000_000L);
        assertThat(probed.decodedBytes()).isEqualTo(20_000_000_000L);
    }

    @Test
    @DisplayName("a cost too large for a long saturates rather than wrapping negative")
    void saturatesRatherThanWrapping() {
        // Two dimensions near Integer.MAX_VALUE multiply past what a long holds.
        // Wrapped, the product is negative and reads as comfortably under every
        // budget, which is the one arithmetic mistake a ceiling cannot survive.
        ProbedImage absurd = new ProbedImage("image/png", 2_000_000_000, 2_000_000_000, 8);

        assertThat(absurd.decodedBytes()).isEqualTo(Long.MAX_VALUE);
    }

    /** A signature and an IHDR: the twenty-six bytes every cost decision is made from. */
    private static byte[] pngHeader(int width, int height, int bitDepth, int colourType) {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R',
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        writeInt(png, 16, width);
        writeInt(png, 20, height);
        png[24] = (byte) bitDepth;
        png[25] = (byte) colourType;
        return png;
    }

    /** An ispe for the dimensions and an av1C whose third payload byte packs the depth flags. */
    private static byte[] avifWith(int av1cFlags) {
        byte[] avif = new byte[80];
        write(avif, 4, "ftyp");
        write(avif, 8, "avif");
        write(avif, 40, "ispe");
        writeInt(avif, 48, 1024);
        writeInt(avif, 52, 768);
        write(avif, 60, "av1C");
        avif[64] = (byte) 0x81;              // marker and version
        avif[65] = 0x00;                     // seq_profile and level
        avif[66] = (byte) av1cFlags;         // tier, high_bitdepth, twelve_bit, monochrome
        return avif;
    }

    private static int bytesPerPixel(byte[] header) {
        return ImageProbe.probe(header).orElseThrow().decodedBytesPerPixel();
    }

    private static byte[] encode(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    private static void write(byte[] target, int offset, String ascii) {
        byte[] source = ascii.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(source, 0, target, offset, source.length);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
