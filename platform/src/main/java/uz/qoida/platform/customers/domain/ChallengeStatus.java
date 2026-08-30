package uz.qoida.platform.customers.domain;

/**
 * Where a verification challenge ended up (ADR 0015).
 *
 * <p>Only {@link #PENDING} is live. Every other value is terminal, and the
 * transition out of {@code PENDING} is a conditional {@code UPDATE} rather than a
 * read followed by a write — which is the entire single-use guarantee: two
 * requests carrying the same correct code race on one row, and PostgreSQL decides,
 * not the order two application threads happened to read in.
 */
public enum ChallengeStatus {

    /** Issued, unspent, and inside its window. */
    PENDING,

    /** The right code arrived. A grant was minted; the code is dead. */
    VERIFIED,

    /** Every attempt was spent on a wrong code. */
    EXHAUSTED,

    /**
     * A later challenge for the same destination replaced it.
     *
     * <p>Not cosmetic. Without it, asking for three codes would leave three live
     * challenges with five attempts each, and the attempt limit would be whatever
     * an attacker was willing to pay for extra SMS rather than five.
     */
    SUPERSEDED,

    /**
     * Its window closed with nothing spent.
     *
     * <p>Bookkeeping, written by the sweeper so that a row says what became of it.
     * Nothing depends on the sweeper having run: every statement that touches a
     * live challenge tests {@code expires_at} as well as the status, because a
     * correctness rule that only holds while a background job is healthy is not a
     * rule.
     */
    EXPIRED
}
