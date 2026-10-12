package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.reporting.application.DayCloseService.RebuildStatus;
import uz.horecaos.platform.reporting.application.DayCloseService.StationLoadRebuild;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * The one-shot, operator-run backfill of {@code reporting.fact_station_load} (ADR 0163, Specification).
 *
 * <p>Without it the first three same-weekday dates after the fact ships answer {@code TOO_FEW_DATES}: the
 * fact exists only from the close that first wrote it. Per tenant it calls {@link
 * DayCloseService#rebuildStationLoad} for each of the {@value #DAYS} business dates before the cutover (the
 * first business date the live close wrote the fact), oldest first, one transaction per date. It is never a
 * migration (a migration does not read tenants' data) and never scheduled: an operator starts it, through
 * {@link StationLoadBackfillRunner}, with the cutover date as its argument.
 *
 * <p><strong>It refuses a date on or after the cutover</strong>, so a day a manager has planned from is never
 * rewritten, and a date the rebuild refuses for {@code BOUNDARY_CHANGED} is listed in the report. It can be
 * re-run: a rebuild over unchanged tickets reproduces the same rows.
 */
@Service
public class StationLoadBackfill {

    /** Eight weeks: eight same-weekday dates per weekday, past the twelve the plan can ask for only at the top. */
    public static final int DAYS = 56;

    private static final Logger log = LoggerFactory.getLogger(StationLoadBackfill.class);

    private final DayCloseService dayClose;
    private final JdbcReportingStore store;

    public StationLoadBackfill(DayCloseService dayClose, JdbcReportingStore store) {
        this.dayClose = dayClose;
        this.store = store;
    }

    /** Every active tenant, one after another; a tenant that fails does not stop the others. */
    public Map<UUID, Report> runAll(LocalDate cutover) {
        Map<UUID, Report> reports = new LinkedHashMap<>();
        for (UUID tenantId : store.activeTenantIds()) {
            try {
                reports.put(tenantId, run(tenantId, cutover));
            } catch (RuntimeException failure) {
                log.warn("The station-load backfill failed for tenant {}", tenantId, failure);
            }
        }
        return reports;
    }

    /** One tenant's {@value #DAYS} dates before {@code cutover}, oldest first. */
    public Report run(UUID tenantId, LocalDate cutover) {
        List<StationLoadRebuild> outcomes = new ArrayList<>();
        for (LocalDate date = cutover.minusDays(DAYS); date.isBefore(cutover); date = date.plusDays(1)) {
            outcomes.add(rebuildDate(tenantId, date, cutover));
        }
        Report report = new Report(tenantId, cutover, outcomes);
        log.info(
                "Station-load backfill for tenant {} before {}: {} rebuilt, {} refused for a changed boundary, {} never closed",
                tenantId,
                cutover,
                report.count(RebuildStatus.REBUILT),
                report.count(RebuildStatus.BOUNDARY_CHANGED),
                report.count(RebuildStatus.NOT_CLOSED));
        return report;
    }

    /**
     * One date, refused when it is on or after the cutover.
     *
     * @throws IllegalArgumentException for a date a manager may already have planned from
     */
    public StationLoadRebuild rebuildDate(UUID tenantId, LocalDate businessDate, LocalDate cutover) {
        if (!businessDate.isBefore(cutover)) {
            throw new IllegalArgumentException("The backfill never rewrites %s: it is on or after the cutover %s"
                    .formatted(businessDate, cutover));
        }
        return dayClose.rebuildStationLoad(tenantId, businessDate);
    }

    /** What a run did for one tenant; {@link #boundaryChanged()} is the list the operator reads. */
    public record Report(UUID tenantId, LocalDate cutover, List<StationLoadRebuild> dates) {

        public Report {
            dates = List.copyOf(dates);
        }

        public long count(RebuildStatus status) {
            return dates.stream().filter(rebuild -> rebuild.status() == status).count();
        }

        public List<LocalDate> boundaryChanged() {
            return dates.stream()
                    .filter(rebuild -> rebuild.status() == RebuildStatus.BOUNDARY_CHANGED)
                    .map(StationLoadRebuild::businessDate)
                    .toList();
        }
    }
}
