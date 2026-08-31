package uz.horecaos.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.media.api.ImageDerivativeRenderer;
import uz.horecaos.platform.media.api.ImageDerivativeRenderer.Rendered;
import uz.horecaos.platform.media.api.ImageDerivativeRenderer.Unsupported;
import uz.horecaos.platform.media.domain.DerivativeVariant;
import uz.horecaos.platform.media.domain.ImageCostLimits;
import uz.horecaos.platform.media.domain.ImageProbe;
import uz.horecaos.platform.media.infrastructure.imaging.ImageIoDerivativeRenderer;

/** What the renderer will and will not decode, and how it says which (ADR 0010). */
class DerivativeRenderingTests {

    private final ImageDerivativeRenderer renderer = new ImageIoDerivativeRenderer();

    @Test
    @DisplayName("a JPEG is scaled to the variant's width, keeping its aspect ratio")
    void scalesToTheVariantWidth() throws IOException {
        var rendered = rendered(encode("jpg", 1200, 900), DerivativeVariant.CARD);
        // The render just asked for exactly this variant and rendered() already
        // asserted a Rendered outcome, so the map holds it; requireNonNull states
        // that rather than leaving the checker to guess.
        var card = Objects.requireNonNull(rendered.renditions().get(DerivativeVariant.CARD));

        assertThat(card.widthPx()).isEqualTo(400);
        assertThat(card.heightPx()).isEqualTo(300);
        assertThat(card.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageProbe.probe(card.content()).orElseThrow().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("every variant comes out of one call, because it comes out of one decode")
    void rendersEveryVariantFromASingleDecode() throws IOException {
        // The source is expanded into a raster once and scaled from it three
        // times. Asked variant by variant, a 300MB original was decoded three
        // times over for three outputs that differ only in a scale factor.
        var rendered = renderer.render(encode("jpg", 1600, 1200), List.of(DerivativeVariant.values()));

        assertThat(rendered).isInstanceOf(Rendered.class);
        var renditions = ((Rendered) rendered).renditions();
        assertThat(renditions)
                .containsOnlyKeys(DerivativeVariant.THUMBNAIL, DerivativeVariant.CARD, DerivativeVariant.DETAIL);
        // containsOnlyKeys above already proved these two are present.
        assertThat(Objects.requireNonNull(renditions.get(DerivativeVariant.THUMBNAIL))
                        .widthPx())
                .isEqualTo(200);
        assertThat(Objects.requireNonNull(renditions.get(DerivativeVariant.DETAIL))
                        .widthPx())
                .isEqualTo(800);
    }

    @Test
    @DisplayName("asking for nothing decodes nothing")
    void rendersNothingWhenNothingIsMissing() throws IOException {
        // A redelivered trigger for an asset that already has every variant. The
        // bytes are never handed to a decoder, which is what makes at-least-once
        // delivery affordable for a job whose unit of work is a raster.
        var rendered = renderer.render(encode("jpg", 1200, 900), List.of());

        assertThat(rendered).isInstanceOf(Rendered.class);
        assertThat(((Rendered) rendered).renditions()).isEmpty();
    }

    @Test
    @DisplayName("a PNG with transparency renders onto white rather than onto black")
    void flattensTransparencyOntoWhite() throws IOException {
        BufferedImage transparent = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(transparent, "png", source);

        var rendered = rendered(source.toByteArray(), DerivativeVariant.THUMBNAIL);

        // JPEG has no alpha channel, so a transparent logo composites onto
        // whatever is behind it. Left to the default that is black, and a menu of
        // black boxes is what "the derivative worked" would have looked like.
        // The render just asked for exactly this variant and rendered() already
        // asserted a Rendered outcome, so the map holds it.
        var thumbnail = Objects.requireNonNull(rendered.renditions().get(DerivativeVariant.THUMBNAIL));
        BufferedImage output = ImageIO.read(new java.io.ByteArrayInputStream(thumbnail.content()));
        assertThat(output.getRGB(50, 50) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    @DisplayName("a format this renderer cannot decode is settled, not failed")
    void unsupportedFormatIsAnOrdinaryOutcome() {
        // A valid WebP header. Uploads accept WebP; the JDK ships no decoder for
        // it, so the original stands alone until an encoder is added — and that
        // is an answer, not an error. Trying again produces the same answer.
        byte[] webp = new byte[32];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
        System.arraycopy("VP8L".getBytes(StandardCharsets.US_ASCII), 0, webp, 12, 4);
        webp[20] = 0x2F;
        int packed = (256 - 1) | ((128 - 1) << 14);
        webp[21] = (byte) packed;
        webp[22] = (byte) (packed >>> 8);
        webp[23] = (byte) (packed >>> 16);

        assertThat(ImageProbe.probe(webp)).isPresent();
        assertThat(renderer.render(webp, List.of(DerivativeVariant.THUMBNAIL)))
                .isEqualTo(new Unsupported("FORMAT_NOT_DECODABLE"));
    }

    @Test
    @DisplayName("a header declaring a decompression bomb is refused before any decoding")
    void refusesADecompressionBombOnItsHeader() {
        // Twenty-six bytes claiming a 50,000-pixel square at 16-bit RGBA. Handing
        // this to a decoder to find out costs twenty gigabytes of heap; reading
        // IHDR costs the twenty-six bytes.
        assertThat(renderer.render(pngHeader(50_000, 50_000, 16, 6), List.of(DerivativeVariant.THUMBNAIL)))
                .isEqualTo(new Unsupported("SOURCE_TOO_LARGE_TO_DECODE"));
    }

    @Test
    @DisplayName("a bomb that a pixel ceiling would have admitted is refused on its byte cost")
    void refusesABombThatIsOnlyExpensivePerPixel() {
        // Exactly forty megapixels — on, and therefore under, the pixel ceiling
        // that used to be the whole test — at 16-bit RGBA, which is 305MB to
        // decode. This is the header of the 311KB file the defect was reported
        // with; the identical dimensions at 8-bit RGB still render.
        assertThat(renderer.render(pngHeader(8000, 5000, 16, 6), List.of(DerivativeVariant.THUMBNAIL)))
                .isEqualTo(new Unsupported("SOURCE_TOO_LARGE_TO_DECODE"));
        // The identical dimensions at 8-bit RGB are 120MB and stay within
        // budget: the limit is what the old comment always claimed it was —
        // "roughly an 8000x5000 photograph" — stated in the unit it was about.
        assertThat(ImageCostLimits.withinBudget(
                        ImageProbe.probe(pngHeader(8000, 5000, 8, 2)).orElseThrow()))
                .isTrue();
    }

    @Test
    @DisplayName("bytes that are not an image are refused")
    void refusesNonImages() {
        assertThat(renderer.render(
                        "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8),
                        List.of(DerivativeVariant.THUMBNAIL)))
                .isEqualTo(new Unsupported("NOT_AN_IMAGE"));
    }

    @Test
    @DisplayName("a malformed body under a real header is settled, because the bytes will not change")
    void malformedSourceIsSettled() throws IOException {
        byte[] malformed = encode("png", 320, 240);
        java.util.Arrays.fill(malformed, 33, malformed.length, (byte) 0x7F);

        assertThat(renderer.render(malformed, List.of(DerivativeVariant.THUMBNAIL)))
                .isEqualTo(new Unsupported("SOURCE_MALFORMED"));
    }

    private Rendered rendered(byte[] source, DerivativeVariant variant) {
        var outcome = renderer.render(source, List.of(variant));
        assertThat(outcome).isInstanceOf(Rendered.class);
        return (Rendered) outcome;
    }

    private static byte[] pngHeader(int width, int height, int bitDepth, int colourType) {
        byte[] png = new byte[] {
            (byte) 0x89,
            'P',
            'N',
            'G',
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0,
            0,
            0,
            13,
            'I',
            'H',
            'D',
            'R',
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        };
        for (int i = 0; i < 4; i++) {
            png[16 + i] = (byte) (width >>> (24 - 8 * i));
            png[20 + i] = (byte) (height >>> (24 - 8 * i));
        }
        png[24] = (byte) bitDepth;
        png[25] = (byte) colourType;
        return png;
    }

    private static byte[] encode(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }
}
