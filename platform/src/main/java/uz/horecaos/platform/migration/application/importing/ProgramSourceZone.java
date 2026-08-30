package uz.horecaos.platform.migration.application.importing;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * The zone the legacy server's naive timestamps are in
 * ({@code migration.programs.source_time_zone}, ADR 0024).
 *
 * <p>A port of its own rather than a field on {@code MigrationProgramStore.ProgramRow},
 * because this is asked once per import page and by nothing else in the control
 * plane, and widening the program row would put a migration-only column in front
 * of every program read on the platform.
 *
 * <p>{@code docs/domains/legacy-profile-findings.md} finding 2, structural: the
 * legacy {@code BaseModel} gives every table {@code created} and {@code updated}
 * typed without a timezone and defaulted to {@code datetime.now}, so their zone
 * is whatever the production application server was configured to and is recorded
 * nowhere. The answer comes from a deployment setting — {@code TZ},
 * {@code /etc/localtime}, the container spec — and never from the data. A
 * histogram over the development database fits Asia/Tashkent; that is a reason to
 * expect an answer, not the answer.
 */
public interface ProgramSourceZone {

    /**
     * The program's configured source zone, or empty while nobody has read it.
     *
     * <p>Empty is a stop, never a default. Reading Asia/Tashkent timestamps as UTC
     * shifts every historical order five hours, across the business-date boundary
     * the daily order number depends on: a day's orders renumber into the wrong
     * day, and the reconciliation meant to catch it compares two equally wrong
     * figures.
     */
    Optional<ZoneId> find(UUID programId);
}
