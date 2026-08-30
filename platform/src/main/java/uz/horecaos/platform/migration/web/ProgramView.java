package uz.horecaos.platform.migration.web;

import java.time.Instant;
import java.util.UUID;
import uz.horecaos.platform.migration.application.MigrationProgramStore.ProgramRow;
import uz.horecaos.platform.migration.application.ProgramStatus;

/**
 * One migration program as the control-plane console sees it.
 *
 * <p>A wire type of its own rather than the store's row, because the two answer
 * to different rules. The row is free to change shape when V0024's columns do;
 * this record cannot, because ADR 0031 makes removing a field or narrowing a type
 * a new major version.
 *
 * @param version the optimistic-concurrency token, echoed in the {@code ETag} and
 *                required back on every mutation
 */
public record ProgramView(
        UUID id,
        String name,
        ProgramStatus status,
        String sourceEnvironment,
        String targetEnvironment,
        int policyVersion,
        Instant startedAt,
        Instant completedAt,
        int version) {

    static ProgramView of(ProgramRow row) {
        return new ProgramView(
                row.id(),
                row.name(),
                row.status(),
                row.sourceEnvironment(),
                row.targetEnvironment(),
                row.policyVersion(),
                row.startedAt(),
                row.completedAt(),
                row.version());
    }
}
