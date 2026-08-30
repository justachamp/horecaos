package uz.horecaos.platform.customers.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.customers.application.VerificationChallengeStore;
import uz.horecaos.platform.customers.domain.ChallengeStatus;

/**
 * Verification challenges in PostgreSQL (ADR 0015).
 *
 * <p>The three single-use rules are statements, not Java. {@link #consumeAttempt},
 * {@link #markVerified} and {@link #redeemGrant} are each one conditional
 * {@code UPDATE … RETURNING}: the row is read, tested and changed in one
 * operation, under one row lock, so two concurrent requests are settled by
 * PostgreSQL rather than by whichever thread read first. Expressed as
 * read-then-write in the service, every one of them would be a race that hands out
 * two grants, or five concurrent guesses for the price of one attempt.
 *
 * <p>Every statement carries the tenant except {@link #redeemGrant}, which is
 * keyed on the grant digest alone — the redeeming caller presents the secret and
 * nothing else, and the tenant comes back off the row so it cannot be edited into
 * the request.
 */
@Repository
public class JdbcVerificationChallengeStore implements VerificationChallengeStore {

    private final JdbcClient jdbc;

    public JdbcVerificationChallengeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NewChallenge challenge) {
        jdbc.sql("""
                INSERT INTO customer.verification_challenges (
                    id, tenant_id, brand_id, purpose, contact_type, destination_hash,
                    destination_encrypted, code_hash, attempts_used, max_attempts, status,
                    issued_at, expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :purpose, :contactType, :destinationHash,
                    :destinationEncrypted, :codeHash, 0, :maxAttempts, 'PENDING',
                    :issuedAt, :expiresAt, :issuedAt, :issuedAt)
                """)
                .param("id", challenge.id())
                .param("tenantId", challenge.tenantId())
                .param("brandId", challenge.brandId())
                .param("purpose", challenge.purpose())
                .param("contactType", challenge.contactType())
                .param("destinationHash", challenge.destinationHash())
                .param("destinationEncrypted", challenge.destinationValue())
                .param("codeHash", challenge.codeHash())
                .param("maxAttempts", challenge.maxAttempts())
                .param("issuedAt", utc(challenge.issuedAt()))
                .param("expiresAt", utc(challenge.expiresAt()))
                .update();
    }

    /**
     * How much of this destination's budget is spent.
     *
     * <p>Counts every challenge in the window whatever became of it, and takes the
     * most recent issuance from the same pass. Two questions, one index scan on
     * {@code (tenant_id, contact_type, destination_hash, issued_at)}: an issuance
     * path that ran two queries would be twice as expensive on exactly the request
     * an attacker repeats.
     */
    @Override
    public IssuanceWindow issuanceWindow(UUID tenantId, String destinationHash, Instant since) {
        return jdbc.sql("""
                SELECT max(issued_at) AS last_issued_at,
                       count(*) FILTER (WHERE issued_at >= :since) AS issued_in_window
                FROM customer.verification_challenges
                WHERE tenant_id = :tenantId AND destination_hash = :destinationHash
                """)
                .param("tenantId", tenantId)
                .param("destinationHash", destinationHash)
                .param("since", utc(since))
                .query((row, number) -> new IssuanceWindow(
                        Optional.ofNullable(row.getObject("last_issued_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant),
                        row.getInt("issued_in_window")))
                .single();
    }

    @Override
    public int supersedePending(UUID tenantId, String contactType, String destinationHash, Instant now) {
        return jdbc.sql("""
                UPDATE customer.verification_challenges
                SET status = 'SUPERSEDED', settled_at = :now, updated_at = :now
                WHERE tenant_id = :tenantId AND contact_type = :contactType
                  AND destination_hash = :destinationHash AND status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("contactType", contactType)
                .param("destinationHash", destinationHash)
                .param("now", utc(now))
                .update();
    }

    /**
     * Spends one attempt, if there is one to spend.
     *
     * <p>{@code attempts_used < max_attempts} in the {@code WHERE} clause is the
     * attempt limit. Doing it in Java would mean reading, comparing and writing,
     * and five concurrent guesses would all read zero.
     *
     * <p>{@code RETURNING} sees the new row, so {@code max_attempts - attempts_used}
     * is what is left <em>after</em> this attempt.
     */
    @Override
    public Optional<Attempt> consumeAttempt(UUID tenantId, UUID challengeId, Instant now) {
        return jdbc.sql("""
                UPDATE customer.verification_challenges
                SET attempts_used = attempts_used + 1, updated_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING'
                  AND expires_at > :now AND attempts_used < max_attempts
                RETURNING code_hash, max_attempts - attempts_used AS attempts_remaining
                """)
                .param("id", challengeId)
                .param("tenantId", tenantId)
                .param("now", utc(now))
                .query((row, number) -> new Attempt(row.getString("code_hash"), row.getInt("attempts_remaining")))
                .optional();
    }

    @Override
    public boolean markVerified(
            UUID tenantId, UUID challengeId, String grantHash, Instant grantExpiresAt, Instant now) {
        return jdbc.sql("""
                UPDATE customer.verification_challenges
                SET status = 'VERIFIED', settled_at = :now, updated_at = :now,
                    grant_hash = :grantHash, grant_expires_at = :grantExpiresAt
                WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING'
                  AND expires_at > :now
                """)
                        .param("id", challengeId)
                        .param("tenantId", tenantId)
                        .param("grantHash", grantHash)
                        .param("grantExpiresAt", utc(grantExpiresAt))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    @Override
    public void markExhausted(UUID tenantId, UUID challengeId, Instant now) {
        jdbc.sql("""
                UPDATE customer.verification_challenges
                SET status = 'EXHAUSTED', settled_at = :now, updated_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING'
                """)
                .param("id", challengeId)
                .param("tenantId", tenantId)
                .param("now", utc(now))
                .update();
    }

    @Override
    public boolean deleteUnsent(UUID tenantId, UUID challengeId) {
        return jdbc.sql("""
                DELETE FROM customer.verification_challenges
                WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING'
                  AND attempts_used = 0
                """)
                        .param("id", challengeId)
                        .param("tenantId", tenantId)
                        .update()
                == 1;
    }

    @Override
    public Optional<RedeemedGrant> redeemGrant(String grantHash, Instant now) {
        return jdbc.sql("""
                UPDATE customer.verification_challenges
                SET grant_redeemed_at = :now, updated_at = :now
                WHERE grant_hash = :grantHash AND status = 'VERIFIED'
                  AND grant_redeemed_at IS NULL AND grant_expires_at > :now
                RETURNING id, tenant_id, brand_id, contact_type, destination_hash,
                          destination_encrypted
                """)
                .param("grantHash", grantHash)
                .param("now", utc(now))
                .query((row, number) -> new RedeemedGrant(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("contact_type"),
                        row.getString("destination_hash"),
                        row.getString("destination_encrypted")))
                .optional();
    }

    /**
     * Bounded per run, so the sweep is a predictable amount of work rather than
     * one that grows with however long the job was down.
     */
    @Override
    public int expirePending(Instant now, int limit) {
        return jdbc.sql("""
                UPDATE customer.verification_challenges
                SET status = 'EXPIRED', settled_at = :now, updated_at = :now
                WHERE id IN (
                    SELECT id FROM customer.verification_challenges
                    WHERE status = 'PENDING' AND expires_at <= :now
                    ORDER BY expires_at
                    LIMIT :limit
                )
                """).param("now", utc(now)).param("limit", limit).update();
    }

    /**
     * Deletes settled challenges past their retention.
     *
     * <p>A row here holds an encrypted phone number, so ADR 0029 makes keeping it
     * a decision rather than a default. A settled challenge's only remaining use is
     * a short investigation window.
     */
    @Override
    public int purgeSettledBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM customer.verification_challenges
                WHERE id IN (
                    SELECT id FROM customer.verification_challenges
                    WHERE status <> :pending AND settled_at < :cutoff
                    ORDER BY settled_at
                    LIMIT :limit
                )
                """)
                .param("pending", ChallengeStatus.PENDING.name())
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
