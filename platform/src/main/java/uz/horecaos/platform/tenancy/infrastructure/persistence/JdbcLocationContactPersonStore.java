package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code tenant.location_contact_persons} (ADR 0139, V0455): who, by name and
 * role, a colleague or the head office calls about a branch.
 *
 * <p>A contact is either a colleague (a staff member id, no copy of anyone's
 * phone) or an outside person (a protected name and phone), and arrives here
 * already sealed: nothing in this class opens a ciphertext. The set is replaced
 * as a whole inside the caller's transaction, guarded by the location's own
 * version, which this class also moves -- the contacts are part of the branch
 * aggregate, so a second tab editing the branch's profile and one editing its
 * contacts conflict instead of overwriting each other.
 */
@Repository
public class JdbcLocationContactPersonStore {

    private final JdbcClient jdbc;

    public JdbcLocationContactPersonStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The location's version, or empty when the brand has no such location in this tenant. */
    public Optional<Long> locationVersion(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("SELECT version FROM tenant.locations WHERE tenant_id = :t AND brand_id = :b AND id = :l")
                .param("t", tenantId)
                .param("b", brandId)
                .param("l", locationId)
                .query(Long.class)
                .optional();
    }

    /** As {@link #locationVersion}, taking the row lock for the rest of the transaction. */
    public Optional<Long> lockLocationVersion(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                        SELECT version FROM tenant.locations
                         WHERE tenant_id = :t AND brand_id = :b AND id = :l FOR UPDATE
                        """)
                .param("t", tenantId)
                .param("b", brandId)
                .param("l", locationId)
                .query(Long.class)
                .optional();
    }

    public boolean bumpLocationVersion(UUID tenantId, UUID locationId, long expectedVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.locations SET version = version + 1, updated_at = :now
                         WHERE tenant_id = :t AND id = :l AND version = :v
                        """)
                        .param("t", tenantId)
                        .param("l", locationId)
                        .param("v", expectedVersion)
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .update()
                == 1;
    }

    public List<ContactRow> forLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                        SELECT id, tenant_id, location_id, relationship_code, staff_member_id,
                               protected_name, protected_phone, version
                          FROM tenant.location_contact_persons
                         WHERE tenant_id = :t AND location_id = :l
                         ORDER BY relationship_code, created_at, id
                        """)
                .param("t", tenantId)
                .param("l", locationId)
                .query((row, number) -> new ContactRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("relationship_code"),
                        row.getObject("staff_member_id", UUID.class),
                        row.getString("protected_name"),
                        row.getString("protected_phone"),
                        row.getInt("version")))
                .list();
    }

    public void deleteAll(UUID tenantId, UUID locationId) {
        jdbc.sql("DELETE FROM tenant.location_contact_persons WHERE tenant_id = :t AND location_id = :l")
                .param("t", tenantId)
                .param("l", locationId)
                .update();
    }

    public void insert(ContactRow row, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.location_contact_persons (
                    id, tenant_id, location_id, relationship_code, staff_member_id,
                    protected_name, protected_phone, version, created_at, updated_at)
                VALUES (:id, :t, :l, :relationship, :member, :name, :phone, 1, :now, :now)
                """)
                .param("id", row.id())
                .param("t", row.tenantId())
                .param("l", row.locationId())
                .param("relationship", row.relationshipCode())
                .param("member", row.staffMemberId())
                .param("name", row.protectedName())
                .param("phone", row.protectedPhone())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** One stored contact. {@code toString} is overridden so ciphertext never lands in a log. */
    public record ContactRow(
            UUID id,
            UUID tenantId,
            UUID locationId,
            String relationshipCode,
            @Nullable UUID staffMemberId,
            @Nullable String protectedName,
            @Nullable String protectedPhone,
            int version) {

        @Override
        public String toString() {
            return "ContactRow[id=" + id + ", relationshipCode=" + relationshipCode + "]";
        }
    }
}
