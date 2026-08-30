package uz.horecaos.platform.media.domain;

import java.util.Locale;

/**
 * Which {@link Error}s a render may survive, and which must end the process
 * (ADR 0010).
 *
 * <p>Catching {@code Error} is normally wrong, and this class exists so that
 * doing it here is a decision with a written reason rather than a broad
 * {@code catch (Throwable)} somebody added to stop a log filling up.
 *
 * <p><b>Why any of it is caught.</b> An image decoder is the one place in this
 * platform where an untrusted party picks the size of an allocation. The JDK's
 * two readers disagree about what to do when that allocation fails:
 * {@code PNGImageReader} wraps the {@code OutOfMemoryError} in an
 * {@code IIOException}, so it arrives as a checked exception, while
 * {@code JPEGImageReader} lets it propagate untouched. A worker that handles
 * only the first has a job that finishes and a job that never does, from the
 * same class of input — and the one that never does keeps its lease, is
 * re-claimed on every expiry, and never consumes its attempt budget, because
 * the attempt limit is only consulted on a path an {@code Error} does not take.
 *
 * <p><b>Why not all of it.</b> A failed heap allocation is a property of one
 * input: the allocation did not happen, nothing was retained, and the decoder's
 * partial raster is unreachable the moment the frame unwinds. The next job is
 * as likely to succeed as if this one had never run. That is not true of the
 * rest of {@code Error}. Metaspace exhaustion, a failure to create a native
 * thread, direct-buffer exhaustion and "GC overhead limit exceeded" are all
 * process-wide conditions the next job will meet too; a {@code LinkageError} or
 * {@code NoClassDefFoundError} means this build cannot run at all; an
 * {@code InternalError} or {@code UnknownError} means the VM itself is unwell.
 * Swallowing any of those leaves a process that logs the same failure every two
 * seconds for as long as the container lives, which is the failure mode this
 * whole change exists to remove — not one to reintroduce one level up.
 *
 * <p><b>What "false" actually causes, which is not what it used to say.</b> An
 * earlier version of this text claimed that rethrowing let the JVM die so an
 * orchestrator would restart it. It does not, and never did: Spring wraps every
 * {@code @Scheduled} method in {@code DelegatingErrorHandlingRunnable}, which
 * catches {@code Throwable} — {@code Error} included — and hands it to the
 * scheduler's error handler, and no path from there ends the process or cancels
 * the schedule. What a rethrow does is deliver the error to that handler, which
 * is the only place on the platform that can act on it: it asks
 * {@code configuration.ProcessHealth} whether the failure is process-fatal, and
 * a fatal one publishes {@code ReadinessState.REFUSING_TRAFFIC} so
 * {@code /actuator/health/readiness} goes down, the container's HEALTHCHECK
 * fails, and {@code autoheal} restarts it. The restart is real; it arrives in
 * about a minute rather than instantly, and it arrives because something asked
 * for it rather than because a thread threw.
 *
 * <p>So false means: settle this job, release the rest of the batch, and put the
 * error where it will be read. It does not mean the next line never runs.
 *
 * <p>{@code ProcessHealth} draws the same line for the whole platform and is the
 * authority on it; this classifier is the decode-side statement of it, kept here
 * because {@code media.domain} may not reach into {@code configuration} and
 * because the {@code IIOException}-wrapping case below is nobody else's problem.
 * {@code ProcessFatalErrorTests} asserts the two agree.
 *
 * <p>{@code StackOverflowError} is on the recoverable side deliberately: a
 * parser recursing through a nested container structure exhausts one thread's
 * stack, and unwinding restores it exactly.
 */
public final class DecodeError {

    private DecodeError() {
    }

    /**
     * @return true when this error describes what the input asked for rather
     *         than what the process has left, and the caller may record it and
     *         carry on. False means rethrow — which delivers the error to the
     *         scheduler's error handler and, through it, to the readiness probe;
     *         see this class's own documentation for why that is not the same as
     *         ending the process
     */
    public static boolean isRecoverable(Error error) {
        if (error instanceof StackOverflowError) {
            return true;
        }
        if (!(error instanceof OutOfMemoryError)) {
            return false;
        }
        String message = error.getMessage();
        if (message == null) {
            // An OutOfMemoryError with no message says nothing about which pool
            // ran out. Treated as the process being in trouble, because that is
            // the assumption whose worst case is a restart rather than a spin.
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("java heap space")
                || normalized.contains("requested array size exceeds vm limit");
    }

    /**
     * Whether a decoder's exception is a failed allocation wearing a checked
     * exception's clothes.
     *
     * <p>{@code PNGImageReader} answers a failed raster allocation with
     * {@code IIOException: Caught exception during read: } and the
     * {@code OutOfMemoryError} as its cause. Caught as an {@code IOException}
     * and read as "this renderer cannot decode this format", that is a render
     * that ran out of memory being recorded as a completed job — the original
     * defect. The cause chain is the only thing that tells the two apart.
     */
    public static boolean ranOutOfMemory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OutOfMemoryError) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
