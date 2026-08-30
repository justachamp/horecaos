package uz.horecaos.platform.migration.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.migration.application.MigrationQuarantineStore;
import uz.horecaos.platform.migration.domain.MappingStatus;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Quarantine persistence (ADR 0024, ADR 0029).
 *
 * <p>There is no payload anywhere in this store, and no method that would accept
 * one. A broken legacy row is not less personal than a valid one, so what is held
 * is a reference to it, a reason code from the approved vocabulary, and a pointer
 * into the protected evidence store — never the record that failed. The reason
 * code is pattern-constrained by the schema precisely so it cannot become the
 * field into which a diagnosing engineer pastes the failing row.
 */
@Repository
public class JdbcQuarantineStore implements MigrationQuarantineStore {

    private static final String SELECT_ITEM = """
            SELECT id, tenant_id, run_id, entity_type, legacy_id, reason_code,
                   sanitized_evidence_reference, status, resolution_code, resolved_by,
                   resolved_at
            FROM migration.quarantine_items""";

    private final JdbcClient jdbc;

    public JdbcQuarantineStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The item this run already filed for this legacy identity, per {@code uq_quarantine_item}. */
    @Override
    public Optional<QuarantineItemRow> findByKey(UUID tenantId, UUID runId, String entityType,
            String legacyId) {
        return jdbc.sql(SELECT_ITEM + """
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND entity_type = :entityType AND legacy_id = :legacyId
                """)
                .param("tenantId", tenantId).param("runId", runId)
                .param("entityType", entityType).param("legacyId", legacyId)
                .query(JdbcQuarantineStore::mapItem)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A plain insert, so a second file of the same legacy identity under the
     * same run hits {@code uq_quarantine_item} and fails. The caller has already
     * probed with {@link #findByKey} and does not reach here on a retry; swallowing
     * the conflict with {@code DO NOTHING} instead would make a genuine
     * double-file — two different reason codes for one row — silently keep the
     * first diagnosis and report success for the second.
     */
    @Override
    public void insert(QuarantineItemRow item, Instant now) {
        jdbc.sql("""
                INSERT INTO migration.quarantine_items (
                    id, tenant_id, run_id, entity_type, legacy_id, reason_code,
                    sanitized_evidence_reference, status, resolution_code, resolved_by,
                    resolved_at, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :runId, :entityType, :legacyId, :reasonCode,
                    :evidenceReference, :status, :resolutionCode, :resolvedBy,
                    :resolvedAt, :now, :now)
                """)
                .param("id", item.id()).param("tenantId", item.tenantId())
                .param("runId", item.runId()).param("entityType", item.entityType())
                .param("legacyId", item.legacyId()).param("reasonCode", item.reasonCode())
                .param("evidenceReference", item.sanitizedEvidenceReference())
                .param("status", item.status())
                .param("resolutionCode", item.resolutionCode())
                .param("resolvedBy", item.resolvedBy())
                .param("resolvedAt", utc(item.resolvedAt()))
                .param("now", utc(now))
                .update();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The upsert targets {@code uq_entity_mapping_key}, whose key is (scope,
     * entity type, legacy id) and carries no run: a row quarantined by a backfill
     * and quarantined again by the remediation that tried to fix it is one legacy
     * identity with one crosswalk entry, and the run that last touched it is a
     * column rather than part of the identity.
     *
     * <p>{@code target_id} is left null, which {@code ck_entity_mapping_quarantined}
     * states as an equality with the QUARANTINED status: a quarantined mapping
     * that named a target would be asserting that the row had in fact been
     * migrated.
     *
     * <p>Which is exactly why the update refuses a row that is already {@code
     * MAPPED}. Blanking a live crosswalk entry leaves the target entity in place
     * with nothing referring to it, and the next import — finding no mapping for
     * that legacy id — creates a second one. The duplicate is silent, survives
     * reconciliation by key count, and is only visible as money counted twice.
     * The predicate turns that into a refusal the caller has to handle.
     */
    @Override
    public boolean upsertQuarantinedMapping(UUID mappingId, UUID tenantId, UUID scopeId, UUID runId,
            String entityType, String legacyId, int transformationVersion, Instant now) {

        return jdbc.sql("""
                INSERT INTO migration.entity_mappings (
                    id, tenant_id, scope_id, entity_type, legacy_id, target_id,
                    transformation_version, mapping_status, run_id, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :scopeId, :entityType, :legacyId, NULL,
                    :transformationVersion, :status, :runId, :now, :now)
                ON CONFLICT ON CONSTRAINT uq_entity_mapping_key DO UPDATE
                SET target_id = NULL,
                    superseded_by_mapping_id = NULL,
                    transformation_version = EXCLUDED.transformation_version,
                    mapping_status = EXCLUDED.mapping_status,
                    run_id = EXCLUDED.run_id,
                    updated_at = EXCLUDED.updated_at
                WHERE migration.entity_mappings.mapping_status <> 'MAPPED'
                """)
                .param("id", mappingId).param("tenantId", tenantId).param("scopeId", scopeId)
                .param("entityType", entityType).param("legacyId", legacyId)
                .param("transformationVersion", transformationVersion)
                .param("status", MappingStatus.QUARANTINED.name())
                .param("runId", runId)
                .param("now", utc(now))
                // Zero rows means, and can only mean, that the conflicting row was
                // MAPPED: an insert answers one, and so does an update to any other
                // status. The count is therefore a reliable signal rather than a
                // guess about why nothing happened.
                .update() == 1;
    }

    @Override
    public Optional<QuarantineItemRow> findById(UUID tenantId, UUID itemId) {
        return jdbc.sql(SELECT_ITEM + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", itemId)
                .query(JdbcQuarantineStore::mapItem)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The resolver, the moment and the resolution code move together in one
     * statement because the schema states them as one fact: a resolution cannot be
     * recorded without an owner, and an open item cannot carry a stale one. The
     * {@code status = 'OPEN'} predicate is what makes a replayed settlement a
     * false rather than a second, different account of how the item was closed.
     */
    @Override
    public boolean resolve(UUID tenantId, UUID itemId, String resolutionCode, String resolvedBy,
            Instant resolvedAt) {
        return jdbc.sql("""
                UPDATE migration.quarantine_items
                SET status = 'RESOLVED',
                    resolution_code = :resolutionCode,
                    resolved_by = :resolvedBy,
                    resolved_at = :now,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN'
                """)
                .param("tenantId", tenantId).param("id", itemId)
                .param("resolutionCode", resolutionCode).param("resolvedBy", resolvedBy)
                .param("now", utc(resolvedAt))
                .update() == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The one gate question in this package that needs a join. Quarantine items
     * hang off the run that filed them and carry no scope of their own — unlike
     * reconciliation results, where the scope is denormalized precisely so the
     * cutover gate can ask its question with a single index probe. The join here
     * runs along {@code ix_runs_scope} and is asked when a scope tries to move, not
     * on the hot path of a write.
     */
    @Override
    public int openCount(UUID tenantId, UUID scopeId) {
        return jdbc.sql("""
                SELECT count(*)
                FROM migration.quarantine_items AS item
                JOIN migration.runs AS run
                  ON run.tenant_id = item.tenant_id AND run.id = item.run_id
                WHERE item.tenant_id = :tenantId AND run.scope_id = :scopeId
                  AND item.status = 'OPEN'
                """)
                .param("tenantId", tenantId).param("scopeId", scopeId)
                .query(Integer.class)
                .single();
    }

    private static QuarantineItemRow mapItem(ResultSet row, int number) throws SQLException {
        return new QuarantineItemRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("run_id", UUID.class),
                row.getString("entity_type"),
                row.getString("legacy_id"),
                row.getString("reason_code"),
                row.getString("sanitized_evidence_reference"),
                row.getString("status"),
                row.getString("resolution_code"),
                row.getString("resolved_by"),
                instantOrNull(row, "resolved_at"));
    }
}
