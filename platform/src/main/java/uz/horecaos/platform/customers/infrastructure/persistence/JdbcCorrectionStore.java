package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code customer.profile_corrections} (V0684): the statements, and nothing about who may run them. */
@Repository
public class JdbcCorrectionStore {

    private final JdbcClient jdbc;

    public JdbcCorrectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records one correction with the value it replaced, already protected and bound to {@code id}. */
    public void insert(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String subject,
            @Nullable UUID targetId,
            String evidenceReference,
            String priorValueProtected,
            String actorType,
            String actorId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.profile_corrections (
                    id, tenant_id, customer_account_id, subject, target_id, evidence_reference,
                    prior_value_protected, corrected_by_actor_type, corrected_by_actor_id, corrected_at)
                VALUES (:id, :tenantId, :accountId, :subject, :targetId, :evidence,
                        :prior, :actorType, :actorId, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("subject", subject)
                .param("targetId", targetId)
                .param("evidence", evidenceReference)
                .param("prior", priorValueProtected)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** One account's corrections, newest first. The prior value is not selected: reading it is the export's act. */
    public List<CorrectionRow> history(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, customer_account_id, subject, target_id, evidence_reference,
                       corrected_by_actor_type, corrected_by_actor_id, corrected_at
                  FROM customer.profile_corrections
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY corrected_at DESC, id DESC
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcCorrectionStore::row)
                .list();
    }

    /** The newest corrections across the tenant, for the data-privacy worklist. */
    public List<CorrectionRow> recentForTenant(UUID tenantId, int limit) {
        return jdbc.sql("""
                SELECT id, customer_account_id, subject, target_id, evidence_reference,
                       corrected_by_actor_type, corrected_by_actor_id, corrected_at
                  FROM customer.profile_corrections
                 WHERE tenant_id = :tenantId
                 ORDER BY corrected_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(JdbcCorrectionStore::row)
                .list();
    }

    private static CorrectionRow row(ResultSet row, int number) throws SQLException {
        return new CorrectionRow(
                row.getObject("id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("subject"),
                row.getObject("target_id", UUID.class),
                row.getString("evidence_reference"),
                row.getString("corrected_by_actor_type"),
                row.getString("corrected_by_actor_id"),
                row.getObject("corrected_at", OffsetDateTime.class).toInstant());
    }

    /** One correction, without the value it replaced. */
    public record CorrectionRow(
            UUID id,
            UUID customerAccountId,
            String subject,
            @Nullable UUID targetId,
            String evidenceReference,
            String correctedByActorType,
            String correctedByActorId,
            Instant correctedAt) {}
}
