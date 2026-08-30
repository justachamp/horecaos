package uz.horecaos.platform.migration.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.migration.application.importing.TransformationRegistryStore;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Transformation registry persistence ({@code migration.transformations}, ADR
 * 0024).
 *
 * <p>Insert and read only, matching the grant. Retirement is an UPDATE that the
 * application role does not hold: a declaration is what crosswalk rows point back
 * to, and retiring one is a decision with an approver rather than something a
 * migrator can do to its own evidence on the way past.
 */
@Repository
public class JdbcTransformationRegistryStore implements TransformationRegistryStore {

    private static final String SELECT_DECLARATION = """
            SELECT id, program_id, entity_type, transformation_version, rule_digest, summary,
                   declared_by, retired_at
            FROM migration.transformations""";

    private final JdbcClient jdbc;

    public JdbcTransformationRegistryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Declaration> findCurrent(UUID programId, String entityType) {
        // The partial unique index guarantees at most one live row, so this needs
        // no ordering and no limit — and if the index were ever dropped, two rows
        // would surface here as an error rather than as an arbitrary choice.
        return jdbc.sql(SELECT_DECLARATION + """
                 WHERE program_id = :programId AND entity_type = :entityType
                   AND retired_at IS NULL
                """)
                .param("programId", programId).param("entityType", entityType)
                .query(this::mapDeclaration)
                .optional();
    }

    @Override
    public Optional<Declaration> find(UUID programId, String entityType, int transformationVersion) {
        return jdbc.sql(SELECT_DECLARATION + """
                 WHERE program_id = :programId AND entity_type = :entityType
                   AND transformation_version = :version
                """)
                .param("programId", programId).param("entityType", entityType)
                .param("version", transformationVersion)
                .query(this::mapDeclaration)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two conflict targets, and both are deliberate. Re-declaring the same
     * version is a replay; declaring the same digest under a new number is a
     * version that means nothing new, which the unique constraint refuses so that
     * a remediation is never started over rows nothing happened to.
     */
    @Override
    public boolean declare(Declaration declaration, Instant now) {
        return jdbc.sql("""
                INSERT INTO migration.transformations (
                    id, program_id, entity_type, transformation_version, rule_digest, summary,
                    declared_by, created_at)
                VALUES (
                    :id, :programId, :entityType, :version, :digest, :summary, :declaredBy, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", declaration.id())
                .param("programId", declaration.programId())
                .param("entityType", declaration.entityType())
                .param("version", declaration.transformationVersion())
                .param("digest", declaration.ruleDigest())
                .param("summary", declaration.summary())
                .param("declaredBy", declaration.declaredBy())
                .param("now", utc(now))
                .update() == 1;
    }

    private Declaration mapDeclaration(ResultSet row, int rowNumber) throws SQLException {
        return new Declaration(
                row.getObject("id", UUID.class),
                row.getObject("program_id", UUID.class),
                row.getString("entity_type"),
                row.getInt("transformation_version"),
                row.getString("rule_digest"),
                row.getString("summary"),
                row.getString("declared_by"),
                instantOrNull(row, "retired_at"));
    }
}
