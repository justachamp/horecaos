package uz.horecaos.platform.media.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/** Media asset persistence (ADR 0010). */
@Repository
public class JdbcMediaAssetStore {

    private final JdbcClient jdbc;

    public JdbcMediaAssetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(MediaAsset asset) {
        jdbc.sql("""
                INSERT INTO media.assets (
                    asset_id, tenant_id, owner_scope, owner_id, object_key, bucket,
                    status, visibility, declared_content_type, declared_size_bytes,
                    declared_checksum_sha256, original_filename, created_by, created_at)
                VALUES (
                    :assetId, :tenantId, :ownerScope, :ownerId, :objectKey, :bucket,
                    :status, :visibility, :contentType, :sizeBytes,
                    :checksum, :filename, :createdBy, :createdAt)
                """)
                .param("assetId", asset.assetId().value())
                .param("tenantId", asset.tenantId())
                .param("ownerScope", asset.owner().scope().name())
                .param("ownerId", asset.owner().id())
                .param("objectKey", asset.objectKey())
                .param("bucket", asset.bucket())
                .param("status", asset.status().name())
                .param("visibility", asset.visibility().name())
                .param("contentType", asset.declaredContentType())
                .param("sizeBytes", asset.declaredSizeBytes())
                .param("checksum", asset.declaredChecksumSha256())
                .param("filename", asset.originalFilename())
                .param("createdBy", asset.createdBy())
                .param("createdAt", OffsetDateTime.ofInstant(asset.createdAt(), ZoneOffset.UTC))
                .update();
    }

    /**
     * Reads an asset only within its own tenant.
     *
     * <p>The tenant predicate is in the query rather than checked afterwards, so
     * there is no code path that loads another tenant's row at all.
     */
    public Optional<MediaAsset> findOwned(UUID tenantId, MediaAssetId assetId) {
        return jdbc.sql("""
                SELECT * FROM media.assets
                WHERE tenant_id = :tenantId AND asset_id = :assetId AND status <> 'DELETED'
                """)
                .param("tenantId", tenantId)
                .param("assetId", assetId.value())
                .query(JdbcMediaAssetStore::mapAsset)
                .optional();
    }

    /**
     * @param contentType what the image's own header says, not what the client
     *                    declared and not what the store echoed back
     */
    public void markAvailable(MediaAssetId assetId, String contentType, long sizeBytes,
            String checksum, int widthPx, int heightPx, Instant now) {
        jdbc.sql("""
                UPDATE media.assets
                SET status = 'AVAILABLE',
                    verified_content_type = :contentType,
                    verified_size_bytes = :sizeBytes,
                    verified_checksum_sha256 = :checksum,
                    width_px = :widthPx,
                    height_px = :heightPx,
                    finalized_at = COALESCE(finalized_at, :now),
                    available_at = :now,
                    rejection_code = NULL,
                    rejection_detail = NULL
                WHERE asset_id = :assetId
                """)
                .param("assetId", assetId.value())
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("checksum", checksum)
                .param("widthPx", widthPx)
                .param("heightPx", heightPx)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public void markRejected(MediaAssetId assetId, String code, String detail, Instant now) {
        jdbc.sql("""
                UPDATE media.assets
                SET status = 'REJECTED', rejection_code = :code, rejection_detail = :detail,
                    finalized_at = :now
                WHERE asset_id = :assetId
                """)
                .param("assetId", assetId.value())
                .param("code", code)
                .param("detail", detail)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    private static MediaAsset mapAsset(ResultSet row, int rowNumber) throws SQLException {
        return new MediaAsset(
                new MediaAssetId(row.getObject("asset_id", UUID.class)),
                row.getObject("tenant_id", UUID.class),
                new MediaOwner(MediaOwner.Scope.valueOf(row.getString("owner_scope")),
                        row.getObject("owner_id", UUID.class)),
                row.getString("object_key"),
                row.getString("bucket"),
                MediaAssetStatus.valueOf(row.getString("status")),
                MediaVisibility.valueOf(row.getString("visibility")),
                row.getString("declared_content_type"),
                row.getLong("declared_size_bytes"),
                row.getString("declared_checksum_sha256"),
                row.getString("verified_content_type"),
                (Long) row.getObject("verified_size_bytes"),
                row.getString("verified_checksum_sha256"),
                row.getString("original_filename"),
                (Integer) row.getObject("width_px"),
                (Integer) row.getObject("height_px"),
                row.getObject("created_by", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
