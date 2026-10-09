package uz.horecaos.platform.media.domain;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The fixed set of renditions the platform produces (ADR 0010).
 *
 * <p>A closed set rather than an arbitrary width in a URL. An on-demand
 * {@code ?w=} parameter is one request away from a thousand distinct renders of
 * the same photograph, each one a cache miss and a decode, which is the CPU
 * spike ADR 0010 rejected the image-proxy option over.
 *
 * <p>Every variant is JPEG. WebP and AVIF are what the ADR names, and they are
 * genuinely smaller, but the JDK ships no encoder for either — adding one is a
 * dependency decision rather than a media-module one. The variant codes are
 * therefore free of a format suffix, so adding a WebP rendition later is a new
 * variant rather than a rename of these.
 */
public enum DerivativeVariant {

    /** A menu list row and a cart line. Small enough to send dozens of. */
    THUMBNAIL("thumb", 200),

    /** A menu card on a phone. */
    CARD("w400", 400),

    /** A dish detail view, and the same file at 2x on a phone card. */
    DETAIL("w800", 800),

    /**
     * A 3:1 banner on a desktop channel (ADR 0157), which needs more than {@link #DETAIL}'s 800 px.
     *
     * <p>Rendered <strong>only</strong> for an asset allocated with the {@code CREATIVE} rendition
     * profile, which the console's content upload sets ({@link #standard()} is what every other
     * asset gets). A bare enum member that every asset rendered would be a fourth JPEG per
     * catalogue photograph on the one 8 vCPU machine (ADR 0073). Like every variant it is never
     * an upscale: a source narrower than 1600 px yields a HERO as wide as the source.
     */
    HERO("w1600", 1600);

    private final String code;
    private final int targetWidthPx;

    DerivativeVariant(String code, int targetWidthPx) {
        this.code = code;
        this.targetWidthPx = targetWidthPx;
    }

    /** The renditions every asset gets: the three that predate {@link #HERO}. */
    public static List<DerivativeVariant> standard() {
        return List.of(THUMBNAIL, CARD, DETAIL);
    }

    /**
     * The renditions owed to an asset of this profile: the standard set, plus {@link #HERO} for a
     * creative (ADR 0157).
     */
    public static List<DerivativeVariant> owedTo(boolean creative) {
        return creative ? List.of(THUMBNAIL, CARD, DETAIL, HERO) : standard();
    }

    /** The variant whose public code ({@code thumb}, {@code w400}, {@code w800}, {@code w1600}) this is. */
    public static Optional<DerivativeVariant> byCode(@Nullable String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (DerivativeVariant variant : values()) {
            if (variant.code.equals(code)) {
                return Optional.of(variant);
            }
        }
        return Optional.empty();
    }

    /** Stable and part of the object key, so it never changes once written. */
    public String code() {
        return code;
    }

    public int targetWidthPx() {
        return targetWidthPx;
    }

    /**
     * The key for this variant of an asset.
     *
     * <p>Derived from the original's key, which is derived from ids we control,
     * so a derivative is as tenant-scoped and as unguessable as its original.
     */
    public String objectKey(String originalKey) {
        return "%s-%s.jpg".formatted(originalKey, code);
    }
}
