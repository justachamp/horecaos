package uz.horecaos.platform.integration.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

/**
 * ADR 0006 requires the backoff calculation to be deterministic under a fixed
 * random source, and requires the delay to carry jitter. Both are asserted here
 * rather than on a distribution, because a test that samples a distribution is a
 * test that fails on a Friday for no reason.
 */
class RetryBackoffTests {

    private static final Duration INITIAL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM = Duration.ofMinutes(5);

    @Test
    void theCeilingDoublesPerAttemptAndThenStopsAtTheMaximum() {
        RetryBackoff backoff = RetryBackoff.of(INITIAL, MAXIMUM);

        assertThat(backoff.ceilingAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.ceilingAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.ceilingAfter(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(backoff.ceilingAfter(40))
                .as("an attempt count past the exponent clamp must not overflow into a short delay")
                .isEqualTo(MAXIMUM);
    }

    @Test
    void aDelayNeverReachesTheCeilingAndNeverFallsBelowHalfOfIt() {
        RetryBackoff never = RetryBackoff.randomisedBy(INITIAL, MAXIMUM, fixed(0d));
        RetryBackoff almost = RetryBackoff.randomisedBy(INITIAL, MAXIMUM, fixed(0.999_999d));

        assertThat(never.delayAfter(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(almost.delayAfter(4))
                .isGreaterThan(Duration.ofSeconds(7))
                .isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void theSameSeedProducesTheSameSequence() {
        assertThat(seededSequence(4321L))
                .as("ADR 0006 asks for a deterministic calculation under a fixed random source")
                .isEqualTo(seededSequence(4321L))
                .isNotEqualTo(seededSequence(9876L));
    }

    @Test
    void tenCallersAtTheSameAttemptCountDoNotAllWakeAtTheSameInstant() {
        RetryBackoff backoff = RetryBackoff.randomisedBy(
                INITIAL, MAXIMUM, new java.util.Random(20260825L));

        Set<Duration> distinct = new LinkedHashSet<>();
        for (int replica = 0; replica < 10; replica++) {
            distinct.add(backoff.delayAfter(6));
        }

        assertThat(distinct)
                .as("undithered backoff is a function of the attempt count alone, so every "
                        + "replica that failed against one outage retries in the same millisecond")
                .hasSizeGreaterThan(1);
    }

    @Test
    void aMaximumShorterThanTheInitialDelayIsRefused() {
        assertThatThrownBy(() -> RetryBackoff.of(Duration.ofSeconds(10), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryBackoff.of(Duration.ZERO, MAXIMUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static java.util.List<Duration> seededSequence(long seed) {
        RetryBackoff backoff = RetryBackoff.randomisedBy(INITIAL, MAXIMUM, new java.util.Random(seed));
        return java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(backoff::delayAfter)
                .toList();
    }

    /** A generator whose only job is to return one fraction, so a bound is exact. */
    private static RandomGenerator fixed(double fraction) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble() {
                return fraction;
            }
        };
    }
}
