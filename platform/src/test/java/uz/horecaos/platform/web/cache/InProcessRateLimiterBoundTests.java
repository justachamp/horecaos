package uz.horecaos.platform.web.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * The bucket map is keyed on values the caller chooses, and some of those
 * callers are anonymous — ADR 0047's QR exchange keys on a token digest with no
 * principal behind it. So the map is a memory-exhaustion surface, and the way it
 * is bounded is itself part of the limit: an eviction rule that could be
 * provoked into forgetting a depleted bucket would let a flood reset its own
 * budget on demand, which is worse than the memory it was spending.
 */
class InProcessRateLimiterBoundTests {

    private static final Instant START = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void theMapDoesNotGrowPastItsCeiling() {
        MutableClock clock = new MutableClock(START);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.strictPerMinute(1);

        floodDistinctKeys(limiter, policy, InProcessRateLimiter.MAXIMUM_BUCKETS + 1_000);

        assertThat(limiter.check(new RateLimiter.Key("qr.exchange", null, "one-more"), policy).allowed())
                .as("a limit that fails closed must refuse rather than admit an unbounded key")
                .isFalse();
    }

    @Test
    void aFailOpenLimitStillAdmitsWhenTheCeilingIsReached() {
        MutableClock clock = new MutableClock(START);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);

        floodDistinctKeys(limiter, RateLimiter.Policy.strictPerMinute(1),
                InProcessRateLimiter.MAXIMUM_BUCKETS + 1_000);

        assertThat(limiter.check(new RateLimiter.Key("menu.read", "tenant-a", "user-1"),
                        RateLimiter.Policy.perMinute(30)).allowed())
                .as("a saturated limiter is an unavailable backend, and Policy.failOpen already "
                        + "says what each limit wants done about that")
                .isTrue();
    }

    @Test
    void aFloodCannotEvictTheBucketItHasAlreadySpent() {
        MutableClock clock = new MutableClock(START);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.strictPerMinute(1);
        RateLimiter.Key spent = new RateLimiter.Key("qr.exchange", null, "the-attackers-own-token");

        assertThat(limiter.check(spent, policy).allowed()).isTrue();
        assertThat(limiter.check(spent, policy).allowed()).isFalse();

        // Least-recently-used eviction would discard this bucket here, because
        // every one of the flood's keys is more recent than it. Ten seconds is a
        // sixth of the window, so it is nowhere near refilled.
        floodDistinctKeys(limiter, policy, InProcessRateLimiter.MAXIMUM_BUCKETS + 1_000);
        clock.advance(Duration.ofSeconds(10));

        assertThat(limiter.check(spent, policy).allowed())
                .as("a budget must not be resettable by spending memory")
                .isFalse();
    }

    @Test
    void refilledBucketsAreSweptOutSoOrdinaryTrafficNeverReachesTheCeiling() {
        MutableClock clock = new MutableClock(START);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.strictPerMinute(1);

        for (int i = 0; i < 5_000; i++) {
            limiter.check(new RateLimiter.Key("qr.exchange", null, "guest-" + i), policy);
        }

        // Past the window every one of those buckets is back at its full budget,
        // which makes it indistinguishable from a bucket that never existed.
        clock.advance(Duration.ofMinutes(2));
        limiter.check(new RateLimiter.Key("qr.exchange", null, "someone-else"), policy);

        assertThat(limiter.bucketCount())
                .as("a bucket at its full budget decides nothing, so holding it is pure cost")
                .isLessThan(100);
    }

    @Test
    void aSweptBucketIsRecreatedWithTheSameBudget() {
        MutableClock clock = new MutableClock(START);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.strictPerMinute(2);
        RateLimiter.Key key = new RateLimiter.Key("qr.exchange", null, "guest");

        limiter.check(key, policy);
        limiter.check(key, policy);
        assertThat(limiter.check(key, policy).allowed()).isFalse();

        clock.advance(Duration.ofMinutes(5));

        assertThat(limiter.check(key, policy).allowed()).isTrue();
        assertThat(limiter.check(key, policy).allowed()).isTrue();
        assertThat(limiter.check(key, policy).allowed())
                .as("eviction must give back the budget the policy states, never more")
                .isFalse();
    }

    private static void floodDistinctKeys(
            InProcessRateLimiter limiter, RateLimiter.Policy policy, int count) {
        for (int i = 0; i < count; i++) {
            limiter.check(new RateLimiter.Key("qr.exchange", null, "flood-" + i), policy);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
