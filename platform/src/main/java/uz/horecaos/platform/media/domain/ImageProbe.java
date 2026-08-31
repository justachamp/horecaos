package uz.horecaos.platform.media.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What the bytes are, read from the image's own header (ADR 0010).
 *
 * <p>This exists because everything else in the upload path is the client's word.
 * The presigned URL signs the content type, so the store refuses an upload whose
 * {@code Content-Type} header differs — but the header itself is chosen by the
 * same client that chose the bytes. {@code HeadObject} then reports that stored
 * header back, so "verified content type" taken from the store is the client's
 * claim with a round trip in the middle. A file of HTML uploaded as
 * {@code image/jpeg} satisfies every check that does not look inside it, and a
 * storefront that serves it from our own origin is serving stored cross-site
 * scripting.
 *
 * <p>Dimensions come from the header too, never from decoding. A ten-megabyte
 * PNG can declare a 50,000 by 50,000 raster that costs ten gigabytes of heap to
 * decompress, so the size limit on the upload is no limit at all on the memory a
 * decoder would need.
 *
 * <p><b>And so does the sample depth.</b> Dimensions alone do not bound the
 * decode either, which is the correction this class carries. The cost of a
 * decoded pixel is not one byte: PNG's {@code IHDR} carries a bit depth and a
 * colour type immediately after the dimensions, and between 8-bit greyscale and
 * 16-bit RGBA they span a factor of eight. Every parser below therefore reads
 * far enough to say what one pixel will cost, and nothing below decodes
 * anything — the whole point of a probe is that it answers from the header.
 */
public final class ImageProbe {

    /**
     * How much of the object has to be read to reach the header.
     *
     * <p>A JPEG's frame header sits after its metadata segments, and an EXIF
     * block alone may be 64KB, so the first few hundred bytes are not enough.
     * 128KB reaches the frame header of every ordinary photograph while keeping
     * the read bounded — the point of a ranged read is that a malicious ten
     * megabytes never enters the application at all.
     */
    public static final int PROBE_BYTES = 128 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private ImageProbe() {}

    /**
     * Reads an image's type and cost-relevant dimensions from its own header.
     *
     * @param prefix the leading bytes of the object; a short or empty prefix is
     *               an ordinary outcome and yields empty
     * @return empty when the bytes are not a supported image, which the caller
     *         must treat as a rejection rather than as "unknown but probably fine"
     */
    public static Optional<ProbedImage> probe(byte @Nullable [] prefix) {
        if (prefix == null || prefix.length < 16) {
            return Optional.empty();
        }
        if (startsWith(prefix, PNG_SIGNATURE)) {
            return png(prefix);
        }
        if ((prefix[0] & 0xFF) == 0xFF && (prefix[1] & 0xFF) == 0xD8) {
            return jpeg(prefix);
        }
        if (ascii(prefix, 0, "RIFF") && ascii(prefix, 8, "WEBP")) {
            return webp(prefix);
        }
        if (ascii(prefix, 4, "ftyp")) {
            return avif(prefix);
        }
        return Optional.empty();
    }

    /**
     * {@code IHDR}: dimensions, bit depth and colour type, in that order.
     *
     * <p>The chunk is mandatory and must be first, so its position is fixed
     * rather than searched for. Twenty-six bytes are needed and not
     * twenty-four: bytes 24 and 25 are the bit depth and the colour type, and a
     * guard that stopped at 24 both skipped them and failed to guarantee byte
     * 24 exists at all.
     */
    private static Optional<ProbedImage> png(byte[] data) {
        if (data.length < 26 || !ascii(data, 12, "IHDR")) {
            return Optional.empty();
        }
        int bitDepth = data[24] & 0xFF;
        int colourType = data[25] & 0xFF;

        // Channels after the decoder has expanded the format, not channels on
        // disk. Indexed colour is the one that differs: three bytes of palette
        // entry per pixel on screen, but the JDK's reader hands back an
        // IndexColorModel raster of one byte per pixel, which is what a
        // derivative render actually holds.
        int channels =
                switch (colourType) {
                    case 0 -> 1; // greyscale
                    case 2 -> 3; // truecolour
                    case 3 -> 1; // indexed
                    case 4 -> 2; // greyscale + alpha
                    case 6 -> 4; // truecolour + alpha
                    default -> -1;
                };
        if (channels < 0 || !isLegalPngDepth(colourType, bitDepth)) {
            // Not a header this parser understood. Refused rather than guessed
            // at, because a guess here is a guess about how much memory the
            // decoder will ask for.
            return Optional.empty();
        }
        // Depths below 8 pack several pixels into a byte, so one byte per pixel
        // over-counts them. Deliberately: this is a ceiling, and a ceiling that
        // errs upward on a 1-bit image costs nothing anybody will notice.
        int bytesPerSample = Math.max(1, bitDepth / 8);
        return probed("image/png", int32(data, 16), int32(data, 20), channels * bytesPerSample);
    }

    /** The depth/colour-type pairs PNG actually permits; anything else is not a PNG header. */
    private static boolean isLegalPngDepth(int colourType, int bitDepth) {
        return switch (colourType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            default -> false;
        };
    }

    /**
     * Walks the marker segments to the start-of-frame header.
     *
     * <p>Walking rather than searching for the marker bytes, because the two-byte
     * pattern occurs inside compressed data and inside embedded thumbnails; a
     * search finds the thumbnail's dimensions and calls them the image's.
     */
    private static Optional<ProbedImage> jpeg(byte[] data) {
        int pos = 2;
        while (pos + 3 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return Optional.empty();
            }
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                pos += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                // Entropy-coded data starts here. A frame header that has not
                // appeared by now is not going to.
                return Optional.empty();
            }
            int segmentLength = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (segmentLength < 2) {
                return Optional.empty();
            }
            if (isStartOfFrame(marker)) {
                // Nine bytes past the marker, not eight. A pixel costs the
                // component count times the sample precision, and the component
                // count is the ninth byte — one past where this used to stop.
                if (pos + 9 >= data.length) {
                    return Optional.empty();
                }
                int precisionBits = data[pos + 4] & 0xFF;
                int height = ((data[pos + 5] & 0xFF) << 8) | (data[pos + 6] & 0xFF);
                int width = ((data[pos + 7] & 0xFF) << 8) | (data[pos + 8] & 0xFF);
                int components = data[pos + 9] & 0xFF;
                if (precisionBits < 1 || precisionBits > 16 || components < 1 || components > 4) {
                    return Optional.empty();
                }
                int bytesPerPixel = components * ((precisionBits + 7) / 8);
                if (isProgressive(marker)) {
                    // A progressive scan cannot be decoded a block at a time, so
                    // the reader holds the whole coefficient array — two bytes
                    // per sample — alongside the output raster it is filling.
                    // Three times the baseline cost is the conservative reading
                    // of that, and being conservative is the entire job here.
                    bytesPerPixel *= 3;
                }
                return probed("image/jpeg", width, height, bytesPerPixel);
            }
            pos += 2 + segmentLength;
        }
        return Optional.empty();
    }

    /** SOF0 through SOF15, less the three markers that share the range but frame nothing. */
    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    /** SOF2, SOF6, SOF10, SOF14 — the four progressive frame types. */
    private static boolean isProgressive(int marker) {
        return marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE;
    }

    /**
     * WebP is 8 bits per sample in every one of its bitstreams.
     *
     * <p>So the depth needs no reading, and the only question is channels. Every
     * decoder in circulation hands back 8-bit RGBA for all three chunk types —
     * a lossy frame is subsampled YUV on the wire and full-resolution RGB(A) in
     * memory, which is the number that matters — so four bytes a pixel is both
     * the honest and the conservative answer.
     */
    private static Optional<ProbedImage> webp(byte[] data) {
        if (data.length < 30) {
            return Optional.empty();
        }
        int bytesPerPixel = 4;
        if (ascii(data, 12, "VP8 ")) {
            // The three-byte sync code is what separates a lossy frame header
            // from four bytes that merely spell the chunk name.
            if ((data[23] & 0xFF) != 0x9D || (data[24] & 0xFF) != 0x01 || (data[25] & 0xFF) != 0x2A) {
                return Optional.empty();
            }
            return probed("image/webp", little16(data, 26) & 0x3FFF, little16(data, 28) & 0x3FFF, bytesPerPixel);
        }
        if (ascii(data, 12, "VP8L")) {
            if ((data[20] & 0xFF) != 0x2F) {
                return Optional.empty();
            }
            int packed = little32(data, 21);
            return probed("image/webp", (packed & 0x3FFF) + 1, ((packed >>> 14) & 0x3FFF) + 1, bytesPerPixel);
        }
        if (ascii(data, 12, "VP8X")) {
            return probed("image/webp", little24(data, 24) + 1, little24(data, 27) + 1, bytesPerPixel);
        }
        return Optional.empty();
    }

    private static Optional<ProbedImage> avif(byte[] data) {
        if (!ascii(data, 8, "avif") && !ascii(data, 8, "avis")) {
            return Optional.empty();
        }
        // ispe carries the primary item's dimensions and is nested several boxes
        // deep inside meta/iprp/ipco. Locating it by name rather than walking the
        // container tree keeps this to a header read; a file whose ispe is not in
        // the probed prefix is rejected rather than guessed at.
        int ispe = indexOf(data, "ispe".getBytes(StandardCharsets.US_ASCII));
        if (ispe < 0 || ispe + 12 > data.length) {
            return Optional.empty();
        }
        return probed("image/avif", int32(data, ispe + 8), int32(data, ispe + 12), avifBytesPerPixel(data));
    }

    /**
     * AV1's bit depth and monochrome flag, from the {@code av1C} configuration box.
     *
     * <p>{@code ispe} carries no depth — it is dimensions and nothing else — so
     * a probe that stopped there would have exactly the hole {@code IHDR}'s
     * bytes 24 and 25 closed for PNG. {@code av1C} sits beside {@code ispe} in
     * {@code iprp/ipco} and its third byte packs {@code high_bitdepth},
     * {@code twelve_bit} and {@code monochrome}.
     *
     * <p>Absent from the probed prefix, the answer is the worst case rather than
     * a default. Alpha in AVIF is a separate auxiliary item, so a colour image
     * is counted at four channels whether or not one is present.
     */
    private static int avifBytesPerPixel(byte[] data) {
        int av1C = indexOf(data, "av1C".getBytes(StandardCharsets.US_ASCII));
        if (av1C < 0 || av1C + 7 > data.length) {
            return 8;
        }
        int flags = data[av1C + 6] & 0xFF;
        boolean highBitDepth = (flags & 0x40) != 0;
        boolean twelveBit = (flags & 0x20) != 0;
        boolean monochrome = (flags & 0x10) != 0;
        int bytesPerSample = (highBitDepth || twelveBit) ? 2 : 1;
        return (monochrome ? 1 : 4) * bytesPerSample;
    }

    /**
     * A zero or negative dimension means the header did not parse, not a
     * zero-pixel image; a non-positive cost means this parser failed to work
     * out what a pixel costs, which is not something to shrug at.
     */
    private static Optional<ProbedImage> probed(String contentType, int width, int height, int decodedBytesPerPixel) {
        if (width <= 0 || height <= 0 || decodedBytesPerPixel <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ProbedImage(contentType, width, height, decodedBytesPerPixel));
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] data, int offset, String expected) {
        if (offset + expected.length() > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if ((data[offset + i] & 0xFF) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int int32(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return -1;
        }
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int little16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int little24(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
    }

    private static int little32(byte[] data, int offset) {
        return little24(data, offset) | ((data[offset + 3] & 0xFF) << 24);
    }
}
