package uz.horecaos.platform.media.api;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What an asset is for, when it is for something more specific than a menu, a brand or a person (ADR 0187, D25;
 * the column ADR 0010 predicted).
 *
 * <p>A purposed asset is always a {@code PUBLIC}, tenant-owned image (the database says so,
 * {@code ck_media_asset_purpose_shape}) and answers to the shape its purpose names: the verification worker
 * rejects one whose header is the wrong shape, and a module that attaches one asks {@link PurposedImages} again,
 * because the attach is where a guessed asset id would be tried.
 */
public enum MediaPurpose {

    /**
     * One picture on a KIOSK channel's idle loop: 9:16, at most 1 MB (1 048 576 bytes), JPEG, PNG or WebP. No video
     * (ADR 0187, D25).
     */
    KIOSK_IDLE(9, 16, 1_048_576L);

    /** One percent: an image cropped to the pixel by a browser is rarely exactly its ratio. */
    private static final double RATIO_TOLERANCE = 0.01;

    private final int ratioWidth;
    private final int ratioHeight;
    private final long maximumBytes;

    MediaPurpose(int ratioWidth, int ratioHeight, long maximumBytes) {
        this.ratioWidth = ratioWidth;
        this.ratioHeight = ratioHeight;
        this.maximumBytes = maximumBytes;
    }

    public int ratioWidth() {
        return ratioWidth;
    }

    public int ratioHeight() {
        return ratioHeight;
    }

    public long maximumBytes() {
        return maximumBytes;
    }

    /** Whether {@code width x height} is this purpose's ratio, within one percent on the cross product. */
    public boolean matchesRatio(int width, int height) {
        if (width < 1 || height < 1) {
            return false;
        }
        double expected = (double) height * ratioWidth;
        double actual = (double) width * ratioHeight;
        return Math.abs(actual - expected) <= RATIO_TOLERANCE * expected;
    }

    /** The purpose a stored column value names, or empty for null (no purpose) and for anything unknown. */
    public static Optional<MediaPurpose> ofColumn(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
