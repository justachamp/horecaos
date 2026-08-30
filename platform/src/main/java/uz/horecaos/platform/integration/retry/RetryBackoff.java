package uz.horecaos.platform.integration.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;

/**
 * Bounded exponential backoff with jitter, shared by every ADR 0006 retry loop.
 *
 * <p>Jitter is the entire reason this type exists rather than a private method
 * on each worker. Undithered exponential backoff is a function of the attempt
 * count alone, so every replica that failed during the same provider outage
 * computes the same delay from the same attempt count and wakes at the same
 * instant. The retry then arrives as a synchronised burst aimed at a dependency
 * that has just finished proving it cannot take one, and the herd re-forms on
 * every subsequent attempt because the delays stay identical.
 *
 * <p><strong>Equal jitter, not full jitter.</strong> Full jitter — a uniform
 * draw across the whole interval — decorrelates callers best, but its lower
 * bound is zero: after a ten-minute outage some caller retries almost
 * immediately, and the backoff it so carefully computed protected nothing. Equal
 * jitter keeps half the computed ceiling as a guaranteed floor and randomises
 * only the other half. That is enough spread to break the herd — the floor is
 * shared but the arrival times are not — while still honouring the wait the
 * attempt count asked for.
 *
 * <p>The random source is injectable so that ADR 0006's requirement of a
 * deterministic backoff calculation under a fixed random source is testable
 * without asserting on a distribution.
 */
public final class RetryBackoff {

    /** Beyond this the ceiling has long since saturated at the maximum. */
    private static final int MAXIMUM_EXPONENT = 30;

    private final long initialMillis;
    private final long maximumMillis;
    private final DoubleSupplier fraction;

    private RetryBackoff(Duration initialDelay, Duration maximumDelay, DoubleSupplier fraction) {
        if (initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("An initial retry delay must be positive");
        }
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("A maximum retry delay must not be shorter than the initial delay");
        }
        this.initialMillis = initialDelay.toMillis();
        this.maximumMillis = maximumDelay.toMillis();
        this.fraction = fraction;
    }

    /** The production policy, drawing from the calling thread's generator. */
    public static RetryBackoff of(Duration initialDelay, Duration maximumDelay) {
        return new RetryBackoff(
                initialDelay, maximumDelay, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** The same policy with a caller-supplied generator, so a test can seed it. */
    public static RetryBackoff randomisedBy(Duration initialDelay, Duration maximumDelay, RandomGenerator random) {
        return new RetryBackoff(initialDelay, maximumDelay, random::nextDouble);
    }

    /**
     * The delay before the attempt following {@code attemptCount} failures.
     *
     * <p>Always in {@code [ceiling/2, ceiling)} — strictly below the ceiling,
     * which is what distinguishes a jittered delay from the undithered one and
     * is the property the outbox and inbox tests assert on.
     */
    public Duration delayAfter(int attemptCount) {
        long ceiling = ceilingAfter(attemptCount).toMillis();
        long floor = ceiling / 2;
        double draw = fraction.getAsDouble();
        if (draw < 0d || draw >= 1d) {
            throw new IllegalStateException("A jitter fraction must fall in [0, 1)");
        }
        return Duration.ofMillis(floor + (long) (draw * (ceiling - floor)));
    }

    /**
     * The undithered exponential ceiling, exposed because a caller reasoning
     * about worst-case drain time needs the bound rather than one draw from it.
     */
    public Duration ceilingAfter(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), MAXIMUM_EXPONENT);
        long candidate;
        try {
            candidate = Math.multiplyExact(initialMillis, 1L << exponent);
        } catch (ArithmeticException overflow) {
            candidate = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(candidate, maximumMillis));
    }
}
