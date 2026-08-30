package uz.horecaos.platform.web.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared idempotency mechanism required by ADR 0031.
 *
 * <p>Deriving the key from a hash of the request was the alternative, and ADR
 * 0019 rejected it: two legitimately different carts can normalise to the same
 * hash, and a retry with a trivial difference would create a second order. The
 * client supplies the key; the hash only detects misuse of that key.
 */
@Service
public class IdempotencyService {

    /** Minimum retention from ADR 0031; monetary operations override upward. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    /**
     * How long an in-progress claim is honoured before another attempt may take
     * it over. Without this, a process that dies mid-request would block that
     * key until retention expired.
     */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Claims the key for this request, or reports what the caller should do
     * instead.
     *
     * <p>Runs in its own transaction so the claim survives a rollback of the
     * business transaction it guards; otherwise a failed attempt would erase
     * the evidence that it happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyOutcome begin(IdempotencyRequest request) {
        Instant now = clock.instant();
        String hash = sha256(request.requestBody());
        UUID recordId = UUID.randomUUID();

        // ON CONFLICT DO NOTHING rather than catching a duplicate-key exception.
        // A constraint violation aborts the surrounding PostgreSQL transaction,
        // so the follow-up SELECT would fail with "current transaction is
        // aborted" instead of finding the existing claim. This mirrors the
        // inbox insert in ADR 0005 for the same reason.
        int inserted = jdbc.sql("""
                INSERT INTO platform.idempotency_records
                    (id, scope_key, idempotency_key, tenant_id, principal_subject,
                     request_hash, status, lease_expires_at, first_seen_at, expires_at)
                VALUES (:id, :scopeKey, :idempotencyKey, :tenantId, :principal,
                        :hash, 'IN_PROGRESS', :leaseExpiresAt, :now, :expiresAt)
                ON CONFLICT DO NOTHING
                """)
                .param("id", recordId)
                .param("scopeKey", request.scopeKey())
                .param("idempotencyKey", request.idempotencyKey())
                .param("tenantId", request.tenantId())
                .param("principal", request.principalSubject())
                .param("hash", hash)
                .param("leaseExpiresAt", at(now.plus(DEFAULT_LEASE)))
                .param("now", at(now))
                .param("expiresAt", at(now.plus(request.retention())))
                .update();

        return inserted == 1
                ? new IdempotencyOutcome.Proceed(recordId)
                : inspectExisting(request, hash, now);
    }

    /** Records the response so an identical retry replays it rather than re-running. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID recordId, int responseStatus, String responseBody) {
        complete(recordId, responseStatus, responseBody, false);
    }

    /**
     * Records the response, saying whether the body is an ADR 0029 envelope.
     *
     * <p>The store does not encrypt and does not decide to: it is handed a body
     * and told what it is. Whether a response carries personal data is a fact
     * about the handler's response type, and {@link ResponseBodyProtection} is
     * where that is read.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID recordId, int responseStatus, String responseBody, boolean protectedBody) {
        jdbc.sql("""
                UPDATE platform.idempotency_records
                   SET status = 'COMPLETED',
                       response_status = :status,
                       response_body = :body,
                       response_body_protected = :protectedBody,
                       completed_at = :now
                 WHERE id = :id AND status = 'IN_PROGRESS'
                """)
                .param("id", recordId)
                .param("status", responseStatus)
                .param("body", responseBody)
                .param("protectedBody", protectedBody)
                .param("now", at(clock.instant()))
                .update();
    }

    /**
     * Releases a claim whose request failed in a way that should be retriable.
     *
     * <p>A business rejection is a completed outcome and must be recorded with
     * {@link #complete}; only an unexpected failure releases the key.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        jdbc.sql("DELETE FROM platform.idempotency_records WHERE id = :id AND status = 'IN_PROGRESS'")
                .param("id", recordId)
                .update();
    }

    /** Removes expired records; scheduled by operations rather than on the request path. */
    @Transactional
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM platform.idempotency_records WHERE expires_at < :now")
                .param("now", at(clock.instant()))
                .update();
    }

    private IdempotencyOutcome inspectExisting(IdempotencyRequest request, String hash, Instant now) {
        Optional<Existing> existing = jdbc.sql("""
                SELECT id, request_hash, status, response_status, response_body,
                       response_body_protected, lease_expires_at
                  FROM platform.idempotency_records
                 WHERE scope_key = :scopeKey
                   AND idempotency_key = :idempotencyKey
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND principal_subject = :principal
                """)
                .param("scopeKey", request.scopeKey())
                .param("idempotencyKey", request.idempotencyKey())
                .param("tenantId", request.tenantId())
                .param("principal", request.principalSubject())
                .query((resultSet, rowNumber) -> new Existing(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("request_hash"),
                        resultSet.getString("status"),
                        (Integer) resultSet.getObject("response_status"),
                        resultSet.getString("response_body"),
                        resultSet.getBoolean("response_body_protected"),
                        resultSet.getObject("lease_expires_at", java.time.OffsetDateTime.class).toInstant()))
                .optional();

        if (existing.isEmpty()) {
            // Raced with a purge between the insert failing and this read.
            return begin(request);
        }
        Existing record = existing.get();

        if (!record.requestHash().equals(hash)) {
            return new IdempotencyOutcome.Conflict();
        }
        if ("COMPLETED".equals(record.status())) {
            return new IdempotencyOutcome.Replay(record.id(), record.responseStatus(),
                    record.responseBody(), record.responseBodyProtected());
        }
        if (record.leaseExpiresAt().isAfter(now)) {
            return new IdempotencyOutcome.InProgress();
        }

        // The lease expired, so the original attempt is presumed dead. Take it over
        // under a fresh lease rather than leaving the key blocked until retention.
        int taken = jdbc.sql("""
                UPDATE platform.idempotency_records
                   SET lease_expires_at = :leaseExpiresAt
                 WHERE id = :id AND status = 'IN_PROGRESS' AND lease_expires_at <= :now
                """)
                .param("id", record.id())
                .param("leaseExpiresAt", at(now.plus(DEFAULT_LEASE)))
                .param("now", at(now))
                .update();

        return taken == 1
                ? new IdempotencyOutcome.Proceed(record.id())
                : new IdempotencyOutcome.InProgress();
    }

    /**
     * The PostgreSQL driver cannot infer a SQL type for {@link Instant}, so
     * instants are bound as UTC offsets at the boundary. Business code keeps
     * working in {@code Instant}.
     */
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Existing(
            UUID id, String requestHash, String status,
            Integer responseStatus, String responseBody, boolean responseBodyProtected,
            Instant leaseExpiresAt) { }
}
