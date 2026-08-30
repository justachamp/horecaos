package uz.qoida.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What this plan's sourcing has already tried (ADR 0014).
 *
 * <p>The planner is a pure function and this is the state it is a function of.
 * Held as a value passed in rather than read from a field, so that the same
 * inputs always produce the same decision and a decision can be replayed from
 * stored evidence — which is what ADR 0014 means by a selection that is
 * "explainable and reproducible".
 *
 * <p>Durably this is the {@code fulfillment.delivery_sourcing_jobs} checkpoint
 * and the {@code assignment_attempts} rows, neither of which exists in any
 * migration. Until they do, a caller has to carry this across a sourcing run
 * itself, which is exactly as far as sourcing can go without the schema.
 *
 * @param startedAt        when the first attempt for this plan was made, not
 *                         when this particular tick ran. The fleet budget is
 *                         measured from here, so a scheduler that wakes late
 *                         does not hand the fleet extra time
 * @param offeredCouriers  the couriers already offered this order in this run.
 *                         A courier who declined is not asked twice: the second
 *                         ask costs another offer TTL and answers a question
 *                         already answered. Membership and size are what the
 *                         planner reads, never order
 * @param outstandingOffer the courier holding an unexpired offer, or null
 * @param offerExpiresAt   when that offer lapses, or null
 * @param attemptedPartners bindings already tried and refused, so the fallback
 *                         walks down the list instead of retrying the first
 * @param uncertainAttempt true when a partner attempt ended UNCERTAIN and has
 *                         not been reconciled. ADR 0014: do not book a fallback
 *                         while the first provider may have accepted
 */
public record SourcingProgress(
        Instant startedAt,
        Set<UUID> offeredCouriers,
        UUID outstandingOffer,
        Instant offerExpiresAt,
        Set<UUID> attemptedPartners,
        boolean uncertainAttempt) {

    public SourcingProgress {
        Objects.requireNonNull(startedAt, "A sourcing start instant is required");
        offeredCouriers = offeredCouriers == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(offeredCouriers));
        attemptedPartners = attemptedPartners == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(attemptedPartners));
        if ((outstandingOffer == null) != (offerExpiresAt == null)) {
            // An offer with no expiry never lapses and the plan waits forever; an
            // expiry with no courier is a wait for nobody. Both are the same bug
            // seen from different ends, and both leave food on a pass.
            throw new IllegalArgumentException(
                    "An outstanding offer and its expiry are present together or not at all");
        }
    }

    /** The first tick of a plan that has tried nothing. */
    public static SourcingProgress starting(Instant now) {
        return new SourcingProgress(now, Set.of(), null, null, Set.of(), false);
    }

    public SourcingProgress withOffer(UUID courierId, Instant expiresAt) {
        Set<UUID> offered = new LinkedHashSet<>(offeredCouriers);
        offered.add(courierId);
        return new SourcingProgress(startedAt, offered, courierId, expiresAt,
                attemptedPartners, uncertainAttempt);
    }

    /** The offer lapsed or was declined. The courier stays on the offered list. */
    public SourcingProgress withoutOffer() {
        return new SourcingProgress(startedAt, offeredCouriers, null, null,
                attemptedPartners, uncertainAttempt);
    }

    public SourcingProgress withPartnerAttempt(UUID bindingId, boolean uncertain) {
        Set<UUID> attempted = new LinkedHashSet<>(attemptedPartners);
        attempted.add(bindingId);
        return new SourcingProgress(startedAt, offeredCouriers, outstandingOffer, offerExpiresAt,
                attempted, uncertainAttempt || uncertain);
    }

    public boolean hasLiveOffer(Instant now) {
        return outstandingOffer != null && now.isBefore(offerExpiresAt);
    }
}
