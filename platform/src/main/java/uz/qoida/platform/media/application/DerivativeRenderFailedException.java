package uz.qoida.platform.media.application;

/**
 * A render attempt that failed, as opposed to a source that cannot be rendered
 * (ADR 0010).
 *
 * <p>Thrown rather than returned in the report, because the two outcomes belong
 * in different places: a report describes what an asset now has, and "nothing,
 * and it is nobody's fault yet" is not a description of an asset — it is an
 * instruction to the caller to come back. The worker's existing shape already
 * turns a thrown failure into a retry with a bounded attempt count, and this is
 * that, with a code specific enough for an operator to act on.
 *
 * @see uz.qoida.platform.media.api.ImageDerivativeRenderer.Failed
 */
public class DerivativeRenderFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    /**
     * @param errorCode a stable code from the renderer, never a decoder message.
     *                  It reaches {@code media.derivative_jobs.last_error_code},
     *                  a column ADR 0029 will not have carrying an uploaded
     *                  filename
     */
    public DerivativeRenderFailedException(String errorCode) {
        super("A derivative render failed: " + errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
