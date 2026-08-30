package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.reporting.domain.Grain;

/**
 * A typed request for numbers (ADR 0043).
 *
 * <p>Metric ids, dimensions, filters, and a date range. Never SQL and never a
 * fragment of one: the moment a client can send an expression, the metric
 * registry becomes decoration and the disagreement this ADR exists to prevent
 * returns through the front door.
 *
 * @param metricCodes  the metrics to return, as {@code revenue.gross.v1}
 * @param groupBy      the axes to break the answer down by. A metric may be
 *                     rolled up from its own grain but never claim a finer one
 * @param locationIds  empty means every location the caller may read
 * @param channelCodes empty means every channel
 */
public record ReportQuery(
        UUID tenantId,
        LocalDate from,
        LocalDate to,
        List<String> metricCodes,
        List<Grain.Dimension> groupBy,
        List<UUID> locationIds,
        List<String> channelCodes) {

    /** The widest range one request may cover, so a typo cannot scan every partition. */
    public static final int MAX_DAYS = 400;

    public ReportQuery {
        Objects.requireNonNull(tenantId, "A reporting query is tenant-scoped");
        Objects.requireNonNull(from, "A reporting query needs a start date");
        Objects.requireNonNull(to, "A reporting query needs an end date");

        metricCodes = List.copyOf(metricCodes);
        groupBy = List.copyOf(groupBy);
        locationIds = List.copyOf(locationIds);
        channelCodes = List.copyOf(channelCodes);

        if (metricCodes.isEmpty()) {
            throw new IllegalArgumentException("Name at least one metric");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The range ends before it starts");
        }
        // 400 days is the behavioural retention ADR 0043 proposes — a
        // year-over-year comparison plus a month of margin — and the same figure
        // bounds a query, so no request can be wider than the history that exists.
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("A range may cover at most " + MAX_DAYS + " days");
        }
    }

    public boolean groupsByLegalEntity() {
        return groupBy.contains(Grain.Dimension.LEGAL_ENTITY);
    }
}
