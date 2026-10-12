package uz.horecaos.platform.content.infrastructure.persistence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.content.domain.ContentKind;

/**
 * {@code content.item_daily_stats} (V0535): anonymous per-day counters, and the revisions they are
 * filed under.
 *
 * <p>The table holds a total per item, revision, day and channel and <strong>nothing that identifies
 * who or what reported</strong>: this class has no parameter that could carry a device, a customer, an
 * address or a network identifier, which is the property that keeps the counters outside ADR 0029.
 */
@Repository
public class JdbcContentStatsStore {

    private final JdbcClient jdbc;

    public JdbcContentStatsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An item and the revision a counter is filed under. */
    public record ItemRevision(UUID itemId, int revisionNo) {}

    /**
     * Which of these (item, revision) pairs exist for this brand, with their kind. A pair that is not
     * the brand's is simply absent, whether it belongs to another brand, another tenant or nothing.
     */
    public Map<ItemRevision, ContentKind> existing(UUID tenantId, UUID brandId, List<ItemRevision> pairs) {
        if (pairs.isEmpty()) {
            return Map.of();
        }
        UUID[] items = pairs.stream().map(ItemRevision::itemId).toArray(UUID[]::new);
        Integer[] revisions = pairs.stream().map(ItemRevision::revisionNo).toArray(Integer[]::new);
        Map<ItemRevision, ContentKind> found = new HashMap<>();
        jdbc.sql("""
                SELECT r.item_id, r.revision_no, i.kind
                FROM unnest(CAST(:items AS uuid[]), CAST(:revisions AS integer[])) AS wanted(item_id, revision_no)
                JOIN content.item_revisions r ON r.item_id = wanted.item_id AND r.revision_no = wanted.revision_no
                JOIN content.items i ON i.id = r.item_id AND i.tenant_id = r.tenant_id
                WHERE r.tenant_id = :tenantId AND r.brand_id = :brandId
                """)
                .param("items", items)
                .param("revisions", revisions)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> {
                    found.put(
                            new ItemRevision(row.getObject("item_id", UUID.class), row.getInt("revision_no")),
                            ContentKind.valueOf(row.getString("kind")));
                    return 0;
                })
                .list();
        return found;
    }

    /** Adds to a day's totals, creating the row the first time. */
    public void add(
            UUID tenantId,
            UUID brandId,
            UUID itemId,
            int revisionNo,
            LocalDate day,
            String channelCode,
            long impressions,
            long clicks,
            long dismissals,
            long completions) {
        jdbc.sql("""
                INSERT INTO content.item_daily_stats
                    (tenant_id, brand_id, item_id, revision_no, day, channel_code,
                     impressions, clicks, dismissals, completions)
                VALUES (:tenantId, :brandId, :itemId, :revisionNo, :day, :channel,
                        :impressions, :clicks, :dismissals, :completions)
                ON CONFLICT (item_id, revision_no, day, channel_code) DO UPDATE SET
                    impressions = content.item_daily_stats.impressions + EXCLUDED.impressions,
                    clicks = content.item_daily_stats.clicks + EXCLUDED.clicks,
                    dismissals = content.item_daily_stats.dismissals + EXCLUDED.dismissals,
                    completions = content.item_daily_stats.completions + EXCLUDED.completions
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemId", itemId)
                .param("revisionNo", revisionNo)
                .param("day", day)
                .param("channel", channelCode)
                .param("impressions", impressions)
                .param("clicks", clicks)
                .param("dismissals", dismissals)
                .param("completions", completions)
                .update();
    }

    /** One day's totals for one revision on one channel. */
    public record DailyRow(
            int revisionNo,
            LocalDate day,
            String channelCode,
            long impressions,
            long clicks,
            long dismissals,
            long completions) {}

    public List<DailyRow> stats(UUID tenantId, UUID brandId, UUID itemId, LocalDate from, LocalDate to) {
        return new ArrayList<>(jdbc.sql("""
                        SELECT revision_no, day, channel_code, impressions, clicks, dismissals, completions
                        FROM content.item_daily_stats
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND item_id = :itemId
                          AND day BETWEEN :from AND :to
                        ORDER BY day DESC, revision_no DESC, channel_code
                        """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("itemId", itemId)
                .param("from", from)
                .param("to", to)
                .query((row, number) -> new DailyRow(
                        row.getInt("revision_no"),
                        row.getObject("day", LocalDate.class),
                        row.getString("channel_code"),
                        row.getLong("impressions"),
                        row.getLong("clicks"),
                        row.getLong("dismissals"),
                        row.getLong("completions")))
                .list());
    }
}
