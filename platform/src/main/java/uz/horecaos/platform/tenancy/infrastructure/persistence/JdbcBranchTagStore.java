package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 10.10d: a chain's own branch tag registry ({@code tenant.branch_tags},
 * V0256) and which branch carries which tag ({@code tenant.location_branch_tags},
 * V0257).
 */
@Repository
public class JdbcBranchTagStore {

    private final JdbcClient jdbc;

    public JdbcBranchTagStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BranchTag> list(UUID tenantId, boolean activeOnly) {
        String sql = "SELECT id, code, display_name, status FROM tenant.branch_tags WHERE tenant_id = :tenantId"
                + (activeOnly ? " AND status = 'ACTIVE'" : "") + " ORDER BY display_name";
        return jdbc.sql(sql)
                .param("tenantId", tenantId)
                .query(JdbcBranchTagStore::tag)
                .list();
    }

    public Optional<BranchTag> find(UUID tenantId, UUID tagId) {
        return jdbc.sql("SELECT id, code, display_name, status FROM tenant.branch_tags"
                        + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", tagId)
                .query(JdbcBranchTagStore::tag)
                .optional();
    }

    public void insert(UUID tenantId, UUID id, String code, String displayName) {
        jdbc.sql("""
                        INSERT INTO tenant.branch_tags (id, tenant_id, code, display_name, status)
                        VALUES (:id, :tenantId, :code, :displayName, 'ACTIVE')
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("displayName", displayName)
                .update();
    }

    /** True when a row was archived; false when it was already archived. */
    public boolean archive(UUID tenantId, UUID tagId) {
        return jdbc.sql("""
                        UPDATE tenant.branch_tags SET status = 'ARCHIVED', updated_at = now()
                         WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                        """).param("tenantId", tenantId).param("id", tagId).update() == 1;
    }

    /** Every {@code (locationId, tagId)} assignment for the tenant, for the settings screen's filter grid. */
    public List<Assignment> assignments(UUID tenantId) {
        return jdbc.sql("""
                        SELECT location_id, tag_id, assigned_at
                          FROM tenant.location_branch_tags
                         WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((ResultSet row, int number) -> new Assignment(
                        row.getObject("location_id", UUID.class),
                        row.getObject("tag_id", UUID.class),
                        row.getObject("assigned_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public List<UUID> tagsOf(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                        SELECT tag_id FROM tenant.location_branch_tags
                         WHERE tenant_id = :tenantId AND location_id = :locationId
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(UUID.class)
                .list();
    }

    public void assign(UUID tenantId, UUID locationId, UUID tagId) {
        jdbc.sql("""
                        INSERT INTO tenant.location_branch_tags (tenant_id, location_id, tag_id)
                        VALUES (:tenantId, :locationId, :tagId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("tagId", tagId)
                .update();
    }

    public void unassign(UUID tenantId, UUID locationId, UUID tagId) {
        jdbc.sql("""
                        DELETE FROM tenant.location_branch_tags
                         WHERE tenant_id = :tenantId AND location_id = :locationId AND tag_id = :tagId
                        """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("tagId", tagId)
                .update();
    }

    private static BranchTag tag(ResultSet row, int number) throws SQLException {
        return new BranchTag(
                row.getObject("id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("status"));
    }

    public record BranchTag(UUID id, String code, String displayName, String status) {}

    public record Assignment(UUID locationId, UUID tagId, Instant assignedAt) {}
}
