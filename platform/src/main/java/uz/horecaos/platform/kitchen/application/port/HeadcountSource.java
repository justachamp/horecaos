package uz.horecaos.platform.kitchen.application.port;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.CapacityWindow;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.LoadCell;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Station;
import uz.horecaos.platform.kitchen.domain.PublicHolidays;

/**
 * Everything the headcount plan reads that the kitchen does not own (ADR 0163): the station-load fact the
 * day close writes, the tenant's business-day boundary and holiday calendar. The plan never reads {@code
 * kitchen.ticket_items} on a request path; the fact is the source.
 */
public interface HeadcountSource {

    boolean locationExists(UUID tenantId, UUID brandId, UUID locationId);

    BusinessDay businessDay(UUID tenantId);

    /**
     * Business dates of one weekday on which the location fired at least one ticket, newest first, within
     * {@code [from, to]}: a date with no fact rows (never closed, or refused for a changed boundary) does not
     * qualify.
     */
    List<LocalDate> candidateDates(
            UUID tenantId, UUID locationId, int weekday, LocalDate from, LocalDate to, int limit);

    Loads loads(UUID tenantId, UUID locationId, List<LocalDate> dates);

    /** The location's ACTIVE stations, whatever their role. */
    List<Station> stations(UUID tenantId, UUID locationId);

    List<CapacityWindow> windows(UUID tenantId, UUID locationId);

    PublicHolidays holidays(UUID tenantId);

    /** The tenant's business day: zone and the local time operating hour 0 begins at. */
    record BusinessDay(ZoneId zone, LocalTime start) {}

    /** Fact rows plus the metric calculation version they were written under (0 when there are none). */
    record Loads(List<LoadCell> cells, int calculationVersion) {}
}
