package uz.horecaos.platform.media.domain;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads the header of an MP4 and decides whether the platform will serve it (ADR 0157, Decision 8).
 *
 * <p>Pure Java, bounded, and it <strong>never decodes</strong>. The platform does not transcode: a
 * transcoder is a native decoder run on hostile input on the one 8 vCPU machine (ADR 0073), and a
 * managed service or an embedded player is a vendor nobody can pay from Uzbekistan today. So the
 * rule is the cheap one: accept an MP4 whose own header says it is the shape every target webview
 * plays, refuse everything else with a named code and a sentence that tells the operator how to
 * export a compliant file. The customer's own decoder is the exposure, the same as any
 * tenant-uploaded video, reduced by accepting H.264-in-MP4 only and an authenticated uploader.
 *
 * <p>What it reads is the first {@link #PROBE_BYTES} of the object, and no more: {@code moov} must
 * be wholly inside them, which is also what makes the file start playing before it has finished
 * loading ("fast start"). A {@code moov} at the end of the file, which is where a phone's camera app
 * leaves it, is refused for that reason alone.
 *
 * <table>
 *   <caption>What is accepted, and the code that refuses what is not</caption>
 *   <tr><td>Container</td><td>MP4, {@code ftyp} major brand isom, iso2, mp41, mp42, avc1 or {@code M4V }</td><td>VIDEO_TYPE_NOT_ALLOWED</td></tr>
 *   <tr><td>Video track</td><td>one, sample entry {@code avc1}; HEVC, VP9 and AV1 are refused</td><td>VIDEO_CODEC_NOT_ACCEPTED</td></tr>
 *   <tr><td>Other tracks</td><td>at most one audio track ({@code mp4a}); no subtitle or data track</td><td>VIDEO_MALFORMED</td></tr>
 *   <tr><td>Layout</td><td>{@code moov} before {@code mdat}, inside the first 256 KiB</td><td>VIDEO_NOT_FASTSTART</td></tr>
 *   <tr><td>Duration</td><td>at most 20 s, from {@code mvhd}</td><td>VIDEO_TOO_LONG</td></tr>
 *   <tr><td>Size</td><td>at most 12 MiB</td><td>VIDEO_TOO_LARGE</td></tr>
 *   <tr><td>Dimensions</td><td>long edge at most 1920, short edge at least 480, from {@code tkhd}</td><td>VIDEO_DIMENSIONS</td></tr>
 *   <tr><td>Boxes</td><td>nesting at most 6, at most 2,000 boxes, no box larger than the bytes remaining</td><td>VIDEO_MALFORMED</td></tr>
 * </table>
 */
public final class VideoProbe {

    /** How much of the object is read: the whole of the probe's reach. */
    public static final int PROBE_BYTES = 256 * 1024;

    public static final long MAX_BYTES = 12L * 1024 * 1024;
    public static final long MAX_DURATION_MS = 20_000;
    public static final int MAX_LONG_EDGE = 1920;
    public static final int MIN_SHORT_EDGE = 480;
    static final int MAX_NESTING = 6;
    static final int MAX_BOXES = 2_000;

    private static final Set<String> BRANDS = Set.of("isom", "iso2", "mp41", "mp42", "avc1", "M4V ");

    /** The boxes that hold other boxes, and so are walked for depth and count. */
    private static final Set<String> CONTAINERS =
            Set.of("moov", "trak", "mdia", "minf", "stbl", "dinf", "edts", "udta", "mvex", "meta");

    private VideoProbe() {}

    /** The verdict: a video the platform will serve, or the reason it will not. */
    public sealed interface Verdict permits Accepted, Rejected {}

    /** @param hasAudio whether an {@code mp4a} track is present */
    public record Accepted(int widthPx, int heightPx, int durationMs, boolean hasAudio) implements Verdict {}

    /**
     * @param code   a stable code: the operator's console names what to do about each
     * @param detail what was found, for a log and a developer; never the bytes
     */
    public record Rejected(String code, String detail) implements Verdict {}

    public static final String TYPE_NOT_ALLOWED = "VIDEO_TYPE_NOT_ALLOWED";
    public static final String CODEC_NOT_ACCEPTED = "VIDEO_CODEC_NOT_ACCEPTED";
    public static final String NOT_FASTSTART = "VIDEO_NOT_FASTSTART";
    public static final String TOO_LONG = "VIDEO_TOO_LONG";
    public static final String TOO_LARGE = "VIDEO_TOO_LARGE";
    public static final String DIMENSIONS = "VIDEO_DIMENSIONS";
    public static final String MALFORMED = "VIDEO_MALFORMED";

    /**
     * @param prefix    the first bytes of the object, at most {@link #PROBE_BYTES} of which are looked at
     * @param totalSize the object's size as the store reports it
     */
    public static Verdict probe(byte[] prefix, long totalSize) {
        if (totalSize > MAX_BYTES) {
            return new Rejected(TOO_LARGE, "The file is %d bytes; the most is %d".formatted(totalSize, MAX_BYTES));
        }
        Reader reader = new Reader(prefix, Math.min(prefix.length, PROBE_BYTES), totalSize);
        try {
            return reader.read();
        } catch (Malformed malformed) {
            return new Rejected(MALFORMED, String.valueOf(malformed.getMessage()));
        } catch (ArithmeticException | IndexOutOfBoundsException hostile) {
            // The contract is a verdict: the verification worker that calls this has nothing to settle a
            // thrown exception with, so the asset would stay UPLOADED and be retried for good. Every
            // number in a header is the uploader's, and one the arithmetic above did not foresee is
            // still a header the platform will not serve.
            return new Rejected(MALFORMED, "A number in the header is outside what the probe can read");
        }
    }

    // ===================================================================== parsing

    private static final class Malformed extends RuntimeException {
        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private final int limit;
        private final long totalSize;
        private int boxes;

        // What the tree held.
        private boolean sawMoov;
        private boolean sawMdat;
        private int videoTracks;
        private int audioTracks;
        private int otherTracks;
        private @Nullable String videoEntry;
        private int widthPx;
        private int heightPx;
        private long durationMs = -1;
        private boolean audioIsAac = true;

        Reader(byte[] bytes, int limit, long totalSize) {
            this.bytes = bytes;
            this.limit = limit;
            this.totalSize = totalSize;
        }

        Verdict read() {
            if (limit < 16 || !"ftyp".equals(type(4))) {
                return new Rejected(TYPE_NOT_ALLOWED, "The file does not begin with an MP4 file-type box");
            }
            long ftypSize = unsigned32(0);
            if (ftypSize < 16 || ftypSize > limit) {
                return new Rejected(MALFORMED, "The file-type box has an impossible size");
            }
            String major = ascii(8, 4);
            if (!BRANDS.contains(major)) {
                return new Rejected(TYPE_NOT_ALLOWED, "The container brand is " + major.strip() + ", not an MP4");
            }

            long offset = 0;
            while (offset < limit) {
                if (limit - offset < 8) {
                    break;
                }
                Box box = box(offset, totalSize, 0);
                if (box.type.equals("moov")) {
                    sawMoov = true;
                    if (box.end > limit) {
                        // The header is not wholly inside what was read: the file does not start
                        // playing before it has loaded, or the box is cut off.
                        return box.end > totalSize
                                ? new Rejected(MALFORMED, "The movie box is larger than the file")
                                : new Rejected(
                                        NOT_FASTSTART,
                                        "The movie box does not end inside the first " + PROBE_BYTES + " bytes");
                    }
                    if (sawMdat) {
                        return new Rejected(NOT_FASTSTART, "The media data comes before the movie box");
                    }
                    // The whole movie box is walked once for its shape -- depth, count, sizes -- and then
                    // read for what it says; the second pass starts its count again.
                    walk(box);
                    boxes = 0;
                    parseMoov(box);
                    break;
                }
                if (box.type.equals("mdat")) {
                    sawMdat = true;
                    // Whatever follows the media data is beyond a moov-first layout.
                    return new Rejected(NOT_FASTSTART, "The media data comes before the movie box");
                }
                if (box.end > totalSize) {
                    return new Rejected(MALFORMED, "A box is larger than the file");
                }
                offset = box.end;
            }
            if (!sawMoov) {
                return new Rejected(
                        NOT_FASTSTART,
                        "No movie box inside the first " + PROBE_BYTES
                                + " bytes; export the file with the movie header first");
            }
            return verdict();
        }

        private Verdict verdict() {
            if (videoTracks != 1) {
                return new Rejected(
                        MALFORMED,
                        videoTracks == 0 ? "The file has no video track" : "The file has more than one video track");
            }
            if (otherTracks > 0 || audioTracks > 1) {
                return new Rejected(MALFORMED, "The file has a track other than one video and at most one audio track");
            }
            if (!"avc1".equals(videoEntry)) {
                return new Rejected(CODEC_NOT_ACCEPTED, "The video is " + videoEntry + ", not H.264 (avc1)");
            }
            if (audioTracks == 1 && !audioIsAac) {
                return new Rejected(CODEC_NOT_ACCEPTED, "The audio is not AAC (mp4a)");
            }
            if (durationMs < 0) {
                return new Rejected(MALFORMED, "The file states no duration");
            }
            if (durationMs > MAX_DURATION_MS) {
                return new Rejected(
                        TOO_LONG, "The video runs %d ms; the most is %d".formatted(durationMs, MAX_DURATION_MS));
            }
            int longEdge = Math.max(widthPx, heightPx);
            int shortEdge = Math.min(widthPx, heightPx);
            if (shortEdge <= 0 || longEdge > MAX_LONG_EDGE || shortEdge < MIN_SHORT_EDGE) {
                return new Rejected(
                        DIMENSIONS,
                        "The video is %dx%d; the long edge is at most %d and the short edge at least %d"
                                .formatted(widthPx, heightPx, MAX_LONG_EDGE, MIN_SHORT_EDGE));
            }
            return new Accepted(widthPx, heightPx, (int) durationMs, audioTracks == 1);
        }

        private void walk(Box parent) {
            for (Box child : children(parent)) {
                if (CONTAINERS.contains(child.type)) {
                    walk(child);
                }
            }
        }

        private void parseMoov(Box moov) {
            for (Box child : children(moov)) {
                switch (child.type) {
                    case "mvhd" -> parseMvhd(child);
                    case "trak" -> parseTrak(child);
                    default -> {
                        // udta, meta and the like: counted as boxes, otherwise ignored.
                    }
                }
            }
        }

        private void parseMvhd(Box mvhd) {
            int version = u8(mvhd.payload);
            long timescale;
            long duration;
            if (version == 1) {
                need(mvhd, 4 + 8 + 8 + 4 + 8);
                timescale = unsigned32(mvhd.payload + 4 + 16);
                duration = signed64(mvhd.payload + 4 + 20);
            } else {
                need(mvhd, 4 + 4 + 4 + 4 + 4);
                timescale = unsigned32(mvhd.payload + 4 + 8);
                duration = unsigned32(mvhd.payload + 4 + 12);
            }
            if (timescale <= 0 || duration < 0) {
                throw new Malformed("The movie header states an impossible timescale or duration");
            }
            // Whole seconds, then the part-second: duration * 1000 itself overflows for a 64-bit
            // duration an uploader chose, and a file that long is TOO_LONG, not an exception. The sum
            // saturates for the same reason.
            long seconds = duration / timescale;
            long fraction = (duration % timescale) * 1000L / timescale;
            durationMs = seconds > (Long.MAX_VALUE - fraction) / 1000L ? Long.MAX_VALUE : seconds * 1000L + fraction;
        }

        private void parseTrak(Box trak) {
            String handler = null;
            String entry = null;
            long width = 0;
            long height = 0;
            for (Box child : children(trak)) {
                if (child.type.equals("tkhd")) {
                    int version = u8(child.payload);
                    int header = version == 1 ? 4 + 8 + 8 + 4 + 4 + 8 : 4 + 4 + 4 + 4 + 4 + 4;
                    // layer, alternate group, volume, reserved, then the 36-byte matrix.
                    int dims = header + 8 + 2 + 2 + 2 + 2 + 36;
                    need(child, dims + 8);
                    width = unsigned32(child.payload + dims) >> 16;
                    height = unsigned32(child.payload + dims + 4) >> 16;
                } else if (child.type.equals("mdia")) {
                    for (Box media : children(child)) {
                        if (media.type.equals("hdlr")) {
                            need(media, 12);
                            handler = ascii(media.payload + 8, 4);
                        } else if (media.type.equals("minf")) {
                            entry = sampleEntry(media);
                        }
                    }
                }
            }
            if ("vide".equals(handler)) {
                videoTracks++;
                videoEntry = entry;
                widthPx = (int) Math.min(width, Integer.MAX_VALUE);
                heightPx = (int) Math.min(height, Integer.MAX_VALUE);
            } else if ("soun".equals(handler)) {
                audioTracks++;
                if (!"mp4a".equals(entry)) {
                    audioIsAac = false;
                }
            } else {
                otherTracks++;
            }
        }

        private @Nullable String sampleEntry(Box minf) {
            for (Box child : children(minf)) {
                if (!child.type.equals("stbl")) {
                    continue;
                }
                for (Box table : children(child)) {
                    if (table.type.equals("stsd")) {
                        need(table, 8 + 8);
                        long count = unsigned32(table.payload + 4);
                        if (count < 1) {
                            throw new Malformed("A track has no sample description");
                        }
                        return type(table.payload + 8 + 4);
                    }
                }
            }
            return null;
        }

        // ------------------------------------------------------------- box plumbing

        private record Box(String type, int start, int payload, long end, int depth) {}

        private Box box(long offset, long parentEnd, int depth) {
            if (depth > MAX_NESTING) {
                throw new Malformed("Boxes nest deeper than " + MAX_NESTING);
            }
            if (++boxes > MAX_BOXES) {
                throw new Malformed("The file has more than " + MAX_BOXES + " boxes");
            }
            if (offset + 8 > limit) {
                throw new Malformed("A box header is cut off");
            }
            int at = (int) offset;
            long size = unsigned32(at);
            String type = type(at + 4);
            int header = 8;
            if (size == 1) {
                if (offset + 16 > limit) {
                    throw new Malformed("A box header is cut off");
                }
                size = signed64(at + 8);
                header = 16;
            } else if (size == 0) {
                size = parentEnd - offset;
            }
            if (size < header) {
                throw new Malformed("A box states a size smaller than its own header");
            }
            // Compared as a remainder, not as offset + size: a 64-bit size near the top of the range
            // makes that sum wrap negative, and the box would then look like it ends before it starts.
            if (size > parentEnd - offset) {
                throw new Malformed("A box is larger than the bytes remaining");
            }
            return new Box(type, at, at + header, offset + size, depth);
        }

        private java.util.List<Box> children(Box parent) {
            java.util.List<Box> found = new java.util.ArrayList<>();
            long offset = parent.payload;
            // 'meta' is a full box: four bytes of version and flags precede its children.
            if (parent.type.equals("meta")) {
                offset += 4;
            }
            while (offset < parent.end) {
                if (parent.end - offset < 8) {
                    throw new Malformed("A box has trailing bytes that are not a box");
                }
                Box child = box(offset, parent.end, parent.depth + 1);
                found.add(child);
                offset = child.end;
            }
            return found;
        }

        private void need(Box box, int bytes) {
            if (box.payload + (long) bytes > box.end) {
                throw new Malformed("A " + box.type + " box is shorter than its fields");
            }
        }

        private int u8(int at) {
            if (at >= limit) {
                throw new Malformed("A field is past the end of what was read");
            }
            return bytes[at] & 0xFF;
        }

        private long unsigned32(int at) {
            if (at + 4 > limit) {
                throw new Malformed("A field is past the end of what was read");
            }
            return ((bytes[at] & 0xFFL) << 24)
                    | ((bytes[at + 1] & 0xFFL) << 16)
                    | ((bytes[at + 2] & 0xFFL) << 8)
                    | (bytes[at + 3] & 0xFFL);
        }

        private long signed64(int at) {
            return (unsigned32(at) << 32) | unsigned32(at + 4);
        }

        private String type(int at) {
            return ascii(at, 4);
        }

        private String ascii(int at, int length) {
            if (at + length > limit) {
                throw new Malformed("A field is past the end of what was read");
            }
            return new String(bytes, at, length, StandardCharsets.ISO_8859_1);
        }
    }

    /** The codes a rejection can carry, for a caller that must know them all. */
    public static Set<String> codes() {
        Set<String> codes = new HashSet<>();
        codes.add(TYPE_NOT_ALLOWED);
        codes.add(CODEC_NOT_ACCEPTED);
        codes.add(NOT_FASTSTART);
        codes.add(TOO_LONG);
        codes.add(TOO_LARGE);
        codes.add(DIMENSIONS);
        codes.add(MALFORMED);
        return Set.copyOf(codes);
    }
}
