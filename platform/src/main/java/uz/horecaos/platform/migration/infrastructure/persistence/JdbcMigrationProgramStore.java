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

import uz.horecaos.platform.migration.application.MigrationProgramStore;
import uz.horecaos.platform.migration.application.ProgramStatus;

import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.horecaos.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Migration program persistence (ADR 0024).
 *
 * <p>The one table in this schema with no tenant column, and therefore the one
 * store here whose statements carry no tenant predicate. A program is the
 * platform-level unit that names a source environment, a target environment and
 * the policy version they were approved against; the same tenant appears under a
 * rehearsal program and under the production one, so scoping a program to a
 * tenant would make that pair unrepresentable.
 *
 * <p>Every write is a conditional {@code UPDATE} naming the status and version it
 * expects, for the same reason as everywhere else on the platform: nothing reads
 * a row, decides, and then writes.
 */
@Repository
public class JdbcMigrationProgramStore implements MigrationProgramStore {

    private static final String SELECT_PROGRAM = """
            SELECT id, name, status, source_environment, target_environment, policy_version,
                   started_at, completed_at, version
            FROM migration.programs""";

    private final JdbcClient jdbc;

    public JdbcMigrationProgramStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProgramRow> findById(UUID programId) {
        return jdbc.sql(SELECT_PROGRAM + " WHERE id = :id")
                .param("id", programId)
                .query(JdbcMigrationProgramStore::mapProgram)
                .optional();
    }

    @Override
    public Optional<ProgramRow> findByName(String name) {
        return jdbc.sql(SELECT_PROGRAM + " WHERE name = :name")
                .param("name", name)
                .query(JdbcMigrationProgramStore::mapProgram)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The two timestamps are written from the row rather than defaulted,
     * because {@code ck_program_started} and {@code ck_program_completed} state
     * pair completeness as equalities: a program created in any status other than
     * {@code PLANNING} owes the schema a start time, and one that hard-coded NULL
     * would be refused rather than storing a slightly wrong row.
     */
    @Override
    public void insert(ProgramRow program, Instant now) {
        // A HashMap because a program in PLANNING has neither timestamp, and
        // Map.of rejects the nulls that is made of.
        Map<String, Object> timestamps = new HashMap<>();
        timestamps.put("startedAt", utc(program.startedAt()));
        timestamps.put("completedAt", utc(program.completedAt()));

        jdbc.sql("""
                INSERT INTO migration.programs (
                    id, name, status, source_environment, target_environment, policy_version,
                    started_at, completed_at, version, created_at, updated_at)
                VALUES (:id, :name, :status, :source, :target, :policyVersion,
                        :startedAt, :completedAt, :version, :now, :now)
                """)
                .param("id", program.id()).param("name", program.name())
                .param("status", program.status().name())
                .param("source", program.sourceEnvironment())
                .param("target", program.targetEnvironment())
                .param("policyVersion", program.policyVersion())
                .params(timestamps)
                .param("version", program.version())
                .param("now", utc(now))
                .update();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Conditional on both the status and the version, so two operators pressing
     * different buttons on one program in the same second are decided by
     * PostgreSQL rather than by whichever thread was scheduled first. The loser
     * gets an empty result and is told what actually happened, instead of applying
     * its own outcome on top of the winner's.
     */
    @Override
    public Optional<Integer> updateStatus(UUID programId, ProgramStatus from, ProgramStatus to,
            int expectedVersion, Instant startedAt, Instant completedAt, Instant now) {

        Map<String, Object> timestamps = new HashMap<>();
        timestamps.put("startedAt", utc(startedAt));
        timestamps.put("completedAt", utc(completedAt));

        return jdbc.sql("""
                UPDATE migration.programs
                SET status = :to,
                    started_at = :startedAt,
                    completed_at = :completedAt,
                    version = version + 1,
                    updated_at = :now
                WHERE id = :id AND status = :from AND version = :expectedVersion
                RETURNING version
                """)
                .param("id", programId).param("from", from.name()).param("to", to.name())
                .params(timestamps)
                .param("expectedVersion", expectedVersion).param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    private static ProgramRow mapProgram(ResultSet row, int number) throws SQLException {
        return new ProgramRow(
                row.getObject("id", UUID.class),
                row.getString("name"),
                ProgramStatus.valueOf(row.getString("status")),
                row.getString("source_environment"),
                row.getString("target_environment"),
                row.getInt("policy_version"),
                instantOrNull(row, "started_at"),
                instantOrNull(row, "completed_at"),
                row.getInt("version"));
    }
}
