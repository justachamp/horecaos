package uz.horecaos.platform.media.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.application.MediaDerivativeStore;
import uz.horecaos.platform.media.domain.DerivativeVariant;
import uz.horecaos.platform.media.domain.MediaDerivative;

/**
 * Derivative persistence (ADR 0010).
 *
 * <p>Over {@code media.derivatives} (V0058). This is the store the upload
 * lifecycle runs on: {@code MediaLifecycleTests} exercises it against a real
 * PostgreSQL and a real MinIO rather than the in-memory stand-in it used while
 * the table was still only proposed.
 */
@Repository
public class JdbcMediaDerivativeStore implements MediaDerivativeStore {

    private final JdbcClient jdbc;

    public JdbcMediaDerivativeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(MediaDerivative derivative) {
        // The conflict target is the unique constraint on (asset_id, variant),
        // so two deliveries of the same availability event race to a single row
        // rather than to two rows describing the same rendition.
        return jdbc.sql("""
                INSERT INTO media.derivatives (
                    derivative_id, tenant_id, asset_id, variant, object_key, bucket,
                    content_type, size_bytes, checksum_sha256, width_px, height_px,
                    processor_version, created_at)
                VALUES (
                    :derivativeId, :tenantId, :assetId, :variant, :objectKey, :bucket,
                    :contentType, :sizeBytes, :checksum, :widthPx, :heightPx,
                    :processorVersion, :createdAt)
                ON CONFLICT (asset_id, variant) DO NOTHING
                """)
                        .param("derivativeId", derivative.derivativeId())
                        .param("tenantId", derivative.tenantId())
                        .param("assetId", derivative.assetId().value())
                        .param("variant", derivative.variant().name())
                        .param("objectKey", derivative.objectKey())
                        .param("bucket", derivative.bucket())
                        .param("contentType", derivative.contentType())
                        .param("sizeBytes", derivative.sizeBytes())
                        .param("checksum", derivative.checksumSha256())
                        .param("widthPx", derivative.widthPx())
                        .param("heightPx", derivative.heightPx())
                        .param("processorVersion", derivative.processorVersion())
                        .param("createdAt", OffsetDateTime.ofInstant(derivative.createdAt(), ZoneOffset.UTC))
                        .update()
                == 1;
    }

    @Override
    public Optional<MediaDerivative> find(UUID tenantId, MediaAssetId assetId, DerivativeVariant variant) {
        return jdbc.sql("""
                SELECT * FROM media.derivatives
                WHERE tenant_id = :tenantId AND asset_id = :assetId AND variant = :variant
                """)
                .param("tenantId", tenantId)
                .param("assetId", assetId.value())
                .param("variant", variant.name())
                .query(JdbcMediaDerivativeStore::mapDerivative)
                .optional();
    }

    @Override
    public List<MediaDerivative> findAll(UUID tenantId, MediaAssetId assetId) {
        return jdbc.sql("""
                SELECT * FROM media.derivatives
                WHERE tenant_id = :tenantId AND asset_id = :assetId
                ORDER BY width_px
                """)
                .param("tenantId", tenantId)
                .param("assetId", assetId.value())
                .query(JdbcMediaDerivativeStore::mapDerivative)
                .list();
    }

    private static MediaDerivative mapDerivative(ResultSet row, int rowNumber) throws SQLException {
        return new MediaDerivative(
                row.getObject("derivative_id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                new MediaAssetId(row.getObject("asset_id", UUID.class)),
                DerivativeVariant.valueOf(row.getString("variant")),
                row.getString("object_key"),
                row.getString("bucket"),
                row.getString("content_type"),
                row.getLong("size_bytes"),
                row.getString("checksum_sha256"),
                row.getInt("width_px"),
                row.getInt("height_px"),
                row.getString("processor_version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
