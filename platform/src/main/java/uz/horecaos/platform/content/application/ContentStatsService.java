package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStatsStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStatsStore.DailyRow;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStatsStore.ItemRevision;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The anonymous per-day counters a storefront reports (ADR 0157, Decision 10; Release 2).
 *
 * <p>Impressions, clicks, dismissals and story completions, reported by the client and labelled so:
 * anyone can inflate them, and they are not a metric the reporting layer (ADR 0043) consumes. The
 * platform keeps a total per item, revision, day and channel and <em>never an identifier</em> -- no
 * device, customer, address or network -- because an anonymous browser has none the server could
 * count against, and because a counter that stored one would be personal data (ADR 0029).
 *
 * <p>The day is the tenant's calendar day. A batch carries at most twenty events, which is the
 * control on what a hostile caller can do in one call; the per-IP budget at the edge is the control
 * on how many calls.
 */
@Service
public class ContentStatsService {

    /** The most events one call may carry. */
    public static final int MAX_BATCH = 20;

    /** What a storefront reports. */
    public enum CounterEvent {
        IMPRESSION,
        CLICK,
        /** A pop-up closed by the visitor. */
        DISMISSAL,
        /** A story watched to its last slide. */
        COMPLETION
    }

    /** One reported event; the revision is the one the storefront was shown. */
    public record Reported(UUID itemId, int revisionNo, CounterEvent event) {}

    /** What became of a batch. */
    public record Accepted(int accepted, int rejected) {}

    private final JdbcContentStatsStore store;
    private final ContentEnvironment environment;
    private final ContentMetrics metrics;
    private final Clock clock;

    public ContentStatsService(
            JdbcContentStatsStore store, ContentEnvironment environment, ContentMetrics metrics, Clock clock) {
        this.store = store;
        this.environment = environment;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Files a batch under the tenant's calendar day. An event that names an item or revision this
     * brand does not have, or an event that makes no sense for the kind (a dismissal of a banner), is
     * rejected and counted, and the rest of the batch is kept.
     */
    @Transactional
    public Accepted record(UUID tenantId, UUID brandId, String channelCode, List<Reported> events) {
        if (events.size() > MAX_BATCH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A call carries at most " + MAX_BATCH + " events");
        }
        if (!environment.brand().brandIsActive(tenantId, brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand or channel");
        }
        SalesChannel channel = environment
                .channel(tenantId, channelCode)
                .filter(c -> ContentEnvironment.CONTENT_SURFACES.contains(c.systemType()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand or channel"));

        List<ItemRevision> pairs = events.stream()
                .map(e -> new ItemRevision(e.itemId(), e.revisionNo()))
                .distinct()
                .toList();
        Map<ItemRevision, ContentKind> known = store.existing(tenantId, brandId, pairs);

        // Group first, so a batch of twenty impressions of one banner is one write.
        Map<ItemRevision, long[]> totals = new LinkedHashMap<>();
        int accepted = 0;
        int rejected = 0;
        for (Reported event : events) {
            ItemRevision pair = new ItemRevision(event.itemId(), event.revisionNo());
            ContentKind kind = known.get(pair);
            if (kind == null || !makesSense(kind, event.event())) {
                rejected++;
                continue;
            }
            long[] counts = totals.computeIfAbsent(pair, p -> new long[4]);
            counts[event.event().ordinal()]++;
            accepted++;
        }

        LocalDate day = LocalDate.ofInstant(clock.instant(), environment.brand().tenantZone(tenantId));
        for (Map.Entry<ItemRevision, long[]> entry : totals.entrySet()) {
            long[] counts = entry.getValue();
            store.add(
                    tenantId,
                    brandId,
                    entry.getKey().itemId(),
                    entry.getKey().revisionNo(),
                    day,
                    channel.code(),
                    counts[CounterEvent.IMPRESSION.ordinal()],
                    counts[CounterEvent.CLICK.ordinal()],
                    counts[CounterEvent.DISMISSAL.ordinal()],
                    counts[CounterEvent.COMPLETION.ordinal()]);
        }
        metrics.eventsAccepted(accepted);
        return new Accepted(accepted, rejected);
    }

    private static boolean makesSense(ContentKind kind, CounterEvent event) {
        return switch (event) {
            case IMPRESSION, CLICK -> true;
            case DISMISSAL -> kind == ContentKind.POPUP;
            case COMPLETION -> kind == ContentKind.STORY_GROUP;
        };
    }

    /** The staff view of an item's counters. Always client-reported, and the response says so. */
    @Transactional(readOnly = true)
    public List<DailyRow> stats(UUID tenantId, UUID brandId, UUID itemId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 400) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "At most 400 days at a time");
        }
        return new ArrayList<>(store.stats(tenantId, brandId, itemId, from, to));
    }

    /** Distinct revisions in a list of rows, newest first; for a screen that groups by revision. */
    public static Map<Integer, List<DailyRow>> byRevision(List<DailyRow> rows) {
        Map<Integer, List<DailyRow>> grouped = new HashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.revisionNo(), r -> new ArrayList<>())
                .add(row));
        return grouped;
    }
}
