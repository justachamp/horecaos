package uz.horecaos.platform.media.domain;

/**
 * What an image's own header says it is (ADR 0010).
 *
 * @param contentType           the type the bytes declare, not the type the
 *                              request or the object store's metadata declared
 * @param widthPx               read from the header, never by decompressing the image
 * @param heightPx              read from the header, never by decompressing the image
 * @param decodedBytesPerPixel  what one pixel of this image costs once a decoder
 *                              has expanded it into a raster. Read from the
 *                              header too — the sample depth and the channel
 *                              count are in the same twenty-odd bytes as the
 *                              dimensions — and derived per format, because only
 *                              the format's own parser knows what its decoder
 *                              will allocate
 */
public record ProbedImage(String contentType, int widthPx, int heightPx, int decodedBytesPerPixel) {

    public long pixels() {
        return (long) widthPx * heightPx;
    }

    /**
     * What decoding this image would cost in heap, which is the quantity every
     * limit in this module is actually about.
     *
     * <p>A pixel count is not that quantity and never was. The same forty
     * megapixels is 40MB as 8-bit greyscale and 320MB as 16-bit RGBA — an
     * eightfold spread, decided by two bytes of {@code IHDR} — so a ceiling
     * expressed in pixels is a ceiling on a number whose meaning the uploader
     * chooses. A 311KB PNG declaring 8000x5000 at 16-bit RGBA sits exactly on a
     * forty-megapixel limit and decodes to 305MB.
     *
     * @return saturated at {@link Long#MAX_VALUE} rather than allowed to wrap;
     *         a header declaring dimensions whose product overflows is over
     *         every conceivable budget, and a wrapped negative would read as
     *         under it
     */
    public long decodedBytes() {
        try {
            return Math.multiplyExact(pixels(), (long) decodedBytesPerPixel);
        } catch (ArithmeticException beyondAnyBudget) {
            return Long.MAX_VALUE;
        }
    }
}
