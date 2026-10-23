package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code customer.export_requests} (V0684): the statements, and nothing about who may run them. */
@Repository
public class JdbcExportRequestStore {

    private static final String COLUMNS = """
            id, tenant_id, customer_account_id, status, requested_via,
            requested_by_actor_type, requested_by_actor_id, requested_at, response_due_at,
            produced_at, produced_by_actor_type, produced_by_actor_id, approval_id,
            bundle_bucket, bundle_key, bundle_sha256, bundle_bytes, manifest::text AS manifest_json,
            bundle_expires_at, bundle_removed_at,
            cancelled_at, cancelled_by_actor_type, cancelled_by_actor_id, version
            """;

    private final JdbcClient jdbc;

    public JdbcExportRequestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a PENDING request unless the account already has one.
     *
     * @return whether this call raised it: false is the unique index saying one is already outstanding
     */
    public boolean insertIfNonePending(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String via,
            String actorType,
            String actorId,
            Instant requestedAt,
            Instant responseDueAt) {
        return jdbc.sql("""
                INSERT INTO customer.export_requests (
                    id, tenant_id, customer_account_id, status, requested_via,
                    requested_by_actor_type, requested_by_actor_id, requested_at, response_due_at)
                VALUES (:id, :tenantId, :accountId, 'PENDING', :via, :actorType, :actorId, :requestedAt, :dueAt)
                ON CONFLICT (tenant_id, customer_account_id) WHERE status = 'PENDING' DO NOTHING
                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("accountId", accountId)
                        .param("via", via)
                        .param("actorType", actorType)
                        .param("actorId", actorId)
                        .param("requestedAt", at(requestedAt))
                        .param("dueAt", at(responseDueAt))
                        .update()
                == 1;
    }

    public Optional<ExportRequestRow> pending(UUID tenantId, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM customer.export_requests
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcExportRequestStore::row)
                .optional();
    }

    public Optional<ExportRequestRow> find(UUID tenantId, UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM customer.export_requests WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", requestId)
                .query(JdbcExportRequestStore::row)
                .optional();
    }

    /** The request, locked until the transaction ends, so two producers of one bundle take turns. */
    public Optional<ExportRequestRow> findForUpdate(UUID tenantId, UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM customer.export_requests WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", tenantId)
                .param("id", requestId)
                .query(JdbcExportRequestStore::row)
                .optional();
    }

    /** One account's requests, newest first. */
    public List<ExportRequestRow> history(UUID tenantId, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM customer.export_requests
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY requested_at DESC, id DESC
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcExportRequestStore::row)
                .list();
    }

    /**
     * Records the bundle on a PENDING request and completes it.
     *
     * @return rows written: zero means the request left PENDING already (another production won, or it was cancelled)
     */
    public int complete(
            UUID tenantId,
            UUID requestId,
            String actorType,
            String actorId,
            @Nullable UUID approvalId,
            String bucket,
            String key,
            String sha256,
            long bytes,
            String manifestJson,
            Instant expiresAt,
            Instant now) {
        return jdbc.sql("""
                UPDATE customer.export_requests
                   SET status = 'COMPLETED', produced_at = :now,
                       produced_by_actor_type = :actorType, produced_by_actor_id = :actorId,
                       approval_id = :approvalId, bundle_bucket = :bucket, bundle_key = :key,
                       bundle_sha256 = :sha256, bundle_bytes = :bytes, manifest = :manifest::jsonb,
                       bundle_expires_at = :expiresAt, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                """)
                .param("now", at(now))
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("approvalId", approvalId)
                .param("bucket", bucket)
                .param("key", key)
                .param("sha256", sha256)
                .param("bytes", bytes)
                .param("manifest", manifestJson)
                .param("expiresAt", at(expiresAt))
                .param("tenantId", tenantId)
                .param("id", requestId)
                .update();
    }

    /** Cancels a PENDING request; zero rows means it was not PENDING. */
    public int cancel(UUID tenantId, UUID requestId, String actorType, String actorId, Instant now) {
        return jdbc.sql("""
                UPDATE customer.export_requests
                   SET status = 'CANCELLED', cancelled_at = :now, cancelled_by_actor_type = :actorType,
                       cancelled_by_actor_id = :actorId, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                """)
                .param("now", at(now))
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("tenantId", tenantId)
                .param("id", requestId)
                .update();
    }

    /** Bundles whose time has come: still in object storage and past their expiry. */
    public List<StoredBundle> expiredBundles(Instant now, int limit) {
        return jdbc.sql("""
                SELECT tenant_id, id, bundle_bucket, bundle_key FROM customer.export_requests
                 WHERE status = 'COMPLETED' AND bundle_removed_at IS NULL AND bundle_expires_at <= :now
                 ORDER BY bundle_expires_at
                 LIMIT :limit
                """)
                .param("now", at(now))
                .param("limit", limit)
                .query(JdbcExportRequestStore::bundle)
                .list();
    }

    /** Every bundle of one account that is still in object storage, expired or not. */
    public List<StoredBundle> liveBundlesOf(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT tenant_id, id, bundle_bucket, bundle_key FROM customer.export_requests
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                   AND status = 'COMPLETED' AND bundle_removed_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcExportRequestStore::bundle)
                .list();
    }

    /** Notes that the object is gone; zero rows means it was noted already. */
    public int markBundleRemoved(UUID tenantId, UUID requestId, Instant now) {
        return jdbc.sql("""
                UPDATE customer.export_requests
                   SET bundle_removed_at = :now, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'COMPLETED' AND bundle_removed_at IS NULL
                """)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("id", requestId)
                .update();
    }

    private static StoredBundle bundle(ResultSet row, int number) throws SQLException {
        return new StoredBundle(
                row.getObject("tenant_id", UUID.class),
                row.getObject("id", UUID.class),
                row.getString("bundle_bucket"),
                row.getString("bundle_key"));
    }

    private static ExportRequestRow row(ResultSet row, int number) throws SQLException {
        return new ExportRequestRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("status"),
                row.getString("requested_via"),
                row.getString("requested_by_actor_type"),
                row.getString("requested_by_actor_id"),
                row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                row.getObject("response_due_at", OffsetDateTime.class).toInstant(),
                instant(row, "produced_at"),
                row.getString("produced_by_actor_type"),
                row.getString("produced_by_actor_id"),
                row.getObject("approval_id", UUID.class),
                row.getString("bundle_bucket"),
                row.getString("bundle_key"),
                row.getString("bundle_sha256"),
                row.getObject("bundle_bytes", Long.class),
                row.getString("manifest_json"),
                instant(row, "bundle_expires_at"),
                instant(row, "bundle_removed_at"),
                instant(row, "cancelled_at"),
                row.getString("cancelled_by_actor_type"),
                row.getString("cancelled_by_actor_id"),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** An object in the store that a request put there. */
    public record StoredBundle(UUID tenantId, UUID requestId, String bucket, String key) {}

    /** One export request at whatever point in its life it is read. */
    public record ExportRequestRow(
            UUID id,
            UUID tenantId,
            UUID customerAccountId,
            String status,
            String requestedVia,
            String requestedByActorType,
            String requestedByActorId,
            Instant requestedAt,
            Instant responseDueAt,
            @Nullable Instant producedAt,
            @Nullable String producedByActorType,
            @Nullable String producedByActorId,
            @Nullable UUID approvalId,
            @Nullable String bundleBucket,
            @Nullable String bundleKey,
            @Nullable String bundleSha256,
            @Nullable Long bundleBytes,
            @Nullable String manifestJson,
            @Nullable Instant bundleExpiresAt,
            @Nullable Instant bundleRemovedAt,
            @Nullable Instant cancelledAt,
            @Nullable String cancelledByActorType,
            @Nullable String cancelledByActorId,
            int version) {

        /** Whether a person can still be given a link: produced, in the store, and not yet expired at {@code now}. */
        public boolean bundleAvailableAt(Instant now) {
            return "COMPLETED".equals(status)
                    && bundleRemovedAt == null
                    && bundleExpiresAt != null
                    && bundleExpiresAt.isAfter(now);
        }

        @Override
        public String toString() {
            return "ExportRequestRow[id=%s, status=%s]".formatted(id, status);
        }
    }
}
