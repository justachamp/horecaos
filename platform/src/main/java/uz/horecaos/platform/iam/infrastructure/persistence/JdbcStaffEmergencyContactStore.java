package uz.horecaos.platform.iam.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam.staff_emergency_contacts} (ADR 0139, V0456): at most three per
 * member, replaced as a whole.
 *
 * <p>The name and phone are a third party's data and arrive here already
 * envelope-encrypted, bound to the row's own id; nothing in this class opens
 * them. The set is replaced by deleting and re-inserting inside the caller's
 * transaction, so a reader never sees a half-written set and the unique slot
 * ({@code uq_emergency_contact_slot}) is free again for the new rows.
 */
@Repository
public class JdbcStaffEmergencyContactStore {

    private final JdbcClient jdbc;

    public JdbcStaffEmergencyContactStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContactRow> forMember(UUID tenantId, UUID memberId) {
        return jdbc.sql("""
                        SELECT id, tenant_id, staff_member_id, relationship_code, protected_name, protected_phone,
                               sort_order, version
                          FROM iam.staff_emergency_contacts
                         WHERE tenant_id = :tenantId AND staff_member_id = :memberId
                         ORDER BY sort_order
                        """)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .query((row, number) -> new ContactRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("staff_member_id", UUID.class),
                        row.getString("relationship_code"),
                        row.getString("protected_name"),
                        row.getString("protected_phone"),
                        row.getInt("sort_order"),
                        row.getInt("version")))
                .list();
    }

    public int count(UUID tenantId, UUID memberId) {
        return jdbc.sql(
                        "SELECT count(*) FROM iam.staff_emergency_contacts WHERE tenant_id = :t AND staff_member_id = :m")
                .param("t", tenantId)
                .param("m", memberId)
                .query(Integer.class)
                .single();
    }

    public void deleteAll(UUID tenantId, UUID memberId) {
        jdbc.sql("DELETE FROM iam.staff_emergency_contacts WHERE tenant_id = :t AND staff_member_id = :m")
                .param("t", tenantId)
                .param("m", memberId)
                .update();
    }

    public void insert(ContactRow row, Instant now) {
        jdbc.sql("""
                INSERT INTO iam.staff_emergency_contacts (
                    id, tenant_id, staff_member_id, relationship_code, protected_name, protected_phone,
                    sort_order, version, created_at, updated_at)
                VALUES (:id, :tenantId, :memberId, :relationship, :name, :phone, :slot, 1, :now, :now)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("memberId", row.staffMemberId())
                .param("relationship", row.relationshipCode())
                .param("name", row.protectedName())
                .param("phone", row.protectedPhone())
                .param("slot", row.sortOrder())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** One stored contact. {@code toString} is overridden so ciphertext never lands in a log. */
    public record ContactRow(
            UUID id,
            UUID tenantId,
            UUID staffMemberId,
            String relationshipCode,
            String protectedName,
            String protectedPhone,
            int sortOrder,
            int version) {

        @Override
        public String toString() {
            return "ContactRow[id=" + id + ", relationshipCode=" + relationshipCode + ", sortOrder=" + sortOrder + "]";
        }
    }
}
