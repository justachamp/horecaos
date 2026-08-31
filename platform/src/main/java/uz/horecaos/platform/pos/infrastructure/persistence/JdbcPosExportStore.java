package uz.horecaos.platform.pos.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pos.domain.ExportCandidate;
import uz.horecaos.platform.pos.domain.ExportState;

/**
 * The POS export rows (ADR 0011).
 *
 * <p>Two properties of this store are load-bearing rather than incidental.
 *
 * <p><strong>Claiming an export is a conditional update.</strong> Every state
 * change names the state it expects to find, so two workers that both believe
 * they should send one order produce one send and one no-op. On a provider with
 * an idempotency key that would be tidiness; here it is the difference between
 * one kitchen ticket and two, because nothing downstream can undo the second.
 *
 * <p><strong>Nothing here deletes an attempt.</strong> The question this table
 * exists to answer is "how many tickets might this order have produced", and an
 * editable history cannot answer it.
 */
@Component
public class JdbcPosExportStore {

    private final JdbcClient jdbc;

    public JdbcPosExportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the export row, or returns the one that already exists.
     *
     * <p>The uniqueness on {@code (tenant_id, order_id)} is the only idempotency
     * this integration has, so this method leans on it rather than checking first:
     * a check-then-insert has a window, and the window is exactly where a
     * duplicated command lands.
     */
    public UUID open(NewExport export) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", export.id());
        parameters.put("tenantId", export.tenantId());
        parameters.put("orderId", export.orderId());
        parameters.put("bindingId", export.bindingId());
        parameters.put("installationId", export.installationId());
        parameters.put("correlation", export.correlationReference());
        parameters.put("fingerprint", export.lineFingerprint());
        parameters.put("phoneHash", export.customerPhoneHash());
        parameters.put("venue", export.externalVenueReference());
        parameters.put("requestedAt", OffsetDateTime.ofInstant(export.requestedAt(), ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.pos_order_exports
                    (id, tenant_id, order_id, binding_id, installation_id, state,
                     correlation_reference, line_fingerprint, customer_phone_hash,
                     external_venue_reference, requested_at)
                VALUES (:id, :tenantId, :orderId, :bindingId, :installationId, 'PENDING',
                        :correlation, :fingerprint, :phoneHash, :venue, :requestedAt)
                ON CONFLICT (tenant_id, order_id) DO NOTHING
                """).params(parameters).update();

        return jdbc.sql("""
                SELECT id FROM integration.pos_order_exports
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", export.tenantId())
                .param("orderId", export.orderId())
                .query(UUID.class)
                .single();
    }

    public Optional<ExportRow> find(UUID tenantId, UUID exportId) {
        return jdbc.sql("""
                SELECT id, tenant_id, order_id, binding_id, installation_id, state,
                       attempt_count, correlation_reference, external_order_id,
                       external_receipt_id, line_fingerprint, customer_phone_hash,
                       external_venue_reference, requested_at, version
                  FROM integration.pos_order_exports
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", exportId)
                .query((row, number) -> new ExportRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        ExportState.valueOf(row.getString("state")),
                        row.getInt("attempt_count"),
                        row.getString("correlation_reference"),
                        row.getString("external_order_id"),
                        row.getString("external_receipt_id"),
                        row.getString("line_fingerprint"),
                        row.getString("customer_phone_hash"),
                        row.getString("external_venue_reference"),
                        row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                        row.getLong("version")))
                .optional();
    }

    /**
     * Moves an export to {@code SENT} and allocates its attempt number.
     *
     * <p>Conditional on the current state, so only one worker wins. The caller
     * must treat an empty answer as "somebody else is sending this" and do
     * nothing at all — not wait, not retry.
     */
    public Optional<Integer> claimForAttempt(UUID tenantId, UUID exportId, ExportState from, Instant now) {
        List<Integer> attempt = jdbc.sql("""
                UPDATE integration.pos_order_exports
                   SET state = 'SENT',
                       attempt_count = attempt_count + 1,
                       first_sent_at = coalesce(first_sent_at, :now),
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND state = :from
             RETURNING attempt_count
                """)
                .param("tenantId", tenantId)
                .param("id", exportId)
                .param("from", from.name())
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(Integer.class)
                .list();
        return attempt.stream().findFirst();
    }

    /** Records what one attempt came to. Append-only. */
    public void recordAttempt(
            UUID tenantId,
            UUID exportId,
            int attemptNumber,
            String outcomeStatus,
            @Nullable String errorCode,
            @Nullable String detail,
            Instant startedAt,
            @Nullable Instant finishedAt) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("tenantId", tenantId);
        parameters.put("exportId", exportId);
        parameters.put("attemptNumber", attemptNumber);
        parameters.put("outcomeStatus", outcomeStatus);
        parameters.put("errorCode", errorCode);
        parameters.put("detail", detail);
        parameters.put("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC));
        parameters.put("finishedAt", finishedAt == null ? null : OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.pos_export_attempts
                    (id, tenant_id, export_id, attempt_number, outcome_status,
                     provider_error_code, detail, started_at, finished_at)
                VALUES (:id, :tenantId, :exportId, :attemptNumber, :outcomeStatus,
                        :errorCode, :detail, :startedAt, :finishedAt)
                ON CONFLICT (export_id, attempt_number) DO NOTHING
                """)
                // params(Map) rather than a chain of param calls, because the
                // error code, the detail and the finish time are all nullable and
                // a HashMap is the shape JdbcClient accepts nulls in.
                .params(parameters)
                .update();
    }

    /** Settles an attempt's outcome onto the export. Conditional on SENT. */
    public boolean settle(
            UUID tenantId,
            UUID exportId,
            ExportState to,
            @Nullable String externalOrderId,
            @Nullable String errorCode,
            @Nullable String detail,
            Instant now) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", exportId);
        parameters.put("to", to.name());
        parameters.put("externalOrderId", externalOrderId);
        parameters.put("errorCode", errorCode);
        parameters.put("detail", detail);
        parameters.put("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        parameters.put("settledAt", to.terminal() ? OffsetDateTime.ofInstant(now, ZoneOffset.UTC) : null);

        return jdbc.sql("""
                UPDATE integration.pos_order_exports
                   SET state = :to,
                       external_order_id = coalesce(:externalOrderId, external_order_id),
                       last_error_code = :errorCode,
                       last_error = :detail,
                       settled_at = :settledAt,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND state = 'SENT'
                """).params(parameters).update() == 1;
    }

    /**
     * Records a resolution of an uncertain export.
     *
     * <p>Always attributed. The database refuses a resolution without a principal
     * and a time, and this is the only method that writes one, so an export
     * cannot come to be resolved by nobody.
     */
    public boolean resolve(
            UUID tenantId,
            UUID exportId,
            ExportState from,
            ExportState to,
            @Nullable String resolutionKind,
            @Nullable String externalOrderId,
            @Nullable String reason,
            @Nullable String resolvedBy,
            Instant now) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", exportId);
        parameters.put("from", from.name());
        parameters.put("to", to.name());
        parameters.put("kind", resolutionKind);
        parameters.put("externalOrderId", externalOrderId);
        parameters.put("reason", reason);
        parameters.put("resolvedBy", resolvedBy);
        parameters.put("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        parameters.put("settledAt", to.terminal() ? OffsetDateTime.ofInstant(now, ZoneOffset.UTC) : null);

        return jdbc.sql("""
                UPDATE integration.pos_order_exports
                   SET state = :to,
                       external_order_id = coalesce(:externalOrderId, external_order_id),
                       resolution_kind = :kind,
                       resolution_reason = :reason,
                       resolved_by = :resolvedBy,
                       resolved_at = :now,
                       settled_at = :settledAt,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND state = :from
                """).params(parameters).update() == 1;
    }

    /**
     * Replaces the candidate set from one recovery read.
     *
     * <p>Replaced rather than accumulated: candidates are a working note from one
     * read, and a list that grew across three reads would show an operator the
     * same provider order three times and imply three orders.
     */
    public void replaceCandidates(
            UUID tenantId, UUID exportId, List<ExportCandidate> candidates, int attemptNumber, Instant now) {

        jdbc.sql("""
                DELETE FROM integration.pos_export_candidates
                 WHERE tenant_id = :tenantId AND export_id = :exportId
                """).param("tenantId", tenantId).param("exportId", exportId).update();

        for (ExportCandidate candidate : candidates) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("id", UUID.randomUUID());
            parameters.put("tenantId", tenantId);
            parameters.put("exportId", exportId);
            parameters.put("externalOrderId", candidate.externalOrderId());
            parameters.put("externalStatus", candidate.externalStatus());
            parameters.put(
                    "externalCreatedAt",
                    candidate.externalCreatedAt() == null
                            ? null
                            : OffsetDateTime.ofInstant(candidate.externalCreatedAt(), ZoneOffset.UTC));
            parameters.put("echoed", candidate.correlationEchoed());
            parameters.put("phoneMatches", candidate.phoneMatches());
            parameters.put("fingerprintMatches", candidate.fingerprintMatches());
            parameters.put("delta", candidate.timeDeltaSeconds());
            parameters.put("discoveredAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            parameters.put("attempt", attemptNumber);

            jdbc.sql("""
                    INSERT INTO integration.pos_export_candidates
                        (id, tenant_id, export_id, external_order_id, external_status,
                         external_created_at, correlation_echoed, phone_matches,
                         fingerprint_matches, time_delta_seconds, discovered_at,
                         discovered_by_attempt)
                    VALUES (:id, :tenantId, :exportId, :externalOrderId, :externalStatus,
                            :externalCreatedAt, :echoed, :phoneMatches,
                            :fingerprintMatches, :delta, :discoveredAt, :attempt)
                    ON CONFLICT (export_id, external_order_id) DO NOTHING
                    """).params(parameters).update();
        }
    }

    public List<ExportCandidate> candidates(UUID tenantId, UUID exportId) {
        return jdbc.sql("""
                SELECT external_order_id, external_status, external_created_at,
                       correlation_echoed, phone_matches, fingerprint_matches,
                       time_delta_seconds
                  FROM integration.pos_export_candidates
                 WHERE tenant_id = :tenantId AND export_id = :exportId
                 ORDER BY correlation_echoed DESC, external_order_id
                """)
                .param("tenantId", tenantId)
                .param("exportId", exportId)
                .query((row, number) -> new ExportCandidate(
                        row.getString("external_order_id"),
                        row.getString("external_status"),
                        toInstant(row.getObject("external_created_at", OffsetDateTime.class)),
                        row.getBoolean("correlation_echoed"),
                        row.getBoolean("phone_matches"),
                        row.getBoolean("fingerprint_matches"),
                        // getInt answers zero for SQL NULL, and zero seconds is a
                        // meaningful delta here. getObject keeps "we do not know
                        // when it was created" distinguishable from "at the same
                        // second we asked".
                        row.getObject("time_delta_seconds", Integer.class)))
                .list();
    }

    /** Exports somebody has to look at. The queue a branch's evening depends on. */
    public List<ExportRow> awaitingOperator(UUID tenantId, int limit) {
        return jdbc.sql("""
                SELECT id, tenant_id, order_id, binding_id, installation_id, state,
                       attempt_count, correlation_reference, external_order_id,
                       external_receipt_id, line_fingerprint, customer_phone_hash,
                       external_venue_reference, requested_at, version
                  FROM integration.pos_order_exports
                 WHERE tenant_id = :tenantId AND state IN ('UNCERTAIN', 'AWAITING_OPERATOR')
                 ORDER BY requested_at
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query((row, number) -> new ExportRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        ExportState.valueOf(row.getString("state")),
                        row.getInt("attempt_count"),
                        row.getString("correlation_reference"),
                        row.getString("external_order_id"),
                        row.getString("external_receipt_id"),
                        row.getString("line_fingerprint"),
                        row.getString("customer_phone_hash"),
                        row.getString("external_venue_reference"),
                        row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                        row.getLong("version")))
                .list();
    }

    private static @Nullable Instant toInstant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record NewExport(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID bindingId,
            UUID installationId,
            String correlationReference,
            String lineFingerprint,
            String customerPhoneHash,
            String externalVenueReference,
            Instant requestedAt) {}

    public record ExportRow(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID bindingId,
            UUID installationId,
            ExportState state,
            int attemptCount,
            // Schema-nullable (V0036): correlation_reference/external_order_id/
            // external_receipt_id have no NOT NULL. The first is unset only if the
            // provider silently drops the field (see the column comment); the other
            // two are unset until the provider names its own order, which for most
            // of an export's life it has not yet done.
            @Nullable String correlationReference,
            @Nullable String externalOrderId,
            @Nullable String externalReceiptId,
            String lineFingerprint,
            String customerPhoneHash,
            String externalVenueReference,
            Instant requestedAt,
            long version) {}
}
