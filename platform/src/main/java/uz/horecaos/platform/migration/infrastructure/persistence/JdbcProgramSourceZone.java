package uz.horecaos.platform.migration.infrastructure.persistence;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.migration.application.importing.ProgramSourceZone;

/**
 * Reads {@code migration.programs.source_time_zone} (ADR 0024).
 *
 * <p>The column is nullable and has no default, which is the whole design: a
 * default of UTC would be indistinguishable from a deployment somebody had
 * actually checked, and reading Asia/Tashkent timestamps as UTC shifts every
 * historical order five hours — across the business-date boundary that the legacy
 * daily order number depends on, so a day's orders renumber into the wrong day
 * and the reconciliation meant to catch it compares two equally wrong figures.
 */
@Repository
public class JdbcProgramSourceZone implements ProgramSourceZone {

    private final JdbcClient jdbc;

    public JdbcProgramSourceZone(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ZoneId> find(UUID programId) {
        return jdbc.sql("SELECT source_time_zone FROM migration.programs WHERE id = :id")
                .param("id", programId)
                .query(String.class)
                .optional()
                // The outer optional is "no such program"; this one is "the program
                // exists and nobody has read its deployment yet". Both are a stop,
                // and flattening them here is safe because neither may be defaulted.
                .flatMap(zone ->
                        zone == null || zone.isBlank() ? Optional.empty() : Optional.of(parse(zone, programId)));
    }

    /**
     * A stored zone the JVM does not recognise fails loudly rather than falling
     * back. The fallback would be the system default, which is the container's,
     * which is UTC — the exact answer this column exists to stop being assumed.
     */
    private static ZoneId parse(String zone, UUID programId) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw new IllegalStateException(
                    ("Program %s names source time zone '%s', which is not an IANA zone this JVM "
                                    + "knows. Falling back to the system default would silently reintroduce "
                                    + "the UTC assumption (ADR 0024).")
                            .formatted(programId, zone),
                    unknown);
        }
    }
}
