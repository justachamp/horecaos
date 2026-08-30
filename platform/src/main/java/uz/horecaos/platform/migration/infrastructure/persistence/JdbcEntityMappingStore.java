package uz.horecaos.platform.migration.infrastructure.persistence;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.migration.domain.MappingStatus;

/**
 * Crosswalk persistence (ADR 0024).
 *
 * <p>The table that makes "no legacy row was forgotten" provable, which is why a
 * row that could not be imported is still written here, with QUARANTINED, rather
 * than left out: absence would be indistinguishable from never having been seen.
 *
 * <p>Legacy ids are text and not uuids, because the estate being retired keys on
 * integers, strings and composites. Coercing those into a uuid is the point at
 * which a crosswalk starts inventing the identities it exists to record.
 */
@Repository
public class JdbcEntityMappingStore {

    private static final String SELECT_MAPPING = """
            SELECT id, tenant_id, scope_id, entity_type, legacy_id, target_id, source_version,
                   target_version, transformation_version, mapping_status,
                   superseded_by_mapping_id, run_id, created_at, updated_at
            FROM migration.entity_mappings""";

    private final JdbcClient jdbc;

    public JdbcEntityMappingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records where one legacy identity ended up, idempotently.
     *
     * <p>ADR 0024 requires runs to be restartable and safe to repeat, and this
     * statement is where that requirement is actually met. The conflict target is
     * the crosswalk's own key, so the second import of a legacy row finds the
     * mapping it already has instead of minting a second target entity; without
     * it, a backfill killed mid-page duplicates every row of the page it was
     * killed inside, and the migration's identity guarantee is gone.
     *
     * <p>The update refuses to touch a SUPERSEDED row. Supersession is the record
     * of an approved human merge of two legacy identities, and re-running the
     * migrator over the losing identity must not quietly revive it. The schema
     * agrees, by a different route: {@code ck_entity_mapping_supersession} ties
     * the status to the surviving mapping, so an unguarded upsert would not
     * resurrect the row, it would abort the whole page with a constraint
     * violation.
     *
     * <p>The tenant predicate is on the update branch because a scope id is a UUID
     * a caller supplied. The insert branch is guarded by the schema instead: the
     * foreign key is on {@code (tenant_id, scope_id)}, so a mapping cannot be
     * attached to another tenant's scope in the first place.
     *
     * @return the mapping id, or empty when the crosswalk row is superseded and
     *         only a reviewed remediation may move it
     */
    public Optional<UUID> upsert(EntityMapping mapping) {
        // A HashMap: a quarantined mapping has no target by construction, and a
        // source that versions nothing supplies neither version.
        Map<String, Object> optional = new HashMap<>();
        optional.put("targetId", mapping.targetId());
        optional.put("sourceVersion", mapping.sourceVersion());
        optional.put("targetVersion", mapping.targetVersion());

        return jdbc.sql("""
                INSERT INTO migration.entity_mappings (
                    id, tenant_id, scope_id, entity_type, legacy_id, target_id, source_version,
                    target_version, transformation_version, mapping_status, run_id,
                    created_at, updated_at)
                VALUES (
                    :id, :tenantId, :scopeId, :entityType, :legacyId, :targetId, :sourceVersion,
                    :targetVersion, :transformationVersion, :status, :runId, :now, :now)
                ON CONFLICT ON CONSTRAINT uq_entity_mapping_key DO UPDATE
                SET target_id = EXCLUDED.target_id,
                    source_version = EXCLUDED.source_version,
                    target_version = EXCLUDED.target_version,
                    transformation_version = EXCLUDED.transformation_version,
                    mapping_status = EXCLUDED.mapping_status,
                    run_id = EXCLUDED.run_id,
                    updated_at = EXCLUDED.updated_at
                WHERE migration.entity_mappings.tenant_id = :tenantId
                  AND migration.entity_mappings.mapping_status <> 'SUPERSEDED'
                RETURNING id
                """)
                .param("id", mapping.mappingId())
                .param("tenantId", mapping.tenantId())
                .param("scopeId", mapping.scopeId())
                .param("entityType", mapping.entityType())
                .param("legacyId", mapping.legacyId())
                .params(optional)
                .param("transformationVersion", mapping.transformationVersion())
                .param("status", mapping.status().name())
                .param("runId", mapping.runId())
                .param("now", utc(mapping.occurredAt()))
                .query(UUID.class)
                .optional();
    }

    /** Resolves a legacy identifier forward, which is the crosswalk's whole purpose. */
    public Optional<EntityMappingRow> find(UUID tenantId, UUID scopeId, String entityType, String legacyId) {
        return jdbc.sql(SELECT_MAPPING + """
                 WHERE tenant_id = :tenantId AND scope_id = :scopeId
                   AND entity_type = :entityType AND legacy_id = :legacyId
                """)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .param("entityType", entityType)
                .param("legacyId", legacyId)
                .query(JdbcEntityMappingStore::mapEntityMapping)
                .optional();
    }

    /**
     * Resolves a target identifier back to the legacy identities that produced it.
     *
     * <p>A list and not an optional. Two legacy ids may point at one target after
     * an approved merge, which is why {@code ix_entity_mappings_target} is an
     * index and not a unique constraint, and a rollback that assumed one answer
     * would silently drop the merged-away identity.
     */
    public List<EntityMappingRow> findByTarget(UUID tenantId, UUID scopeId, String entityType, UUID targetId) {
        return jdbc.sql(SELECT_MAPPING + """
                 WHERE tenant_id = :tenantId AND scope_id = :scopeId
                   AND entity_type = :entityType AND target_id = :targetId
                 ORDER BY created_at, id
                """)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .param("entityType", entityType)
                .param("targetId", targetId)
                .query(JdbcEntityMappingStore::mapEntityMapping)
                .list();
    }

    /**
     * Records an approved merge of two legacy identities.
     *
     * <p>Only a MAPPED row can be superseded, and the schema is what says so:
     * {@code ck_entity_mapping_target} ties QUARANTINED to the absence of a
     * target, so marking a quarantined row SUPERSEDED would leave it with neither
     * a target nor a quarantine status and the constraint would refuse it. A
     * quarantine item is settled through its own resolution, not by being merged
     * away.
     *
     * <p>The survivor is checked as carefully as the loser. Its foreign key is on
     * the id alone, so nothing in the schema stops a caller from naming a mapping
     * in another tenant, or under another scope, as the surviving identity — the
     * {@code EXISTS} is where that is refused.
     *
     * @return whether this caller performed the supersession
     */
    public boolean supersede(UUID tenantId, UUID mappingId, UUID survivingMappingId, UUID runId, Instant now) {
        return jdbc.sql("""
                UPDATE migration.entity_mappings AS mapping
                SET mapping_status = 'SUPERSEDED',
                    superseded_by_mapping_id = :survivorId,
                    run_id = :runId,
                    updated_at = :now
                WHERE mapping.tenant_id = :tenantId AND mapping.id = :id
                  AND mapping.mapping_status = 'MAPPED'
                  AND EXISTS (
                      SELECT 1 FROM migration.entity_mappings AS survivor
                      WHERE survivor.id = :survivorId
                        AND survivor.tenant_id = :tenantId
                        AND survivor.scope_id = mapping.scope_id
                        AND survivor.mapping_status = 'MAPPED')
                """)
                        .param("tenantId", tenantId)
                        .param("id", mappingId)
                        .param("survivorId", survivingMappingId)
                        .param("runId", runId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** One scope's crosswalk for one entity type, oldest first. */
    public List<EntityMappingRow> listForScope(
            UUID tenantId, UUID scopeId, String entityType, MigrationPageCursor after, int limit) {
        return jdbc.sql(SELECT_MAPPING + """
                 WHERE tenant_id = :tenantId AND scope_id = :scopeId
                   AND entity_type = :entityType
                   AND (CAST(:afterId AS uuid) IS NULL
                        OR (created_at, id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY created_at, id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .param("entityType", entityType)
                .params(MigrationPageCursor.params(after))
                .param("limit", limit)
                .query(JdbcEntityMappingStore::mapEntityMapping)
                .list();
    }

    /**
     * How many legacy identities of one type this scope saw and could not map.
     *
     * <p>Answered from {@code ix_entity_mappings_unmapped}, which exists for this
     * question: it is the number an operator reads next to "and every one of them
     * is accounted for" before asking for a cutover.
     */
    public long countQuarantined(UUID tenantId, UUID scopeId, String entityType) {
        return jdbc.sql("""
                SELECT count(*) FROM migration.entity_mappings
                WHERE tenant_id = :tenantId AND scope_id = :scopeId
                  AND entity_type = :entityType AND mapping_status = 'QUARANTINED'
                """)
                .param("tenantId", tenantId)
                .param("scopeId", scopeId)
                .param("entityType", entityType)
                .query(Long.class)
                .single();
    }

    private static EntityMappingRow mapEntityMapping(ResultSet row, int number) throws SQLException {
        return new EntityMappingRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("scope_id", UUID.class),
                row.getString("entity_type"),
                row.getString("legacy_id"),
                row.getObject("target_id", UUID.class),
                row.getString("source_version"),
                // getLong would answer 0 for a source whose target carries no
                // version, and "the target was at revision zero when we wrote it"
                // is the sentence a later edit would be compared against.
                row.getObject("target_version", Long.class),
                row.getInt("transformation_version"),
                MappingStatus.valueOf(row.getString("mapping_status")),
                row.getObject("superseded_by_mapping_id", UUID.class),
                row.getObject("run_id", UUID.class),
                instantOrNull(row, "created_at"),
                instantOrNull(row, "updated_at"));
    }

    /**
     * @param sourceVersion the source row version this mapping was built from, in
     *                      whatever the source calls a version. Compared as an
     *                      opaque token and never ordered
     * @param targetVersion the target aggregate's version straight after the
     *                      upsert, so a later human edit on the target side stays
     *                      distinguishable from the migrator's own write
     */
    public record EntityMapping(
            UUID mappingId,
            UUID tenantId,
            UUID scopeId,
            String entityType,
            String legacyId,
            UUID targetId,
            String sourceVersion,
            Long targetVersion,
            int transformationVersion,
            MappingStatus status,
            UUID runId,
            Instant occurredAt) {}

    public record EntityMappingRow(
            UUID mappingId,
            UUID tenantId,
            UUID scopeId,
            String entityType,
            String legacyId,
            UUID targetId,
            String sourceVersion,
            Long targetVersion,
            int transformationVersion,
            MappingStatus status,
            UUID supersededByMappingId,
            UUID runId,
            Instant createdAt,
            Instant updatedAt) {}
}
