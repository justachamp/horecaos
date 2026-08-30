package uz.horecaos.platform.migration.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.migration.application.importing.SourceCursorStore;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Extraction cursor persistence ({@code migration.source_cursors}, ADR 0024).
 *
 * <p>Two statements carry the whole restartability guarantee. {@link #open} is
 * {@code ON CONFLICT DO NOTHING}, so two migrators starting the same entity type
 * converge on one position rather than racing to create two. {@link #advance} is
 * conditional on the version, so a page retried after a lost commit cannot move a
 * cursor somebody else has already moved.
 *
 * <p>Both are called inside the transaction that holds the page's target writes,
 * which is what makes the advance and the import atomic.
 */
@Repository
public class JdbcSourceCursorStore implements SourceCursorStore {

    private static final String SELECT_CURSOR = """
            SELECT id, tenant_id, scope_id, entity_type, stable_key_column, last_stable_key,
                   watermark, watermark_column, advanced_by_run_id, transformation_version,
                   pages_committed, rows_committed, exhausted, version
            FROM migration.source_cursors""";

    private final JdbcClient jdbc;

    public JdbcSourceCursorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Cursor> find(UUID tenantId, UUID scopeId, String entityType) {
        return jdbc.sql(SELECT_CURSOR + """
                 WHERE tenant_id = :tenantId AND scope_id = :scopeId AND entity_type = :entityType
                """)
                .param("tenantId", tenantId).param("scopeId", scopeId)
                .param("entityType", entityType)
                .query(this::mapCursor)
                .optional();
    }

    @Override
    public boolean open(Cursor cursor, Instant now) {
        // A HashMap: a cursor nobody has read yet has no key, and an entity type
        // with no incremental feed has no watermark column either.
        Map<String, Object> optional = new HashMap<>();
        optional.put("lastStableKey", cursor.lastStableKey());
        optional.put("watermark", cursor.watermark());
        optional.put("watermarkColumn", cursor.watermarkColumn());

        return jdbc.sql("""
                INSERT INTO migration.source_cursors (
                    id, tenant_id, scope_id, entity_type, stable_key_column, last_stable_key,
                    watermark, watermark_column, advanced_by_run_id, transformation_version,
                    pages_committed, rows_committed, exhausted, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :scopeId, :entityType, :stableKeyColumn, :lastStableKey,
                    :watermark, :watermarkColumn, :runId, :transformationVersion,
                    :pagesCommitted, :rowsCommitted, :exhausted, 1, :now, :now)
                ON CONFLICT ON CONSTRAINT uq_source_cursor DO NOTHING
                """)
                .param("id", cursor.id()).param("tenantId", cursor.tenantId())
                .param("scopeId", cursor.scopeId()).param("entityType", cursor.entityType())
                .param("stableKeyColumn", cursor.stableKeyColumn())
                .params(optional)
                .param("runId", cursor.advancedByRunId())
                .param("transformationVersion", cursor.transformationVersion())
                .param("pagesCommitted", cursor.pagesCommitted())
                .param("rowsCommitted", cursor.rowsCommitted())
                .param("exhausted", cursor.exhausted())
                .param("now", utc(now))
                .update() == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code exhausted} is set with {@code OR}, never overwritten. A catch-up
     * run reading changes on an entity type whose backfill has finished would
     * otherwise clear the flag on the first page it read, and the next backfill
     * would start again from the beginning of a source it had already covered.
     */
    @Override
    public boolean advance(UUID tenantId, UUID scopeId, String entityType, Advance advance,
            int expectedVersion, Instant now) {
        Map<String, Object> optional = new HashMap<>();
        optional.put("lastStableKey", advance.lastStableKey());
        optional.put("watermark", advance.watermark());

        return jdbc.sql("""
                UPDATE migration.source_cursors
                SET last_stable_key = :lastStableKey,
                    watermark = :watermark,
                    advanced_by_run_id = :runId,
                    transformation_version = :transformationVersion,
                    pages_committed = :pagesCommitted,
                    rows_committed = :rowsCommitted,
                    exhausted = exhausted OR :exhausted,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND scope_id = :scopeId
                  AND entity_type = :entityType AND version = :expectedVersion
                """)
                .params(optional)
                .param("runId", advance.advancedByRunId())
                .param("transformationVersion", advance.transformationVersion())
                .param("pagesCommitted", advance.pagesCommitted())
                .param("rowsCommitted", advance.rowsCommitted())
                .param("exhausted", advance.exhausted())
                .param("now", utc(now))
                .param("tenantId", tenantId).param("scopeId", scopeId)
                .param("entityType", entityType)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private Cursor mapCursor(ResultSet row, int rowNumber) throws SQLException {
        return new Cursor(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("scope_id", UUID.class),
                row.getString("entity_type"),
                row.getString("stable_key_column"),
                row.getString("last_stable_key"),
                row.getString("watermark"),
                row.getString("watermark_column"),
                row.getObject("advanced_by_run_id", UUID.class),
                row.getInt("transformation_version"),
                row.getLong("pages_committed"),
                row.getLong("rows_committed"),
                row.getBoolean("exhausted"),
                row.getInt("version"));
    }
}
