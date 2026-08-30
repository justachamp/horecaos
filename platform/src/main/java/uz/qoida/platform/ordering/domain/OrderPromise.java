package uz.qoida.platform.ordering.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * When the food was promised, and what produced that answer (ADR 0036).
 *
 * <p>Decided once, at checkout, and never recomputed. ADR 0036 calls the promised
 * time "the most complained-about number in food delivery", and a number that
 * contested has to be evidence rather than a derivation: the bands it came from
 * get edited, and a promise re-derived next week from this week's bands would
 * quietly rewrite what a customer was told — including inside the report that
 * measures whether the restaurant kept it.
 *
 * <p>The components are kept apart from the total so a promise can be explained
 * and not merely asserted. "Why 55 minutes?" is answerable from the order row:
 * 35 in the kitchen, 20 on the road.
 *
 * <p>Lateness is not stored anywhere, here or in the database. It is
 * {@link #lateAt} — a predicate over this promise and a status, evaluated at read
 * time. A stored flag would need a job to maintain it, would be wrong between
 * runs of that job, and would let two readers give different answers to the same
 * question depending on whether they trusted the flag or the timestamps.
 *
 * @param promisedAt    absolute, because a duration is only meaningful against the
 *                      instant it was quoted from, and that instant stops being
 *                      {@code created_at} at the first amendment
 * @param prepMinutes   the kitchen component; absent unless the basis derived the
 *                      promise from a duration
 * @param travelMinutes the road component. Absent on a delivery order means travel
 *                      was <em>not modelled</em> — ADR 0037's zone model was not
 *                      built when the order was taken — and not that it was zero
 */
public record OrderPromise(
        Instant promisedAt,
        PromiseBasis basis,
        Integer prepMinutes,
        Integer travelMinutes) {

    /**
     * What a branch is quoted at when no band covers the instant.
     *
     * <p>Deliberately unflattering. A fallback that quotes optimistically turns a
     * configuration gap into a broken promise to a customer, whereas one that
     * quotes conservatively turns it into a pleasant surprise and a branch that
     * eventually notices its bands are wrong.
     */
    public static final int DEFAULT_PREP_MINUTES = 45;

    private static final int MAX_MINUTES = 1440;

    public OrderPromise {
        Objects.requireNonNull(basis, "A promise must say what produced it");

        // Mirrors ck_order_promise_pairing. Both ends are asserted here as well as
        // in the schema because a constraint violation surfacing as a driver error
        // at the end of a checkout transaction is a far worse diagnostic than a
        // failure at the point of construction.
        if ((basis == PromiseBasis.NOT_PROMISED) != (promisedAt == null)) {
            throw new IllegalArgumentException(
                    "A promise with a basis needs a time and a time needs a basis: " + basis);
        }

        // Mirrors ck_order_promise_components.
        if (basis.isDerivedFromDuration() ? prepMinutes == null : prepMinutes != null) {
            throw new IllegalArgumentException(
                    "Preparation minutes belong to a duration-derived basis only, not " + basis);
        }
        if (basis == PromiseBasis.NOT_PROMISED && travelMinutes != null) {
            throw new IllegalArgumentException("An absent promise has no travel component");
        }

        requireInRange(prepMinutes, "Preparation");
        requireInRange(travelMinutes, "Travel");
    }

    private static void requireInRange(Integer minutes, String what) {
        if (minutes != null && (minutes < 0 || minutes > MAX_MINUTES)) {
            throw new IllegalArgumentException(
                    what + " minutes must be between 0 and " + MAX_MINUTES + ", was " + minutes);
        }
    }

    /** No band, no default, no promise. The honest answer, and a visible one. */
    public static OrderPromise notPromised() {
        return new OrderPromise(null, PromiseBasis.NOT_PROMISED, null, null);
    }

    /**
     * A promise built by adding a preparation estimate, and optionally travel, to
     * the moment of checkout.
     *
     * @param travelMinutes null while ADR 0037 is unbuilt
     */
    public static OrderPromise from(Instant placedAt, PromiseBasis basis, int prepMinutes,
            Integer travelMinutes) {

        if (!basis.isDerivedFromDuration()) {
            throw new IllegalArgumentException(basis + " is not derived from a duration");
        }
        Duration total = Duration.ofMinutes(prepMinutes)
                .plusMinutes(travelMinutes == null ? 0 : travelMinutes);
        return new OrderPromise(placedAt.plus(total), basis, prepMinutes, travelMinutes);
    }

    /**
     * Assembles the promise ADR 0036 describes, from a band, an item override and
     * a travel component.
     *
     * <p>A pure function of its inputs, so the rule can be tested without a
     * database and read without following three collaborators. The rule itself:
     *
     * <ol>
     *   <li>the branch's baseline is its band, or {@link #DEFAULT_PREP_MINUTES}
     *       when no band covers this instant;</li>
     *   <li>a slower item overrides that baseline, because the order is ready when
     *       its slowest dish is;</li>
     *   <li>travel is added on top, when it is known at all.</li>
     * </ol>
     *
     * <p>Every branch takes a maximum, never a sum, and every fallback is
     * conservative. Those two properties are what stop this from being the number
     * ADR 0036 warns about: a promise that is quietly optimistic is broken in front
     * of a customer, while one that is quietly generous is only ever a surprise.
     *
     * <p>Note that an override <em>below</em> the baseline does not shorten the
     * promise. A dish that cooks in five minutes does not make the kitchen's
     * twenty-five-minute queue disappear.
     *
     * @param bandMinutes   the resolved preparation band, null when none covered
     *                      this instant
     * @param itemOverride  the slowest ordered item's override, null when no
     *                      ordered item carries one
     * @param travelMinutes ADR 0037's road estimate; null while that model is
     *                      unbuilt, which every delivery order is today
     */
    public static OrderPromise assemble(Instant placedAt, Integer bandMinutes,
            Duration itemOverride, Integer travelMinutes) {

        int baseline = bandMinutes != null ? bandMinutes : DEFAULT_PREP_MINUTES;
        PromiseBasis basis = bandMinutes != null
                ? PromiseBasis.PREPARATION_BAND : PromiseBasis.PLATFORM_DEFAULT;

        if (itemOverride != null) {
            // Rounded up. A forty-and-a-half-minute dish that quotes forty is late
            // by construction, every single time it is ordered.
            long overrideMinutes = (itemOverride.toSeconds() + 59) / 60;
            if (overrideMinutes > baseline) {
                baseline = (int) Math.min(overrideMinutes, MAX_MINUTES);
                basis = PromiseBasis.ITEM_OVERRIDE;
            }
        }
        return from(placedAt, basis, baseline, travelMinutes);
    }

    public Optional<Instant> at() {
        return Optional.ofNullable(promisedAt);
    }

    public boolean isPromised() {
        return promisedAt != null;
    }

    /**
     * Whether this order is late, as of one instant.
     *
     * <p>Terminal statuses are never late. A completed order was handed over,
     * whenever that happened; a cancelled one has nobody waiting for it. Reporting
     * on how badly a completed order missed its promise is a different question,
     * asked of {@code completed_at} and answered by ADR 0043 rather than here.
     */
    public boolean lateAt(Instant now, OrderStatus status) {
        return promisedAt != null && !status.terminal() && now.isAfter(promisedAt);
    }
}
