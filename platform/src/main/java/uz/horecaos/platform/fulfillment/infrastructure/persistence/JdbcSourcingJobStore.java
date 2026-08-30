package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.instant;
import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

/**
 * {@code fulfillment.delivery_sourcing_jobs} (ADR 0014, V0054).
 *
 * <p>The claim is {@code JdbcOutboxStore.claimBatch}'s, deliberately: a second
 * pattern for the same problem is a second place to get {@code FOR UPDATE SKIP
 * LOCKED} subtly wrong, and the outbox's has been exercised. What differs is what
 * makes a row claimable — the outbox retries a failed publish, this one wakes at a
 * time computed from a kitchen's estimate — and the lease, which here is the
 * three columns V0054 insists on together: a token, a holder and a deadline.
 *
 * <p><b>Why a lease and not a lock.</b> A worker that dies mid-tick holding a
 * transaction lock releases it on disconnect and the next worker re-runs the tick
 * with no idea one was in flight; a worker that dies holding a lease leaves a row
 * that says who had it and until when, so the re-run is visible as a re-run. The
 * lease is also what makes a hung worker — connected, alive, doing nothing — lose
 * its claim, which a lock never does.
 */
@Repository
public class JdbcSourcingJobStore {

    private final JdbcClient jdbc;

    public JdbcSourcingJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One job per plan, created with the plan.
     *
     * <p>{@code ON CONFLICT} against {@code ux_job_one_active} rather than a
     * read-then-write: two jobs for one plan is two workers sourcing the same
     * order, and the index is the only thing that can decide that race.
     *
     * @return true when this call created the job. False means one was already
     *         waiting, which is the ordinary answer to a replayed confirmation
     */
    public boolean enqueue(UUID jobId, UUID tenantId, UUID planId, Instant dueAt) {
        return jdbc.sql("""
                INSERT INTO fulfillment.delivery_sourcing_jobs (
                    id, tenant_id, delivery_plan_id, status, due_at)
                VALUES (:id, :tenantId, :planId, 'PENDING', :dueAt)
                ON CONFLICT (tenant_id, delivery_plan_id) WHERE status IN ('PENDING', 'LEASED')
                DO NOTHING
                """)
                .param("id", jobId).param("tenantId", tenantId).param("planId", planId)
                .param("dueAt", utc(dueAt))
                .update() == 1;
    }

    /**
     * Due jobs, claimed under a fresh lease.
     *
     * <p>A job is claimable when it is due and either nobody holds it or whoever
     * did has stopped saying so. The second half is the whole point: a worker that
     * dies does not strand the order, because its lease expires and the row
     * becomes claimable again without anybody intervening.
     *
     * <p>{@code REQUIRES_NEW} for the reason the outbox uses it — the claim must
     * commit on its own, so a tick that later fails does not roll the claim back
     * and hand the same job to the next poll as though nothing had been tried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedJob> claim(Instant now, Duration lease, int batchSize, String workerId) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("A sourcing batch size must be positive");
        }
        UUID leaseToken = UUID.randomUUID();

        return jdbc.sql("""
                WITH candidates AS (
                    SELECT candidate.id
                    FROM fulfillment.delivery_sourcing_jobs AS candidate
                    WHERE candidate.due_at <= :now
                      AND (candidate.status = 'PENDING'
                           OR (candidate.status = 'LEASED' AND candidate.leased_until <= :now))
                    ORDER BY candidate.due_at, candidate.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE fulfillment.delivery_sourcing_jobs AS job
                SET status = 'LEASED',
                    attempt_count = job.attempt_count + 1,
                    lease_token = :leaseToken,
                    leased_until = :leasedUntil,
                    leased_by = :workerId,
                    updated_at = :now
                FROM candidates
                WHERE job.id = candidates.id
                RETURNING job.id, job.tenant_id, job.delivery_plan_id, job.attempt_count,
                          job.lease_token, job.created_at
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .param("leaseToken", leaseToken)
                .param("leasedUntil", utc(now.plus(lease)))
                .param("workerId", workerId)
                .query((row, number) -> new ClaimedJob(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("delivery_plan_id", UUID.class),
                        row.getInt("attempt_count"),
                        row.getObject("lease_token", UUID.class),
                        instant(row, "created_at")))
                .list();
    }

    /**
     * Sourcing is finished with this plan, successfully or otherwise.
     *
     * <p>A false return means the lease was lost — the tick took longer than its
     * lease and somebody else has the job — and the caller must not treat its own
     * result as the plan's outcome.
     */
    public boolean complete(UUID jobId, UUID leaseToken, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_sourcing_jobs
                SET status = 'COMPLETED', lease_token = NULL, leased_until = NULL,
                    leased_by = NULL, updated_at = :now
                WHERE id = :jobId AND lease_token = :leaseToken
                """)
                .param("jobId", jobId).param("leaseToken", leaseToken).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Come back to this plan at {@code dueAt}.
     *
     * @param errorCode a stable code or null. Never a message: a provider's own
     *                  wording is the one place a customer's address has been seen
     *                  to arrive in a column nobody was watching
     */
    public boolean reschedule(UUID jobId, UUID leaseToken, Instant dueAt, String checkpointJson,
            String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_sourcing_jobs
                SET status = 'PENDING', due_at = :dueAt,
                    lease_token = NULL, leased_until = NULL, leased_by = NULL,
                    checkpoint = CAST(:checkpoint AS jsonb),
                    last_error_code = :errorCode,
                    last_error_at = CASE WHEN CAST(:errorCode AS varchar) IS NULL
                                         THEN NULL ELSE :now END,
                    updated_at = :now
                WHERE id = :jobId AND lease_token = :leaseToken
                """)
                .param("jobId", jobId).param("leaseToken", leaseToken)
                .param("dueAt", utc(dueAt)).param("checkpoint", checkpointJson)
                .param("errorCode", errorCode).param("now", utc(now))
                .update() == 1;
    }

    /**
     * The job is beyond automated recovery and a human owns the plan now.
     *
     * <p>ABANDONED rather than deleted. The row is the only record that this order
     * was scheduled at all, and an operator asking why nobody was ever sent needs
     * to see the attempt count and the last error, not an absence.
     */
    public boolean abandon(UUID jobId, UUID leaseToken, String errorCode, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_sourcing_jobs
                SET status = 'ABANDONED', lease_token = NULL, leased_until = NULL,
                    leased_by = NULL,
                    last_error_code = :errorCode,
                    last_error_at = CASE WHEN CAST(:errorCode AS varchar) IS NULL
                                         THEN NULL ELSE :now END,
                    updated_at = :now
                WHERE id = :jobId AND lease_token = :leaseToken
                """)
                .param("jobId", jobId).param("leaseToken", leaseToken)
                .param("errorCode", errorCode).param("now", utc(now))
                .update() == 1;
    }

    /**
     * The kitchen changed its estimate, so the plan is due at a different time.
     *
     * <p>Only a job nobody is holding. A plan being sourced right now has already
     * passed the point where moving its due time changes anything, and the tick in
     * flight owns what happens next.
     */
    public boolean moveDueTime(UUID tenantId, UUID planId, Instant dueAt, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_sourcing_jobs
                SET due_at = :dueAt, updated_at = :now
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId AND status = 'PENDING'
                """)
                .param("tenantId", tenantId).param("planId", planId)
                .param("dueAt", utc(dueAt)).param("now", utc(now))
                .update() == 1;
    }

    /**
     * @param claimedAttempt the attempt number this claim is, already incremented.
     *                       A tick reads it to decide whether it has spent its
     *                       retry budget
     * @param createdAt      when this plan's sourcing began. The fleet budget is
     *                       measured from it rather than from this tick, so a
     *                       scheduler that wakes late does not hand the fleet
     *                       extra time
     */
    public record ClaimedJob(
            UUID jobId,
            UUID tenantId,
            UUID planId,
            int claimedAttempt,
            UUID leaseToken,
            Instant createdAt) { }
}
