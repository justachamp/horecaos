package uz.horecaos.platform.media.api;

import java.util.List;
import java.util.Map;
import uz.horecaos.platform.media.domain.DerivativeVariant;

/**
 * Produces renditions from an original's bytes (ADR 0010).
 *
 * <p>A port, because rendering is the one part of the media module likely to
 * move: a native encoder or an out-of-process worker would be a different
 * implementation of exactly this, and neither should reach the lifecycle code.
 */
public interface ImageDerivativeRenderer {

    /**
     * Recorded on every derivative row.
     *
     * <p>It is what makes "these thumbnails look different" answerable a year
     * later, and what a re-render sweep would select on.
     */
    String processorVersion();

    /**
     * Every requested rendition, from one decode.
     *
     * <p>All the variants at once and not one call each, because the expensive
     * half of a render is turning the original into a raster and that answer is
     * the same for all three. Rendering a 300MB source variant by variant paid
     * for it three times and held the peak three times, for three outputs that
     * differ only in a scale factor.
     *
     * @param source   the whole original object
     * @param variants what is still missing for this asset, which after a
     *                 partial failure is fewer than the whole set
     */
    RenderOutcome render(byte[] source, List<DerivativeVariant> variants);

    /**
     * What became of a render, in three kinds rather than two.
     *
     * <p>The distinction {@link Unsupported} and {@link Failed} draw is the
     * whole reason this is not an {@code Optional}. An empty optional had to
     * mean both "no decoder here will ever read these bytes" and "this decode
     * ran out of memory", and a caller that cannot tell them apart has to pick
     * one behaviour for both: recording the second as a settled success, or
     * retrying the first forever. The first is what shipped.
     */
    sealed interface RenderOutcome permits Rendered, Unsupported, Failed {}

    /**
     * Every requested variant, produced.
     *
     * <p>All or nothing on purpose: a partial map would need a caller that
     * decides per variant whether the absent ones are settled or owed, which is
     * the same conflation in a smaller box. A run that produced some and then
     * failed reports {@link Failed}, and the retry renders only what the store
     * still lacks.
     */
    record Rendered(Map<DerivativeVariant, Rendition> renditions) implements RenderOutcome {}

    /**
     * Settled. These bytes will not become renderable by being tried again.
     *
     * @param reason a stable code, never a decoder's message. A decoder quotes
     *               the bytes it choked on and, often enough, the filename a
     *               customer typed (ADR 0029)
     */
    record Unsupported(String reason) implements RenderOutcome {}

    /**
     * Not settled. This attempt failed and another might not.
     *
     * @param errorCode a stable code, for the job row's {@code last_error_code}
     */
    record Failed(String errorCode) implements RenderOutcome {}

    record Rendition(byte[] content, String contentType, int widthPx, int heightPx) {}
}
