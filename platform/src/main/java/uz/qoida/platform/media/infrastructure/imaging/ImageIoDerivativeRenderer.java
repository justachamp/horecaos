package uz.qoida.platform.media.infrastructure.imaging;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uz.qoida.platform.media.api.ImageDerivativeRenderer;
import uz.qoida.platform.media.domain.DecodeError;
import uz.qoida.platform.media.domain.DerivativeVariant;
import uz.qoida.platform.media.domain.ImageCostLimits;
import uz.qoida.platform.media.domain.ImageProbe;
import uz.qoida.platform.media.domain.ProbedImage;

/**
 * Rendition rendering on the JDK's own decoders (ADR 0010).
 *
 * <p>Reads JPEG and PNG. It does not read WebP or AVIF, which uploads accept:
 * the JDK ships no decoder for either, and adding one is a dependency decision.
 * Those originals therefore get no derivative and the caller records that, which
 * is why the storefront has to be able to fall back to the original rather than
 * assume a thumbnail exists.
 *
 * <p>One decode serves every variant. The source is expanded into a raster once
 * and scaled from that raster three times, because the decode is the expensive
 * and the dangerous half — a variant-at-a-time loop paid the peak allocation
 * once per variant for three outputs that differ only in a scale factor.
 */
@Component
public class ImageIoDerivativeRenderer implements ImageDerivativeRenderer {

    private static final Logger log = LoggerFactory.getLogger(ImageIoDerivativeRenderer.class);

    /**
     * Bumped whenever the output of this renderer changes — a different quality,
     * a different scaling filter, a different encoder.
     */
    private static final String PROCESSOR_VERSION = "imageio-1";

    /** The bytes are not an image at all. */
    static final String NOT_AN_IMAGE = "NOT_AN_IMAGE";

    /** A real header, over a body no decoder here can read. WebP and AVIF land here. */
    static final String FORMAT_NOT_DECODABLE = "FORMAT_NOT_DECODABLE";

    /** A real header over a body the decoder refused. The same bytes will refuse again. */
    static final String SOURCE_MALFORMED = "SOURCE_MALFORMED";

    /** The header describes a decode this platform will not pay for. */
    static final String SOURCE_TOO_LARGE_TO_DECODE = "SOURCE_TOO_LARGE_TO_DECODE";

    /** Not settled: the decoder asked for memory and did not get it. */
    static final String RENDER_OUT_OF_MEMORY = "RENDER_OUT_OF_MEMORY";

    /** Not settled: the raster decoded and the rendition would not encode. */
    static final String ENCODE_FAILED = "ENCODE_FAILED";

    @Override
    public String processorVersion() {
        return PROCESSOR_VERSION;
    }

    @Override
    public RenderOutcome render(byte[] source, List<DerivativeVariant> variants) {
        if (variants.isEmpty()) {
            return new Rendered(Map.of());
        }

        Optional<ProbedImage> probed = ImageProbe.probe(source);
        if (probed.isEmpty()) {
            return new Unsupported(NOT_AN_IMAGE);
        }
        if (!ImageCostLimits.withinBudget(probed.get())) {
            // Refused on the header, before a decoder allocates anything, and on
            // what the decode would cost rather than on how many pixels it
            // covers. Settled rather than failed: the header will say the same
            // thing on every future delivery.
            //
            // Restated here and not assumed from the upload gate on purpose.
            // This class is the only place in the module where a declared
            // dimension turns into allocated memory, and a re-render sweep over
            // rows written before that gate existed reaches it without passing
            // finalize at all.
            return new Unsupported(SOURCE_TOO_LARGE_TO_DECODE);
        }

        BufferedImage original;
        try {
            original = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException | RuntimeException unreadable) {
            return classifyDecodeFailure(unreadable);
        } catch (Error fatal) {
            // JPEGImageReader does not wrap a failed allocation the way
            // PNGImageReader does; it lets the OutOfMemoryError out as an
            // Error. Uncaught it passes through every catch in this class and
            // every catch in the worker, leaving a leased job that is re-claimed
            // on each lease expiry and never spends its attempt budget.
            //
            // Only the errors DecodeError vouches for are turned into an
            // outcome. The rest are rethrown so the worker settles this job,
            // releases the rest of its batch and lets the scheduler's error
            // handler mark the process unhealthy — not because the throw itself
            // ends anything, which it does not. See DecodeError.
            if (!DecodeError.isRecoverable(fatal)) {
                throw fatal;
            }
            log.warn("A derivative decode ran out of memory ({})", fatal.getClass().getSimpleName());
            return new Failed(RENDER_OUT_OF_MEMORY);
        }
        if (original == null) {
            // No installed reader claimed the format. WebP and AVIF land here.
            return new Unsupported(FORMAT_NOT_DECODABLE);
        }

        try {
            Map<DerivativeVariant, Rendition> renditions = new EnumMap<>(DerivativeVariant.class);
            for (DerivativeVariant variant : variants) {
                Optional<Rendition> rendition = scaleAndEncode(original, variant);
                if (rendition.isEmpty()) {
                    return new Failed(ENCODE_FAILED);
                }
                renditions.put(variant, rendition.get());
            }
            return new Rendered(Map.copyOf(renditions));
        } catch (Error fatal) {
            if (!DecodeError.isRecoverable(fatal)) {
                throw fatal;
            }
            log.warn("A derivative rescale ran out of memory ({})", fatal.getClass().getSimpleName());
            return new Failed(RENDER_OUT_OF_MEMORY);
        } finally {
            // The raster is the largest thing this method holds and the caller
            // has no use for it. Released before the encoded output goes back up
            // the stack rather than at the next collection.
            original.flush();
        }
    }

    /**
     * Whether a decoder's refusal is about the bytes or about the heap.
     *
     * <p>The distinction the original code did not draw. {@code PNGImageReader}
     * reports a failed raster allocation as {@code IIOException: Caught
     * exception during read: } with the {@code OutOfMemoryError} as its cause —
     * an {@code IOException} by the time it arrives, indistinguishable from an
     * unreadable file unless something looks at the cause. Read as "unsupported
     * format", a render that ran out of memory completed its job, cleared its
     * error columns and incremented the rendered counter.
     *
     * <p>Package-visible so it can be tested against the exact exception shape
     * the JDK produces, which cannot be provoked from a shared test JVM without
     * deciding how much heap that JVM has.
     */
    static RenderOutcome classifyDecodeFailure(Throwable failure) {
        if (DecodeError.ranOutOfMemory(failure)) {
            log.warn("A derivative decode ran out of memory (reported as {})",
                    failure.getClass().getSimpleName());
            return new Failed(RENDER_OUT_OF_MEMORY);
        }
        // A truncated or deliberately malformed file whose header passed the
        // probe. The JDK's decoders answer that with whatever the corrupt
        // structure produces — most often an index or a negative-size exception
        // out of a native reader, none of it declared. Settled rather than
        // retried: the next delivery finds the same bytes.
        //
        // The message is not logged. A decoder's own wording quotes the bytes it
        // choked on.
        log.warn("Derivative source is malformed and was refused by the decoder ({})",
                failure.getClass().getSimpleName());
        return new Unsupported(SOURCE_MALFORMED);
    }

    /** @return empty when the encoder produced nothing, which is a failure and not a format */
    private static Optional<Rendition> scaleAndEncode(BufferedImage original,
            DerivativeVariant variant) {

        int width = Math.min(variant.targetWidthPx(), original.getWidth());
        // Never upscaled. Enlarging a small photograph produces a bigger file
        // that looks worse, and the point of the variant is a size bound, not a
        // promise that every rendition is exactly that wide.
        int height = Math.max(1,
                Math.round(original.getHeight() * (float) width / original.getWidth()));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var canvas = scaled.createGraphics();
        try {
            // White behind the draw, because JPEG has no alpha channel and a
            // transparent PNG would otherwise composite onto black.
            canvas.setColor(java.awt.Color.WHITE);
            canvas.fillRect(0, 0, width, height);
            canvas.drawImage(original.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            canvas.dispose();
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(scaled, "jpg", encoded)) {
                // No writer took it, or the writer wrote nothing. Either way the
                // raster decoded and the output did not appear, which is this
                // attempt failing rather than this format being unreadable — and
                // a transient object-store or temp-file fault on the encode side
                // arrives exactly here.
                log.warn("No JPEG writer produced a {} rendition", variant.code());
                return Optional.empty();
            }
        } catch (IOException | RuntimeException failed) {
            log.warn("Derivative could not be encoded ({})", failed.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            scaled.flush();
        }
        return Optional.of(new Rendition(encoded.toByteArray(), "image/jpeg", width, height));
    }
}
