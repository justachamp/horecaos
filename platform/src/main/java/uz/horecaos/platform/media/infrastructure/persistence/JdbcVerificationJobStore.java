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
 * {@code media.verification_jobs} (ADR 0010, V0180).
 *
 * <p>The claim is {@code JdbcDerivativeJobStore}'s, which is {@code
 * JdbcSourcingJobStore}'s, which is {@code JdbcOutboxStore.claimBatch}'s: {@code
 * FOR UPDATE SKIP LOCKED}, a lease with a token, a holder and a deadline, and a
 * batch. A fourth hand-written variant of that query is a fourth chance to get
 * it subtly wrong, and none of the differences between rendering a thumbnail
 * and verifying an upload reach as far as the claim.
 *
 * <p>What the lease is <em>not</em> is what stops an upload being verified
 * twice by two overlapping workers. It only decides who does the round trip;
 * the verdict itself is idempotent — {@link uz.horecaos.platform.media.application.MediaAssetService#verifyUpload}
 * re-reads the asset's status before writing one, so a second worker that
 * raced past an expired lease finds the asset already settled and leaves it
 * alone.
 */
@Repository
public class JdbcVerificationJobStore {

    private final JdbcClient jdbc;

    public JdbcVerificationJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that this asset's upload still needs verifying.
     *
     * <p>Called inside the same statement-shaped moment as the {@code UPLOADED}
     * transition — not the same transaction as a derivative job's, because
     * nothing here needs one, but written immediately after so there is no
     * instant at which an asset claims to be uploaded and nothing will ever
     * check it.
     *
     * <p>{@code ON CONFLICT} against the partial unique index rather than a
     * read-then-write: two outstanding jobs for one asset is two workers making
     * the same round trip to the object store for nothing.
     *
     * @return true when this call created the job; false means one was already
     *         outstanding, which is the ordinary answer to a replayed finalize
     */
    public boolean enqueue(UUID jobId, UUID tenantId, MediaAssetId assetId, Instant dueAt) {
        return jdbc.sql("""
                INSERT INTO media.verification_jobs (job_id, tenant_id, asset_id, status, due_at)
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
     * <p>A job is claimable when it is due and either nobody holds it or
     * whoever did has stopped saying so. The second half is the point: a worker
     * killed mid-verification does not strand the upload in {@code UPLOADED}
     * forever, because its lease expires and the row becomes claimable again
     * with nobody intervening — which is what makes a crash mid-verification a
     * state the next pass can resume from, rather than a stuck asset.
     *
     * <p>{@code REQUIRES_NEW} for the reason the derivative claim uses it — the
     * claim has to commit on its own, so a verification that later fails does
     * not roll the claim back and hand the same job to the next poll as though
     * nothing had been tried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedJob> claim(Instant now, Duration lease, int batchSize, String workerId) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("A verification batch size must be positive");
        }
        UUID leaseToken = UUID.randomUUID();

        return jdbc.sql("""
                WITH candidates AS (
                    SELECT candidate.job_id
                    FROM media.verification_jobs AS candidate
                    WHERE candidate.due_at <= :now
                      AND (candidate.status = 'PENDING'
                           OR (candidate.status = 'LEASED' AND candidate.leased_until <= :now))
                    ORDER BY candidate.due_at, candidate.job_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE media.verification_jobs AS job
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
     * Nothing more is owed for this asset's upload.
     *
     * <p>A false return means the lease was lost — the verification outlasted
     * its lease and somebody else holds the job — and the caller must not treat
     * its own result as the asset's outcome.
     */
    public boolean complete(UUID jobId, UUID leaseToken, Instant now) {
        return jdbc.sql("""
                UPDATE media.verification_jobs
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
     * @param errorCode a stable code, never a message — the same discipline
     *                  {@code JdbcDerivativeJobStore} follows, for the same
     *                  reason (ADR 0029)
     */
    public boolean reschedule(UUID jobId, UUID leaseToken, Instant dueAt, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE media.verification_jobs
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
     * <p>For a batch that was claimed and then interrupted before this job was
     * reached — see {@code MediaDerivativeWorker.releaseUntouched} for the full
     * reasoning, which applies here unchanged. The attempt is refunded, because
     * an attempt count is the record of what was <em>tried</em>, and a job at
     * the back of an abandoned batch was not.
     *
     * @return false when the lease was lost, which means somebody else owns the
     *         job and this caller must leave it alone
     */
    public boolean release(UUID jobId, UUID leaseToken, Instant dueAt, Instant now) {
        return jdbc.sql("""
                UPDATE media.verification_jobs
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
     * This asset's upload will never be verified automatically.
     *
     * <p>{@code ABANDONED} rather than deleted or retried forever. The row is
     * the only record that verification was ever owed, and it stays in {@code
     * UPLOADED} — visibly stuck rather than silently AVAILABLE or silently gone
     * — for an operator to find.
     */
    public boolean abandon(UUID jobId, UUID leaseToken, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE media.verification_jobs
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

    /**
     * One verification job, claimed and leased to this worker.
     *
     * @param attemptCount including this one, because the claim incremented it
     */
    public record ClaimedJob(UUID jobId, UUID tenantId, MediaAssetId assetId, int attemptCount, UUID leaseToken) {}

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
