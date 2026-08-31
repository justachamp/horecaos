package uz.horecaos.platform.customers.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Persistence for customer sessions, stated as a contract (ADR 0051).
 *
 * <p>A port for the reason {@link VerificationChallengeStore} is one: the
 * interesting rules here are conditions in SQL rather than branches in Java, and
 * a port lets them be driven past their edges without a container.
 *
 * <p><strong>{@link #find} deliberately returns ended sessions too.</strong> The
 * obvious shape is a query that filters on {@code expires_at > now AND revoked_at
 * IS NULL} and answers empty for everything else, and it is wrong here: a
 * customer whose token expired mid-basket would then be indistinguishable from
 * somebody who never signed in, and the storefront would show them the front door
 * instead of "your session ended, sign in again". Whether that distinction is an
 * oracle is a fair question and the answer is no: the caller must already hold a
 * 256-bit token to ask, so what they learn about a value they guessed is nothing,
 * because they will not guess one.
 */
public interface CustomerSessionStore {

    /** Writes a freshly minted session. */
    void insert(NewSession session);

    /**
     * The session behind a digest, live or not.
     *
     * <p>Keyed on the digest alone and not on a tenant, because a caller presents
     * the token and nothing else. The tenant comes back off the row and is checked
     * against the path afterwards, so it cannot be edited into the request.
     */
    Optional<StoredSession> find(String tokenHash);

    /**
     * Ends one session.
     *
     * @return false when it was already ended, which is what a second sign-out tap
     *         is rather than an error
     */
    boolean revoke(String tokenHash, Instant now);

    /**
     * Ends every live session an account has.
     *
     * <p>Not called from a request path today. It exists because "I lost my phone"
     * has to have an answer that is one statement rather than an investigation,
     * and because a governed identity-mode change should be able to end the
     * sessions it invalidates rather than leaving them to fail one by one.
     *
     * @return how many were ended
     */
    int revokeForAccount(UUID tenantId, UUID accountId, Instant now);

    /**
     * Deletes sessions that ended before the cutoff.
     *
     * <p>Deleted rather than kept: an expired session answers no question that is
     * worth a row per sign-in per customer forever, and the audit record of the
     * sign-in is elsewhere and outlives it.
     *
     * @return how many were deleted
     */
    int purgeEndedBefore(Instant cutoff, int limit);

    /**
     * @param identityPartitionBrandId null under {@code TENANT_SHARED}, the brand
     *                                 under {@code BRAND_ISOLATED}
     */
    record NewSession(
            UUID sessionId,
            UUID tenantId,
            UUID brandId,
            UUID accountId,
            @Nullable UUID identityPartitionBrandId,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt) {}

    /**
     * A session row as stored, including how it ended if it has.
     *
     * @param revokedAt set by sign-out or by an operator, and kept separate from
     *                  {@code expiresAt} so that an early end reads as one
     */
    record StoredSession(
            UUID sessionId,
            UUID tenantId,
            UUID brandId,
            UUID accountId,
            @Nullable UUID identityPartitionBrandId,
            Instant issuedAt,
            Instant expiresAt,
            @Nullable Instant revokedAt) {}
}
