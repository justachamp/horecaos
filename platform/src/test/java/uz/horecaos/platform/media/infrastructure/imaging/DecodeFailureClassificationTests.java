package uz.horecaos.platform.media.infrastructure.imaging;

import java.io.EOFException;

import javax.imageio.IIOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.media.api.ImageDerivativeRenderer.Failed;
import uz.horecaos.platform.media.api.ImageDerivativeRenderer.Unsupported;
import uz.horecaos.platform.media.domain.DecodeError;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling "cannot" from "failed" at the point the JDK blurs them (ADR 0010).
 *
 * <p>In this package rather than beside the other media tests, because the
 * classifier is deliberately not part of the {@code ImageDerivativeRenderer}
 * port — no caller chooses it — and widening it to public to be tested would
 * make the seam look like an API.
 *
 * <p>Asserted against constructed exceptions rather than through a real decode.
 * A real {@code OutOfMemoryError} can only be provoked by deciding how much
 * heap the test JVM has, and a test that passes under one {@code -Xmx} and not
 * another is not a test of this behaviour. The shapes below were taken from a
 * reproduction: at {@code -Xmx256m}, a 311,100-byte PNG declaring 8000x5000 at
 * 16-bit RGBA produced exactly
 * {@code javax.imageio.IIOException: Caught exception during read: } caused by
 * {@code java.lang.OutOfMemoryError: Java heap space}, while the equivalent
 * JPEG let the {@code OutOfMemoryError} out untouched.
 */
class DecodeFailureClassificationTests {

    @Test
    @DisplayName("a decode that ran out of memory is a failure, not an unreadable format")
    void anOutOfMemoryWrappedInAnIoExceptionIsAFailure() {
        // The original defect, in one call. PNGImageReader answers a failed
        // raster allocation with an IIOException, so it arrives through the
        // IOException catch and used to be read as "this renderer cannot decode
        // this format" — which completed the job, cleared its error columns and
        // incremented the rendered counter.
        IIOException wrapped = new IIOException("Caught exception during read: ",
                new OutOfMemoryError("Java heap space"));

        assertThat(ImageIoDerivativeRenderer.classifyDecodeFailure(wrapped))
                .isEqualTo(new Failed("RENDER_OUT_OF_MEMORY"));
    }

    @Test
    @DisplayName("a genuinely unreadable file is still settled")
    void aCorruptStreamIsUnsupported() {
        assertThat(ImageIoDerivativeRenderer.classifyDecodeFailure(
                new IIOException("Invalid chunk length", new EOFException())))
                .isEqualTo(new Unsupported("SOURCE_MALFORMED"));
        assertThat(ImageIoDerivativeRenderer.classifyDecodeFailure(
                new ArrayIndexOutOfBoundsException(-1)))
                .isEqualTo(new Unsupported("SOURCE_MALFORMED"));
    }

    @Test
    @DisplayName("a cause chain that loops back on itself is not walked forever")
    void aSelfReferentialCauseTerminates() {
        // Throwable.initCause forbids self-reference, but a hand-written
        // getCause override does not, and this walk runs while a worker is
        // already unwinding on an allocation failure.
        Throwable looping = new IIOException("read failed") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(DecodeError.ranOutOfMemory(looping)).isFalse();
    }

    @Test
    @DisplayName("a failed heap allocation is survivable and the rest of Error is not")
    void onlyAllocationFailuresAreRecoverable() {
        // The line this change draws, and the reason it is drawn rather than
        // assumed: swallowing a process-wide exhaustion leaves a JVM that logs
        // the same failure every two seconds for as long as the container lives,
        // which is the loop being removed one level up.
        //
        // "False" means rethrow, and rethrowing does not end this process — no
        // Error thrown from a scheduled method does. It delivers the failure to
        // the scheduler's error handler, which asks ProcessHealth about it and
        // refuses traffic so the readiness probe fails and autoheal restarts the
        // container. ProcessFatalErrorTests pins that, and asserts this
        // classifier and the platform-wide one still agree.
        assertThat(DecodeError.isRecoverable(new OutOfMemoryError("Java heap space"))).isTrue();
        assertThat(DecodeError.isRecoverable(
                new OutOfMemoryError("Requested array size exceeds VM limit"))).isTrue();
        assertThat(DecodeError.isRecoverable(new StackOverflowError())).isTrue();

        assertThat(DecodeError.isRecoverable(new OutOfMemoryError("Metaspace"))).isFalse();
        assertThat(DecodeError.isRecoverable(
                new OutOfMemoryError("unable to create native thread"))).isFalse();
        assertThat(DecodeError.isRecoverable(
                new OutOfMemoryError("GC overhead limit exceeded"))).isFalse();
        assertThat(DecodeError.isRecoverable(new OutOfMemoryError())).isFalse();
        assertThat(DecodeError.isRecoverable(new NoClassDefFoundError("a codec"))).isFalse();
        assertThat(DecodeError.isRecoverable(new InternalError("the VM is unwell"))).isFalse();
    }
}
