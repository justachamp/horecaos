package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code conversations.flow_documents} (V0108). Authoring never edits a row —
 * {@link #insert} always creates the next version; {@link #activate} is the
 * only mutation an existing row ever receives, and only ever
 * {@code is_active}.
 */
@Repository
class FlowDocumentRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    FlowDocumentRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<Row> findActive(UUID tenantId, UUID brandId, String flowKey) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, flow_key, version, document_yaml, is_active,
                       description, authored_by, created_at
                FROM conversations.flow_documents
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND flow_key = :flowKey AND is_active
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("flowKey", flowKey)
                .query(FlowDocumentRepository::map)
                .optional();
    }

    Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, flow_key, version, document_yaml, is_active,
                       description, authored_by, created_at
                FROM conversations.flow_documents
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .query(FlowDocumentRepository::map)
                .optional();
    }

    List<Row> list(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, flow_key, version, document_yaml, is_active,
                       description, authored_by, created_at
                FROM conversations.flow_documents
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY flow_key, version DESC
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(FlowDocumentRepository::map)
                .list();
    }

    int nextVersion(UUID tenantId, UUID brandId, String flowKey) {
        return jdbc.sql("""
                SELECT max(version) FROM conversations.flow_documents
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND flow_key = :flowKey
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("flowKey", flowKey)
                .query(Integer.class)
                .optional()
                .map(max -> max + 1)
                .orElse(1);
    }

    /** Clears {@code is_active} on the currently active version, if any — the first half of a publish. */
    void deactivateActive(UUID tenantId, UUID brandId, String flowKey) {
        jdbc.sql("""
                UPDATE conversations.flow_documents SET is_active = false
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND flow_key = :flowKey AND is_active
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("flowKey", flowKey)
                .update();
    }

    Row insert(
            UUID tenantId,
            UUID brandId,
            String flowKey,
            int version,
            String documentYaml,
            @Nullable String description,
            String authoredBy,
            boolean active) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO conversations.flow_documents (
                    id, tenant_id, brand_id, flow_key, version, document_yaml, is_active,
                    description, authored_by, created_at)
                VALUES (:id, :tenantId, :brandId, :flowKey, :version, :yaml, :active, :description, :authoredBy, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("flowKey", flowKey)
                .param("version", version)
                .param("yaml", documentYaml)
                .param("active", active)
                .param("description", description)
                .param("authoredBy", authoredBy)
                .param("now", utc(now))
                .update();
        return new Row(id, tenantId, brandId, flowKey, version, documentYaml, active, description, authoredBy, now);
    }

    private static Row map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        java.sql.Timestamp createdAt = row.getTimestamp("created_at");
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("flow_key"),
                row.getInt("version"),
                row.getString("document_yaml"),
                row.getBoolean("is_active"),
                row.getString("description"),
                row.getString("authored_by"),
                createdAt.toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record Row(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String flowKey,
            int version,
            String documentYaml,
            boolean active,
            @Nullable String description,
            String authoredBy,
            Instant createdAt) {}
}
