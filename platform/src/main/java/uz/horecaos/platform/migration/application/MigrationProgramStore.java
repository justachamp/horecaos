package uz.horecaos.platform.migration.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes {@code migration.programs}.
 *
 * <p>A program is the one table in this schema that is not tenant-scoped: it is
 * a platform-level unit, and the same tenant appears under a rehearsal program
 * and the production one. So these methods take no tenant, and that is a
 * deliberate exception to the rule the rest of this package follows rather than
 * an oversight to be copied.
 */
public interface MigrationProgramStore {

    Optional<ProgramRow> findById(UUID programId);

    /**
     * Resolves a program by its name, which {@code uq_program_name} makes unique.
     *
     * <p>This is the program table's idempotency mechanism. V0024 gives programs
     * no {@code idempotency_key} column, so a retried create is recognised by the
     * name it asked for rather than by the key the caller supplied.
     */
    Optional<ProgramRow> findByName(String name);

    void insert(ProgramRow program, Instant now);

    /**
     * Moves the program's status if it is still at {@code expectedVersion}.
     *
     * <p>{@code startedAt} and {@code completedAt} are written only where the
     * target status requires them; {@code ck_program_started} and {@code
     * ck_program_completed} state the pairing as equalities, so a store that
     * wrote one without the other would be refused rather than storing a running
     * program with no start time.
     *
     * @return the new version, or empty when another writer moved first
     */
    Optional<Integer> updateStatus(UUID programId, ProgramStatus from, ProgramStatus to,
            int expectedVersion, Instant startedAt, Instant completedAt, Instant now);

    /**
     * One migration of one source environment into one target environment.
     *
     * @param policyVersion the approved mapping and quarantine policy the program
     *                      executes, pinned so a later policy revision cannot
     *                      retroactively change what it was approved to do
     */
    record ProgramRow(
            UUID id,
            String name,
            ProgramStatus status,
            String sourceEnvironment,
            String targetEnvironment,
            int policyVersion,
            Instant startedAt,
            Instant completedAt,
            int version) { }
}
