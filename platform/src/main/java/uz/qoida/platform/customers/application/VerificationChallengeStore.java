package uz.qoida.platform.customers.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for verification challenges, stated as a contract rather than as a
 * class (ADR 0015).
 *
 * <p>Every other store in this module is a concrete {@code Jdbc…} class the
 * service depends on directly, and this one deliberately is not. The reason is
 * that three of the security properties in this feature are not implemented in
 * Java at all — they are conditional {@code UPDATE} statements, and the condition
 * <em>is</em> the rule:
 *
 * <ul>
 *   <li>{@link #consumeAttempt} spends an attempt only while the challenge is
 *       pending, unexpired, and under its limit;</li>
 *   <li>{@link #markVerified} succeeds for exactly one caller, which is what makes
 *       a code single-use;</li>
 *   <li>{@link #redeemGrant} succeeds for exactly one caller, which is what makes
 *       a grant single-use.</li>
 * </ul>
 *
 * <p>Writing those as a port means the rules can be stated once, here, and
 * asserted against an implementation that is not PostgreSQL as well as against the
 * one that is — including the races, which are the part a hand-run test against a
 * live database is least likely to reproduce.
 */
public interface VerificationChallengeStore {

    /** Writes a freshly issued, pending challenge. */
    void insert(NewChallenge challenge);

    /**
     * How much of this destination's issuance budget is already spent.
     *
     * <p>Counted from the rows rather than from a counter in memory. A per-replica
     * counter is not a rate limit when there are three replicas — it is three rate
     * limits, each the size of the one that was configured — and the two things
     * being protected here, an SMS bill and a brute-force oracle, are both global
     * to the destination.
     *
     * <p>Counts every challenge regardless of how it ended, so burning a
     * challenge's attempts on wrong codes does not buy a fresh budget.
     */
    IssuanceWindow issuanceWindow(UUID tenantId, String destinationHash, Instant since);

    /**
     * Retires every pending challenge for this destination.
     *
     * @return how many were retired
     */
    int supersedePending(UUID tenantId, String contactType, String destinationHash, Instant now);

    /**
     * Spends one attempt against a live challenge and returns what it holds.
     *
     * <p>The increment and the guard are one statement. A read-then-write would let
     * five concurrent guesses all observe {@code attempts_used = 0} and all
     * proceed, which turns a five-attempt limit into a five-times-concurrency one.
     *
     * @return empty when the challenge is unknown, settled, expired, or already at
     *         its limit — deliberately indistinguishable, because the caller
     *         answers all four the same way
     */
    Optional<Attempt> consumeAttempt(UUID tenantId, UUID challengeId, Instant now);

    /**
     * Settles a challenge as verified and attaches the grant, if it is still
     * pending.
     *
     * @return false when somebody else already settled it, which is the losing
     *         side of two requests carrying the same correct code
     */
    boolean markVerified(UUID tenantId, UUID challengeId, String grantHash,
            Instant grantExpiresAt, Instant now);

    /** Settles a challenge whose last attempt was spent on a wrong code. */
    void markExhausted(UUID tenantId, UUID challengeId, Instant now);

    /**
     * Removes a challenge whose code never left the building.
     *
     * <p>Only ever called when the transport refused or was unreachable. Keeping
     * the row would charge the customer's issuance budget for our outage and then
     * leave a live challenge whose code nobody can possibly know; deleting it
     * costs nothing, because a code that was never sent cannot have been used.
     *
     * <p>Guarded on the row still being pending and untouched, so the delete
     * cannot race ahead of an attempt and erase evidence of one.
     *
     * @return true when a row was removed
     */
    boolean deleteUnsent(UUID tenantId, UUID challengeId);

    /**
     * Redeems a grant, once.
     *
     * <p>Keyed on the digest alone and not on a tenant, because a redeeming caller
     * presents the secret and nothing else; the tenant and brand come back off the
     * row, so they cannot be edited into the request.
     *
     * @return empty when the digest is unknown, already redeemed, or past its
     *         window
     */
    Optional<RedeemedGrant> redeemGrant(String grantHash, Instant now);

    /**
     * Marks pending challenges whose window has closed. Bookkeeping only.
     *
     * @return how many were marked
     */
    int expirePending(Instant now, int limit);

    /**
     * Deletes settled challenges older than the cutoff.
     *
     * <p>A challenge row carries an encrypted phone number, so it is personal data
     * under ADR 0029 and cannot be kept because deleting it was never scheduled.
     * Once a challenge is settled the row's only remaining use is a short
     * investigation window.
     *
     * @return how many were deleted
     */
    int purgeSettledBefore(Instant cutoff, int limit);

    /**
     * @param destinationHash  the ADR 0029 keyed lookup hash of the normalized
     *                         destination. The rate-limit key, the supersede key,
     *                         and the only form of the number that is safe to put
     *                         in a log line
     * @param destinationValue the ADR 0029 protected form, bound to this row
     * @param codeHash         a keyed MAC over the code, never the code
     */
    record NewChallenge(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String purpose,
            String contactType,
            String destinationHash,
            String destinationValue,
            String codeHash,
            int maxAttempts,
            Instant issuedAt,
            Instant expiresAt) {
    }

    /**
     * @param lastIssuedAt when this destination was last sent a code, empty if
     *                     never
     */
    record IssuanceWindow(Optional<Instant> lastIssuedAt, int issuedInWindow) {
    }

    /**
     * @param attemptsRemaining after this attempt was spent. Zero means the wrong
     *                          answer settles the challenge
     */
    record Attempt(String codeHash, int attemptsRemaining) {
    }

    record RedeemedGrant(
            UUID challengeId,
            UUID tenantId,
            UUID brandId,
            String contactType,
            String destinationHash,
            String destinationValue) {
    }
}
