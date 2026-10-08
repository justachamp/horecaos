package uz.horecaos.platform.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.media.domain.Mp4.audio;
import static uz.horecaos.platform.media.domain.Mp4.box;
import static uz.horecaos.platform.media.domain.Mp4.concat;
import static uz.horecaos.platform.media.domain.Mp4.ftyp;
import static uz.horecaos.platform.media.domain.Mp4.mdat;
import static uz.horecaos.platform.media.domain.Mp4.moov;
import static uz.horecaos.platform.media.domain.Mp4.mvhd;
import static uz.horecaos.platform.media.domain.Mp4.trak;
import static uz.horecaos.platform.media.domain.Mp4.valid;
import static uz.horecaos.platform.media.domain.Mp4.video;
import static uz.horecaos.platform.media.domain.Mp4.withMoov;
import static uz.horecaos.platform.media.domain.Mp4.zeros;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.media.domain.VideoProbe.Accepted;
import uz.horecaos.platform.media.domain.VideoProbe.Rejected;
import uz.horecaos.platform.media.domain.VideoProbe.Verdict;

/**
 * The MP4 probe (ADR 0157, Decision 8), over fixtures built box by box: what it accepts, and for
 * each thing it refuses the named code an operator's console turns into a sentence about how to
 * export a compliant file.
 *
 * <p>The probe reads a header and never decodes, so every fixture here is only the boxes the probe
 * looks at; a real file's frames would change nothing it says.
 */
class VideoProbeTests {

    private static Verdict probe(byte[] file) {
        return VideoProbe.probe(file, file.length);
    }

    private static void refused(byte[] file, String code) {
        assertThat(probe(file))
                .isInstanceOfSatisfying(
                        Rejected.class,
                        rejection -> assertThat(rejection.code()).isEqualTo(code));
    }

    private static Accepted accepted(byte[] file) {
        Verdict verdict = probe(file);
        assertThat(verdict).as("%s", verdict).isInstanceOf(Accepted.class);
        return (Accepted) verdict;
    }

    // ================================================================= accepted

    @Test
    @DisplayName(
            "a faststart H.264 file with AAC audio is accepted, and its size and duration are read from its own header")
    void aValidFile() {
        Accepted accepted = accepted(valid());

        assertThat(accepted.widthPx()).isEqualTo(1280);
        assertThat(accepted.heightPx()).isEqualTo(720);
        assertThat(accepted.durationMs()).isEqualTo(10_000);
        assertThat(accepted.hasAudio()).isTrue();
    }

    @Test
    @DisplayName(
            "a file with no audio track, a portrait file, the smallest and largest accepted frames, a 64-bit duration and every accepted brand are accepted")
    void theEdgesOfAccepted() {
        assertThat(accepted(withMoov(mvhd(1000, 5_000), video("avc1", 1080, 1920)))
                        .hasAudio())
                .isFalse();
        assertThat(accepted(withMoov(mvhd(1000, 5_000), video("avc1", 1080, 1920)))
                        .heightPx())
                .isEqualTo(1920);
        accepted(withMoov(mvhd(1000, 5_000), video("avc1", 480, 480)));
        accepted(withMoov(mvhd(1000, 5_000), video("avc1", 1920, 1080)));
        assertThat(accepted(withMoov(Mp4.mvhdV1(90_000, 90_000L * 20), video("avc1", 1280, 720)))
                        .durationMs())
                .isEqualTo(20_000);
        for (String brand : new String[] {"isom", "iso2", "mp41", "mp42", "avc1", "M4V "}) {
            accepted(concat(ftyp(brand), moov(mvhd(1000, 5_000), video("avc1", 1280, 720)), mdat(10)));
        }
        // A free box ahead of the movie box, with a 64-bit size, is just a box.
        accepted(concat(
                ftyp("isom"), Mp4.largeBox("free", zeros(64)), moov(mvhd(1000, 5_000), video("avc1", 1280, 720))));
    }

    // ========================================================== the named refusals

    @Test
    @DisplayName("VIDEO_CODEC_NOT_ACCEPTED: HEVC, VP9 and AV1 video, and audio that is not AAC")
    void codecs() {
        for (String entry : new String[] {"hvc1", "hev1", "vp09", "av01", "mp4v"}) {
            refused(withMoov(mvhd(1000, 5_000), video(entry, 1280, 720)), VideoProbe.CODEC_NOT_ACCEPTED);
        }
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), audio("ac-3")), VideoProbe.CODEC_NOT_ACCEPTED);
    }

    @Test
    @DisplayName("VIDEO_NOT_FASTSTART: the movie box after the media data, or beyond the first 256 KiB")
    void layout() {
        byte[] moovAtTheEnd = concat(ftyp("isom"), mdat(2000), moov(mvhd(1000, 5_000), video("avc1", 1280, 720)));
        refused(moovAtTheEnd, VideoProbe.NOT_FASTSTART);

        // The media data declaring it runs to the end of the file, ahead of the header.
        byte[] toTheEnd = concat(
                ftyp("isom"),
                Mp4.boxClaiming(0, "mdat", zeros(100)),
                moov(mvhd(1000, 5_000), video("avc1", 1280, 720)));
        refused(toTheEnd, VideoProbe.NOT_FASTSTART);

        // A movie box that ends past 256 KiB is never read, so it is refused whatever it holds.
        byte[] farAway = concat(
                ftyp("isom"),
                box("free", zeros(VideoProbe.PROBE_BYTES)),
                moov(mvhd(1000, 5_000), video("avc1", 1280, 720)),
                mdat(100));
        refused(farAway, VideoProbe.NOT_FASTSTART);

        // And one that begins inside the window but ends outside it.
        byte[] straddling = concat(
                ftyp("isom"),
                box("free", zeros(VideoProbe.PROBE_BYTES - 200)),
                moov(mvhd(1000, 5_000), video("avc1", 1280, 720), box("udta", zeros(2000))),
                mdat(100));
        refused(straddling, VideoProbe.NOT_FASTSTART);

        // There is no movie box at all within what was read.
        refused(concat(ftyp("isom"), box("free", zeros(100))), VideoProbe.NOT_FASTSTART);
    }

    @Test
    @DisplayName("VIDEO_TOO_LONG: more than 20 seconds, from the movie header; exactly 20 seconds is accepted")
    void duration() {
        refused(withMoov(mvhd(1000, 3_600_000), video("avc1", 1280, 720)), VideoProbe.TOO_LONG);
        refused(withMoov(mvhd(1000, 20_001), video("avc1", 1280, 720)), VideoProbe.TOO_LONG);
        refused(withMoov(mvhd(90_000, 90_000L * 21), video("avc1", 1280, 720)), VideoProbe.TOO_LONG);
        accepted(withMoov(mvhd(1000, 20_000), video("avc1", 1280, 720)));
        refused(withMoov(mvhd(0, 1000), video("avc1", 1280, 720)), VideoProbe.MALFORMED);
    }

    @Test
    @DisplayName(
            "VIDEO_TOO_LARGE: more than 12 MiB as the store reports it, whatever the header says; exactly 12 MiB is accepted")
    void size() {
        byte[] header = valid();
        assertThat(VideoProbe.probe(header, VideoProbe.MAX_BYTES + 1))
                .isInstanceOfSatisfying(
                        Rejected.class, r -> assertThat(r.code()).isEqualTo(VideoProbe.TOO_LARGE));
        assertThat(VideoProbe.probe(header, VideoProbe.MAX_BYTES)).isInstanceOf(Accepted.class);
        assertThat(VideoProbe.MAX_BYTES).isEqualTo(12L * 1024 * 1024);
    }

    @Test
    @DisplayName("VIDEO_DIMENSIONS: a long edge over 1920, a short edge under 480, and a frame of zero")
    void dimensions() {
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 3840, 2160)), VideoProbe.DIMENSIONS);
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 1921, 1080)), VideoProbe.DIMENSIONS);
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 640, 360)), VideoProbe.DIMENSIONS);
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 0, 0)), VideoProbe.DIMENSIONS);
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 1280, 0)), VideoProbe.DIMENSIONS);
    }

    @Test
    @DisplayName(
            "VIDEO_TYPE_NOT_ALLOWED: a QuickTime brand, a file that does not begin with a file-type box, and bytes that are not a video at all")
    void container() {
        refused(concat(ftyp("qt  "), moov(mvhd(1000, 5_000), video("avc1", 1280, 720))), VideoProbe.TYPE_NOT_ALLOWED);
        refused(concat(moov(mvhd(1000, 5_000), video("avc1", 1280, 720)), ftyp("isom")), VideoProbe.TYPE_NOT_ALLOWED);
        refused("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), VideoProbe.TYPE_NOT_ALLOWED);
        refused(new byte[0], VideoProbe.TYPE_NOT_ALLOWED);
        refused(new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'}, VideoProbe.TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName(
            "VIDEO_MALFORMED: no video track, two, a subtitle or data track, two audio tracks, a truncated file, a box whose size lies, a box smaller than its own header")
    void malformed() {
        refused(withMoov(mvhd(1000, 5_000), audio("mp4a")), VideoProbe.MALFORMED);
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), video("avc1", 1280, 720)), VideoProbe.MALFORMED);
        refused(
                withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), trak("text", "tx3g", 0, 0)),
                VideoProbe.MALFORMED);
        refused(
                withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), trak("meta", "mett", 0, 0)),
                VideoProbe.MALFORMED);
        refused(
                withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), audio("mp4a"), audio("mp4a")),
                VideoProbe.MALFORMED);
        refused(withMoov(video("avc1", 1280, 720)), VideoProbe.MALFORMED);

        // Truncated inside the movie box: the file has fewer bytes than the box says.
        byte[] whole = valid();
        byte[] truncated = Arrays.copyOf(whole, ftyp("isom").length + 60);
        refused(truncated, VideoProbe.MALFORMED);

        // A box whose size field lies: more than the file has, and less than a header.
        byte[] liar =
                concat(ftyp("isom"), Mp4.boxClaiming(10_000_000, "moov", mvhd(1000, 5_000), video("avc1", 1280, 720)));
        refused(liar, VideoProbe.MALFORMED);
        byte[] tiny = concat(ftyp("isom"), moov(Mp4.boxClaiming(3, "mvhd", zeros(40)), video("avc1", 1280, 720)));
        refused(tiny, VideoProbe.MALFORMED);
        byte[] childTooBig =
                concat(ftyp("isom"), moov(mvhd(1000, 5_000), Mp4.boxClaiming(5_000_000, "trak", zeros(100))));
        refused(childTooBig, VideoProbe.MALFORMED);
        // A header too short for its own fields.
        refused(withMoov(box("mvhd", zeros(8)), video("avc1", 1280, 720)), VideoProbe.MALFORMED);
        // Trailing bytes inside a box that are not a box.
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), box("udta", zeros(3))), VideoProbe.MALFORMED);
    }

    @Test
    @DisplayName("VIDEO_MALFORMED: boxes nested more than six deep, and more than two thousand boxes")
    void bombs() {
        byte[] nest = box("free", zeros(0));
        for (int depth = 0; depth < 8; depth++) {
            nest = box("trak", nest);
        }
        refused(withMoov(mvhd(1000, 5_000), video("avc1", 1280, 720), nest), VideoProbe.MALFORMED);

        byte[][] many = new byte[2_100][];
        for (int i = 0; i < many.length; i++) {
            many[i] = box("free", zeros(0));
        }
        refused(withMoov(concat(many), mvhd(1000, 5_000), video("avc1", 1280, 720)), VideoProbe.MALFORMED);

        // Six levels of nesting that are legitimate: moov, trak, mdia, minf, stbl, stsd.
        accepted(valid());
    }

    // ================================================================= its reach

    @Test
    @DisplayName("the probe never reads past 256 KiB: bytes beyond it change nothing it says")
    void neverReadsPastTheWindow() {
        byte[] header = valid();
        byte[] noise = new byte[2 * VideoProbe.PROBE_BYTES];
        Arrays.fill(noise, (byte) 0x5A);
        byte[] withGarbageBeyond = concat(header, noise);

        assertThat(VideoProbe.probe(withGarbageBeyond, withGarbageBeyond.length))
                .isInstanceOf(Accepted.class);
        assertThat(VideoProbe.probe(withGarbageBeyond, withGarbageBeyond.length))
                .isEqualTo(VideoProbe.probe(header, header.length));

        // A header that exists only beyond the window is invisible to it, even in the array it was handed.
        byte[] hidden = concat(
                ftyp("isom"),
                box("free", zeros(VideoProbe.PROBE_BYTES)),
                moov(mvhd(1000, 5_000), video("avc1", 1280, 720)));
        refused(hidden, VideoProbe.NOT_FASTSTART);
    }
}
