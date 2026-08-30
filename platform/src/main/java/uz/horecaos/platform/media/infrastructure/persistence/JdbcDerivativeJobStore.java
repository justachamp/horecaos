package uz.horecaos.platform.media.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.media.api.MediaAssetId;

/**
 * {@code media.derivative_jobs} (ADR 0010, V0065).
 *
 * <p>The claim is {@code JdbcSourcingJobStore}'s, which is {@code
 * JdbcOutboxStore.claimBatch}'s: {@code FOR UPDATE SKIP LOCKED}, a lease with a
 * token, a holder and a deadline, and a batch. A third hand-written variant of
 * that query is a third chance to get it subtly wrong, and none of the
 * differences between rendering a thumbnail and sourcing a courier reach as far
 * as the claim.
 *
 * <p>What the lease is <em>not</em> is the thing that stops a derivative being
 * rendered twice. It only decides who does the work; whether the work is safe to
 * repeat is settled by {@code uq_media_derivative (asset_id, variant)} and by
 * {@code MediaDerivativeService} skipping variants that already exist.
 */
@Repository
public class JdbcDerivativeJobStore {

    private final JdbcClient jdbc;

    public JdbcDerivativeJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that this asset's renditions are owed.
     *
     * <p>Called inside the transaction that marks the asset {@code AVAILABLE},
     * so there is no instant at which an asset is displayable and nothing will
     * ever render it. One statement and no network call, which is what makes it
     * safe to put in a transaction that the object-store round trips were
     * deliberately kept out of.
     *
     * <p>{@code ON CONFLICT} against the partial unique index rather than a
     * read-then-write: two outstanding jobs for one asset is two workers
     * decoding the same photograph, and only the index can decide that race.
     *
     * @return true when this call created the job; false means one was already
     *         outstanding, which is the ordinary answer to a replayed finalize
     */
    public boolean enqueue(UUID jobId, UUID tenantId, MediaAssetId assetId, Instant dueAt) {
        return jdbc.sql("""
                INSERT INTO media.derivative_jobs (job_id, tenant_id, asset_id, status, due_at)
                VALUES (:jobId, :tenantId, :assetId, 'PENDING', :dueAt)
                ON CONFLICT (tenant_id, asset_id) WHERE status IN ('PENDING', 'LEASED')
                DO NOTHING
                """)
                        .param("jobId", jobId)
                        .param("tenantId", tenantId)
                        .param("assetId", assetId.value())
                        .param("dueAt", utc(dueAt))
                        .update()
                == 1;
    }

    /**
     * Due jobs, claimed under a fresh lease.
     *
     * <p>A job is claimable when it is due and either nobody holds it or whoever
     * did has stopped saying so. The second half is the point: a worker killed
     * mid-render does not strand the asset without derivatives, because its
     * lease expires and the row becomes claimable again with nobody
     * intervening.
     *
     * <p>{@code REQUIRES_NEW} for the reason the outbox uses it — the claim has
     * to commit on its own, so a render that later fails does not roll the claim
     * back and hand the same job to the next poll as though nothing had been
     * tried. The attempt count is the record that something was.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedJob> claim(Instant now, Duration lease, int batchSize, String workerId) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("A derivative batch size must be positive");
        }
        UUID leaseToken = UUID.randomUUID();

        return jdbc.sql("""
                WITH candidates AS (
                    SELECT candidate.job_id
                    FROM media.derivative_jobs AS candidate
                    WHERE candidate.due_at <= :now
                      AND (candidate.status = 'PENDING'
                           OR (candidate.status = 'LEASED' AND candidate.leased_until <= :now))
                    ORDER BY candidate.due_at, candidate.job_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE media.derivative_jobs AS job
                SET status = 'LEASED',
                    attempt_count = job.attempt_count + 1,
                    lease_token = :leaseToken,
                    leased_until = :leasedUntil,
                    leased_by = :workerId,
                    updated_at = :now
                FROM candidates
                WHERE job.job_id = candidates.job_id
                RETURNING job.job_id, job.tenant_id, job.asset_id, job.attempt_count,
                          job.lease_token
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .param("leaseToken", leaseToken)
                .param("leasedUntil", utc(now.plus(lease)))
                .param("workerId", workerId)
                .query((row, number) -> new ClaimedJob(
                        row.getObject("job_id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        new MediaAssetId(row.getObject("asset_id", UUID.class)),
                        row.getInt("attempt_count"),
                        row.getObject("lease_token", UUID.class)))
                .list();
    }

    /**
     * Nothing more is owed for this asset.
     *
     * <p>A false return means the lease was lost — the render outlasted its
     * lease and somebody else holds the job — and the caller must not treat its
     * own result as the asset's outcome.
     */
    public boolean complete(UUID jobId, UUID leaseToken, Instant now) {
        return jdbc.sql("""
                UPDATE media.derivative_jobs
                SET status = 'COMPLETED', lease_token = NULL, leased_until = NULL,
                    leased_by = NULL, last_error_code = NULL, last_error_at = NULL,
                    updated_at = :now
                WHERE job_id = :jobId AND lease_token = :leaseToken
                """)
                        .param("jobId", jobId)
                        .param("leaseToken", leaseToken)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Come back to this asset at {@code dueAt}.
     *
     * @param errorCode a stable code, never a message. A decoder's own wording
     *                  carries the uploaded filename often enough that ADR 0029
     *                  will not have it in a column nobody is watching
     */
    public boolean reschedule(UUID jobId, UUID leaseToken, Instant dueAt, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE media.derivative_jobs
                SET status = 'PENDING', due_at = :dueAt,
                    lease_token = NULL, leased_until = NULL, leased_by = NULL,
                    last_error_code = :errorCode,
                    last_error_at = CASE WHEN CAST(:errorCode AS varchar) IS NULL
                                         THEN NULL ELSE :now END,
                    updated_at = :now
                WHERE job_id = :jobId AND lease_token = :leaseToken
                """)
                        .param("jobId", jobId)
                        .param("leaseToken", leaseToken)
                        .param("dueAt", utc(dueAt))
                        .param("errorCode", errorCode)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Hands a claimed job back untouched, as though it had never been claimed.
     *
     * <p>For the one case the other three settlements do not cover: a batch that
     * was claimed and then interrupted before this job was reached. Its siblings
     * were rendered or settled; this one was only ever a row in a list, and
     * leaving it {@code LEASED} would make it wait out a five-minute lease and
     * come back with an attempt already spent on work nobody did. Six of those
     * and the worker's own guard abandons it as {@code ATTEMPTS_EXHAUSTED}
     * having never rendered it once, which is the failure the guard exists to
     * bound rather than one it should be producing.
     *
     * <p>The attempt is refunded, and that is the difference between this and
     * {@link #reschedule}. An attempt count is the record of what was
     * <em>tried</em>; a job at the back of an abandoned batch was not tried, and
     * spending its budget for it means a process failing repeatedly burns
     * through the budget of every innocent job behind the poisonous one.
     * {@code GREATEST(…, 0)} because a refund must not be able to drive the
     * count below zero however the row got here.
     *
     * <p>The error columns are left exactly as they were. Whatever this job last
     * failed on is still the last thing it failed on; being carried by a batch
     * that ended badly is not a fact about this asset.
     *
     * @return false when the lease was lost, which means somebody else owns the
     *         job and this caller must leave it alone
     */
    public boolean release(UUID jobId, UUID leaseToken, Instant dueAt, Instant now) {
        return jdbc.sql("""
                UPDATE media.derivative_jobs
                SET status = 'PENDING', due_at = :dueAt,
                    attempt_count = GREATEST(attempt_count - 1, 0),
                    lease_token = NULL, leased_until = NULL, leased_by = NULL,
                    updated_at = :now
                WHERE job_id = :jobId AND lease_token = :leaseToken
                """)
                        .param("jobId", jobId)
                        .param("leaseToken", leaseToken)
                        .param("dueAt", utc(dueAt))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * This asset will never get its renditions automatically.
     *
     * <p>{@code ABANDONED} rather than deleted, and rather than retried forever.
     * The row is the only record that rendering was ever owed, and an operator
     * asking why one dish has no thumbnail needs to see the attempt count and
     * the code, not an absence. A storefront falls back to the original either
     * way, which is why this is an operational fact and not an incident.
     */
    public boolean abandon(UUID jobId, UUID leaseToken, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE media.derivative_jobs
                SET status = 'ABANDONED', lease_token = NULL, leased_until = NULL,
                    leased_by = NULL,
                    last_error_code = :errorCode,
                    last_error_at = :now,
                    updated_at = :now
                WHERE job_id = :jobId AND lease_token = :leaseToken
                """)
                        .param("jobId", jobId)
                        .param("leaseToken", leaseToken)
                        .param("errorCode", errorCode)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** @param attemptCount including this one, because the claim incremented it */
    public record ClaimedJob(UUID jobId, UUID tenantId, MediaAssetId assetId, int attemptCount, UUID leaseToken) {}

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
