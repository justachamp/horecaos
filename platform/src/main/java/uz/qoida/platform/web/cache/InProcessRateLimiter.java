package uz.qoida.platform.web.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiting inside one replica (ADR 0033).
 *
 * <p>Deliberately approximate: with N replicas the effective global limit is
 * roughly N times the configured budget, so budgets are set conservatively. That
 * is accepted while the replica count is small, and it is why the limiter sits
 * behind a port — moving enforcement to a shared Valkey counter later changes
 * configuration, not call sites.
 *
 * <p><strong>The bucket map is bounded, and how it is bounded matters.</strong>
 * Some keys are chosen by the caller and reached without authentication — a QR
 * token digest is one — so an unbounded map is an anonymous caller sizing the
 * heap. The obvious defences are both wrong. Least-recently-used eviction is
 * exactly backwards: a flood's own bucket is the most recently used, so LRU
 * discards the honest tenants' buckets and keeps the attacker's, and once the
 * flood pauses long enough its bucket is the one that survives. A plain TTL on
 * last-touch has the same hole, because a depleted bucket that is left alone for
 * a window is the one an attacker most wants forgotten.
 *
 * <p>So eviction removes exactly one thing: a bucket that has refilled to its
 * full budget. Such a bucket is behaviourally identical to a bucket that does
 * not exist, so dropping it cannot change any decision — which is what makes it
 * ungameable. A depleted bucket is never dropped to make room; when the ceiling
 * is reached with nothing spare to discard, the limiter is a backend that has
 * become unavailable, and {@link Policy#failOpen()} already says what each limit
 * wants done about that.
 */
@Component
public class InProcessRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InProcessRateLimiter.class);

    /**
     * At roughly three hundred bytes an entry — key string, map node, bucket —
     * this is some fifteen megabytes at the ceiling, which the replica can lose
     * without noticing. Reaching it at all takes a flood: an entry only survives
     * eviction while it is depleted, so an attacker has to spend a whole budget
     * on every key they want to keep resident.
     */
    static final int MAXIMUM_BUCKETS = 50_000;

    /**
     * How often the refilled buckets are swept out. Short enough that ordinary
     * traffic never approaches the ceiling, long enough that the scan is not on
     * the hot path of every request.
     */
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> nextSweep;
    private final AtomicReference<Instant> nextSaturationLog;

    public InProcessRateLimiter(Clock clock) {
        this.clock = clock;
        this.nextSweep = new AtomicReference<>(clock.instant().plus(SWEEP_INTERVAL));
        this.nextSaturationLog = new AtomicReference<>(Instant.MIN);
    }

    @Override
    public Decision check(Key key, Policy policy) {
        String canonical = key.canonical() + "@" + policy.permits() + "/" + policy.window();

        // Compare-and-set rather than updateAndGet, because the decision depends
        // on whether this attempt actually consumed a token, which an update
        // function cannot report back.
        while (true) {
            Instant now = clock.instant();
            sweepIfDue(now);

            Bucket current = buckets.get(canonical);
            if (current == null) {
                if (buckets.size() >= MAXIMUM_BUCKETS) {
                    return saturated(policy, now);
                }
                Bucket fresh = new Bucket(policy, policy.permits(), now);
                Bucket raced = buckets.putIfAbsent(canonical, fresh);
                current = raced == null ? fresh : raced;
            }

            double available = refill(current, policy, now);
            if (available < 1) {
                return Decision.denied(retryAfter(policy));
            }
            Bucket next = new Bucket(policy, available - 1, now);
            if (buckets.replace(canonical, current, next)) {
                return Decision.allowed((long) Math.floor(next.tokens()));
            }
        }
    }

    /** How many buckets are resident. The bound is the point, so it is assertable. */
    int bucketCount() {
        return buckets.size();
    }

    /**
     * Throttled, and throttled by the same clock the buckets use.
     *
     * <p>The scan is linear in the map, so running it on every request once the
     * ceiling is reached would hand a flood a far cheaper amplification than the
     * memory it was denied.
     */
    private void sweepIfDue(Instant now) {
        Instant due = nextSweep.get();
        if (now.isBefore(due) || !nextSweep.compareAndSet(due, now.plus(SWEEP_INTERVAL))) {
            return;
        }
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            if (refill(bucket, bucket.policy(), now) >= bucket.policy().permits()) {
                // Two-argument remove, so a bucket that was spent between the
                // test and the removal keeps its state rather than being handed
                // back as a fresh budget. Tokens only fall and the refill instant
                // only advances, so the value that was tested cannot reappear.
                buckets.remove(entry.getKey(), bucket);
            }
        }
    }

    /**
     * The ceiling has been reached and every resident bucket is still owed
     * tokens, so there is nothing that can be discarded without forgetting a
     * decision.
     */
    private Decision saturated(Policy policy, Instant now) {
        Instant due = nextSaturationLog.get();
        if (!now.isBefore(due) && nextSaturationLog.compareAndSet(due, now.plus(SWEEP_INTERVAL))) {
            log.warn("Rate limiter is holding its ceiling of {} depleted buckets; limits are "
                    + "failing {} until it drains", MAXIMUM_BUCKETS, policy.failOpen() ? "open" : "closed");
        }
        return policy.failOpen() ? Decision.allowed(0) : Decision.denied(retryAfter(policy));
    }

    private static double refill(Bucket bucket, Policy policy, Instant now) {
        double elapsedSeconds = Duration.between(bucket.lastRefill(), now).toNanos() / 1_000_000_000.0;
        double windowSeconds = Math.max(0.001, policy.window().toMillis() / 1000.0);
        double perSecond = policy.permits() / windowSeconds;
        return Math.min(policy.permits(), bucket.tokens() + elapsedSeconds * perSecond);
    }

    private static Duration retryAfter(Policy policy) {
        long seconds = Math.max(1, policy.window().toSeconds() / Math.max(1, policy.permits()));
        return Duration.ofSeconds(seconds);
    }

    /**
     * Immutable, and stored in the map directly rather than behind a mutable
     * holder, so both the consume and the eviction can be a compare-and-set
     * against the exact state that was read.
     */
    private record Bucket(Policy policy, double tokens, Instant lastRefill) { }
}
