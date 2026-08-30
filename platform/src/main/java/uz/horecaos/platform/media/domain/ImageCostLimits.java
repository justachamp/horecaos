package uz.horecaos.platform.media.domain;

/**
 * What one image is allowed to cost this platform (ADR 0010).
 *
 * <p>Here rather than restated in the upload gate and again in the renderer.
 * They were two copies of the same number with two different comments, and the
 * one thing worse than a limit in the wrong unit is two limits in the wrong
 * unit that can drift apart.
 *
 * <p>Three limits, because a picture costs three different things and no one
 * number bounds all of them.
 */
public final class ImageCostLimits {

    /**
     * Heap, and the one that actually matters.
     *
     * <p>128MiB is what a single decode may allocate. It is not a new policy so
     * much as the old one stated in its real unit: forty megapixels of ordinary
     * 8-bit RGB — "roughly an 8000x5000 photograph, more than any menu ever
     * needs" — is 120MB, so every image the pixel ceiling was written to admit
     * still passes. What no longer passes is the same forty megapixels at
     * 16-bit RGBA, which is 305MB and was indistinguishable from the first
     * under a limit that counted pixels.
     *
     * <p>This is a policy, not a guarantee. It says what the platform will
     * attempt, and it obliges a deployment to give the renderer a heap that
     * comfortably exceeds it. When the policy turns out to be wrong for some
     * input the decoder still runs out of memory, and that is why the render
     * path has to be able to report a failure as a failure rather than swallow
     * it — the ceiling and the failure handling are two halves of one fix.
     */
    public static final long MAX_DECODED_BYTES = 128L * 1024 * 1024;

    /**
     * CPU, which heap does not bound.
     *
     * <p>Kept even though {@link #MAX_DECODED_BYTES} is the memory limit,
     * because scaling is per-pixel work regardless of what a pixel weighs: a
     * 128-megapixel 8-bit greyscale image is 128MB of heap and still three
     * smooth rescales of 128 million pixels. Forty megapixels is the bound on
     * the arithmetic; the byte limit is the bound on the allocation.
     */
    public static final long MAX_PIXELS = 40_000_000L;

    /**
     * Geometry, which neither of the other two bounds.
     *
     * <p>A 200,000 by 8 strip is 1.6 megapixels and 5MB, and is not a
     * photograph of anything. Bounding each side separately keeps a legitimate
     * budget from being spent on a shape no storefront can render.
     */
    public static final int MAX_DIMENSION_PX = 12_000;

    private ImageCostLimits() {
    }

    /** @return true when this header describes an image the platform will decode */
    public static boolean withinBudget(ProbedImage image) {
        return image.widthPx() <= MAX_DIMENSION_PX
                && image.heightPx() <= MAX_DIMENSION_PX
                && image.pixels() <= MAX_PIXELS
                && image.decodedBytes() <= MAX_DECODED_BYTES;
    }
}
