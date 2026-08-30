package uz.horecaos.platform.media.domain;

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
    DETAIL("w800", 800);

    private final String code;
    private final int targetWidthPx;

    DerivativeVariant(String code, int targetWidthPx) {
        this.code = code;
        this.targetWidthPx = targetWidthPx;
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
