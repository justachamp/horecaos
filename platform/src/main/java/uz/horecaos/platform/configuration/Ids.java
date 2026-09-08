package uz.horecaos.platform.configuration;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.UUIDClock;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * ADR 0076: row identity for a newly minted row is RFC 9562 UUID version 7, not
 * {@link UUID#randomUUID()}. Nothing else changes — {@code UUID.nameUUIDFromBytes}
 * derived ids stay version 3, and lease/fencing tokens (a worker's own
 * {@code claim_token}, {@code processingToken}, {@code leaseToken}) stay
 * {@link UUID#randomUUID()}; neither is "row identity" in the sense this class
 * exists for.
 *
 * <p><strong>Which method to call:</strong>
 *
 * <ul>
 *   <li>{@link #newId()} for the primary key of a new row. It sorts
 *       approximately by creation time, which is the entire point (see the
 *       ADR): inserts land at the right-hand edge of the index instead of
 *       scattering, and a keyset cursor on the id alone approximates one on
 *       {@code (created_at, id)}.
 *   <li>{@link #newUndisclosedTimestampId()} for an identifier that is handed
 *       to an outside party where the true creation time is exactly the fact
 *       that must not leak — today that is {@code customer.customer_accounts},
 *       whose id would otherwise read as a signup date to any tenant staff
 *       member who can see it. Use this only where that disclosure is the
 *       concern; it buys privacy by giving up the ordering property, so
 *       reaching for it by default would quietly undo the reason this class
 *       exists.
 * </ul>
 */
public final class Ids {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * {@code com.fasterxml.uuid:java-uuid-generator} (JUG), the maintained
     * library the owner's "a dependency if one exists on Maven Central, else
     * hand-rolled" answer asked for (ADR 0076, resolved 2026-09-08).
     * {@code Generators.timeBasedEpochGenerator(...)} implements RFC 9562 v7
     * ("time-based epoch"): within one millisecond it increments its entropy
     * rather than re-randomising it, so a minting burst sorts in mint order —
     * exactly the property the ADR's own hand-rolled fallback spec asked for,
     * already implemented and already tested by a library with years of
     * production use, which is why hand-rolling was not the first choice. Its
     * only runtime dependency is {@code slf4j-api}, already on this
     * application's classpath via Spring Boot's own logging, so this adds no
     * meaningful transitive surface.
     *
     * <p>Two adjustments over the bare factory method:
     *
     * <ul>
     *   <li>The clock is {@link NeverGoesBackwardClock}, not the system clock
     *       directly, so an NTP step backwards cannot make this generator emit
     *       an id that sorts before one it already issued — the ADR's own
     *       explicit requirement, which the library does not provide by
     *       itself (its generator simply re-reads {@code System.currentTimeMillis()}
     *       and re-randomises whenever that value does not repeat exactly).
     *   <li>On same-millisecond entropy exhaustion (JUG 5.2's fix for its own
     *       issue #124) the generator throws {@link IllegalStateException}
     *       rather than emitting an out-of-order id or blocking the caller.
     *       The counter space involved is 74 bits; exhausting it within one
     *       millisecond is not a realistic event, so failing loudly instead
     *       of silently waiting is an acceptable trade — a mint burst that
     *       could reach it is not a burst this platform will ever produce.
     * </ul>
     */
    private static final TimeBasedEpochGenerator GENERATOR =
            Generators.timeBasedEpochGenerator(SECURE_RANDOM, new NeverGoesBackwardClock());

    private Ids() {}

    /** A new row's primary key: RFC 9562 UUID version 7, time-ordered. */
    public static UUID newId() {
        return GENERATOR.generate();
    }

    /**
     * A version-7-shaped id whose timestamp component carries no information
     * about when it was minted.
     *
     * <p>The owner's answer to the ADR's PII open input (2026-09-08): a v7 id
     * discloses the millisecond it was created to anyone holding it, which for
     * {@code customer.customer_accounts} is a signup date visible to any
     * tenant staff member who can see the id. Rather than mint that id as v4
     * (and give up the schema-uniform "new rows are v7" property) or as a real
     * v7 (and leak the date), every one of its 74 non-version, non-variant
     * bits — including the 48 that would otherwise be a millisecond timestamp
     * — is drawn fresh from a {@link SecureRandom}. That is not a jitter
     * around the true instant; it is drawn uniformly across the entire 48-bit
     * field, a span of roughly 8.9 million years, so there is no bucket —
     * not a day, not a year — that the true creation time is more likely to
     * fall in than any other. Only the version nibble ({@code 0b0111}) and the
     * variant bits ({@code 0b10}) are not random, which is what keeps the
     * value shaped like every other row identity this platform mints.
     *
     * <p>The name is deliberate: a call site that reads {@code Ids.newId()}
     * says nothing about why, so a value that must not disclose when it was
     * made gets its own method instead of a flag on the ordinary one.
     *
     * <p>Apply this where an identifier is handed to an outside party and its
     * creation time is itself sensitive. Today that is
     * {@code customer.customer_accounts} only; nothing else has been moved to
     * it without saying so in ADR 0076's implementation notes.
     */
    public static UUID newUndisclosedTimestampId() {
        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);
        randomBytes[6] = (byte) ((randomBytes[6] & 0x0f) | 0x70); // version 7
        randomBytes[8] = (byte) ((randomBytes[8] & 0x3f) | 0x80); // variant 0b10 (IETF)

        long mostSigBits = 0;
        for (int i = 0; i < 8; i++) {
            mostSigBits = (mostSigBits << 8) | (randomBytes[i] & 0xffL);
        }
        long leastSigBits = 0;
        for (int i = 8; i < 16; i++) {
            leastSigBits = (leastSigBits << 8) | (randomBytes[i] & 0xffL);
        }
        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * A {@link UUIDClock} that never reports a time earlier than one it has
     * already reported.
     *
     * <p>{@link TimeBasedEpochGenerator} only guards ordering <em>within</em>
     * a repeated millisecond (by incrementing its entropy); if the clock it
     * reads moves backwards — an NTP step is the realistic case — it treats
     * the smaller value as a new, distinct millisecond and mints entropy that
     * can sort before an id already issued. Clamping the reported time to the
     * high-water mark closes that gap: {@link #currentTimeMillis()} is
     * non-decreasing by construction, so every id this generator produces is
     * greater than or equal to, in mint order, the one before it, regardless
     * of what the system clock does.
     *
     * <p>Package-private, not private: {@code IdsTests} constructs one over a
     * fake, deliberately-backward-stepping time source to prove the clamp
     * without touching the real system clock, which is the only way to make
     * that behavior a deterministic test rather than a wait-and-hope one.
     */
    static final class NeverGoesBackwardClock extends UUIDClock {
        private final LongSupplier observedTimeMillis;
        private final AtomicLong highWaterMarkMillis = new AtomicLong(Long.MIN_VALUE);

        NeverGoesBackwardClock() {
            this(System::currentTimeMillis);
        }

        NeverGoesBackwardClock(LongSupplier observedTimeMillis) {
            this.observedTimeMillis = observedTimeMillis;
        }

        @Override
        public long currentTimeMillis() {
            long observed = observedTimeMillis.getAsLong();
            return highWaterMarkMillis.updateAndGet(previous -> Math.max(previous, observed));
        }
    }
}
