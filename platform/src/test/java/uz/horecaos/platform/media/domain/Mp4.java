package uz.horecaos.platform.media.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Builds the boxes of an MP4 in memory, so a probe test can say exactly what is wrong with the file
 * it hands over: a real file's header is boxes, and the probe reads nothing else.
 */
public final class Mp4 {

    private Mp4() {}

    public static byte[] box(String type, byte[]... parts) {
        int size = 8;
        for (byte[] part : parts) {
            size += part.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(size);
        buffer.put(type.getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }

    /** A box whose size field says something other than its length. */
    public static byte[] boxClaiming(long size, String type, byte[]... parts) {
        byte[] real = box(type, parts);
        ByteBuffer.wrap(real).putInt((int) size);
        return real;
    }

    /** A box with a 64-bit size field. */
    public static byte[] largeBox(String type, byte[]... parts) {
        int payload = 0;
        for (byte[] part : parts) {
            payload += part.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(16 + payload);
        buffer.putInt(1);
        buffer.put(type.getBytes(StandardCharsets.ISO_8859_1));
        buffer.putLong(16L + payload);
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    public static byte[] zeros(int length) {
        return new byte[length];
    }

    public static byte[] ftyp(String major) {
        return box(
                "ftyp",
                major.getBytes(StandardCharsets.ISO_8859_1),
                new byte[] {0, 0, 2, 0},
                "isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1));
    }

    public static byte[] mvhd(long timescale, long duration) {
        ByteBuffer payload = ByteBuffer.allocate(100);
        payload.putInt(0); // version and flags
        payload.putInt(0); // creation
        payload.putInt(0); // modification
        payload.putInt((int) timescale);
        payload.putInt((int) duration);
        return box("mvhd", payload.array());
    }

    public static byte[] mvhdV1(long timescale, long duration) {
        ByteBuffer payload = ByteBuffer.allocate(112);
        payload.putInt(0x01000000); // version 1
        payload.putLong(0);
        payload.putLong(0);
        payload.putInt((int) timescale);
        payload.putLong(duration);
        return box("mvhd", payload.array());
    }

    public static byte[] tkhd(int width, int height) {
        ByteBuffer payload = ByteBuffer.allocate(84);
        payload.putInt(0); // version and flags
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(1); // track id
        payload.putInt(0);
        payload.putInt(0); // duration
        payload.position(24 + 8 + 8 + 36);
        payload.putInt(width << 16);
        payload.putInt(height << 16);
        return box("tkhd", payload.array());
    }

    public static byte[] hdlr(String handler) {
        ByteBuffer payload = ByteBuffer.allocate(24);
        payload.putInt(0);
        payload.putInt(0);
        payload.put(handler.getBytes(StandardCharsets.ISO_8859_1));
        return box("hdlr", payload.array());
    }

    public static byte[] stsd(String entry) {
        byte[] sampleEntry = box(entry, zeros(78));
        return box("stsd", new byte[] {0, 0, 0, 0, 0, 0, 0, 1}, sampleEntry);
    }

    public static byte[] trak(String handler, String entry, int width, int height) {
        return box("trak", tkhd(width, height), box("mdia", hdlr(handler), box("minf", box("stbl", stsd(entry)))));
    }

    public static byte[] video(String entry, int width, int height) {
        return trak("vide", entry, width, height);
    }

    public static byte[] audio(String entry) {
        return trak("soun", entry, 0, 0);
    }

    public static byte[] moov(byte[]... children) {
        return box("moov", children);
    }

    public static byte[] mdat(int payload) {
        return box("mdat", zeros(payload));
    }

    /** A 10-second 1280x720 H.264 file with AAC audio, moov first: the shape the platform serves. */
    public static byte[] valid() {
        return concat(ftyp("isom"), moov(mvhd(1000, 10_000), video("avc1", 1280, 720), audio("mp4a")), mdat(1000));
    }

    public static byte[] withMoov(byte[]... moovChildren) {
        return concat(ftyp("isom"), moov(moovChildren), mdat(1000));
    }
}
