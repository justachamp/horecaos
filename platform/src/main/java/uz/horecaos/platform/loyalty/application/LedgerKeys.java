package uz.horecaos.platform.loyalty.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency keys two classes have to agree on (ADR 0046).
 *
 * <p>Only one so far, and it is here because it was wrong in both places at once
 * and in the same way.
 */
final class LedgerKeys {

    private LedgerKeys() {
    }

    /**
     * The key of the {@code EXPIRY} entry that destroys what a lot still holds.
     *
     * <p><strong>It names the expiry, not the lot.</strong> It used to be
     * {@code EXPIRY:<lot>} alone, deliberately shared between the expiry sweep
     * and {@code LoyaltyAdjustmentService.merge} so that "whichever of the two
     * gets there first writes the one entry". The sharing was never what made
     * the movement happen once — {@code expireLot} is, because it carries the
     * remaining the caller read into its WHERE clause and so the loser of that
     * race matches nothing and moves nothing. The key was doing no work, and it
     * was doing harm: a lot can legitimately be expired twice.
     *
     * <p>The second expiry is not hypothetical. The repair arm of
     * {@code expiredLots} exists precisely to find a lot that is already
     * {@code EXPIRED} and holds value again — points returned to it by a refund
     * after its date had passed. That lot already has an {@code EXPIRY:<lot>}
     * entry from the first time. Under the old key the second destruction found
     * the key used, {@code appendEntry} answered false, the sweep discarded the
     * answer, and the balance came down with no entry to explain it: the exact
     * defect the return path was fixed for, arriving by the other door.
     *
     * <p>The instant makes the key an event rather than a lot. Two expiries of
     * one lot cannot share an instant, because the first sets its remaining to
     * zero and the second cannot happen until a later transaction puts value
     * back. A retried transaction recomputes the whole key from a clock that has
     * moved, and rightly: its first attempt committed nothing, so the lot still
     * holds the value and {@code expireLot} lets exactly one attempt through.
     */
    static String expiry(UUID lotId, Instant at) {
        return "EXPIRY:" + lotId + ":" + at.toEpochMilli();
    }
}
