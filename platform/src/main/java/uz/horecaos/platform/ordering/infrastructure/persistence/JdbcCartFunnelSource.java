package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.CartFunnelSource;
import uz.horecaos.platform.ordering.application.CartRetentionSweeper;

/**
 * {@link CartFunnelSource} over the live cart tables (ADR 0167, row 7.6c).
 *
 * <p>Two reads, both grouped to a brand and a channel code and nothing finer. The basket read
 * takes a cart's first line as the moment it became a basket, so a cart opened and left empty is
 * not one and a cart edited all week is counted on the day it began. The checkout read counts a
 * cart once however many attempts it made, because a retried checkout is one customer reaching the
 * same point twice.
 *
 * <p>A cart deleted by {@link CartRetentionSweeper} is gone from both reads; {@link #retainedFrom}
 * is how the close job knows which days that applies to.
 */
@Service
public class JdbcCartFunnelSource implements CartFunnelSource {

    private final JdbcClient jdbc;
    private final CartRetentionSweeper retention;
    private final Clock clock;

    public JdbcCartFunnelSource(JdbcClient jdbc, CartRetentionSweeper retention, Clock clock) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Instant retainedFrom(UUID tenantId) {
        return clock.instant()
                .minus(Duration.ofDays(retention.windowFor(tenantId).effective()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartDayCount> countBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive) {
        OffsetDateTime from = OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC);

        Map<Key, int[]> counts = new LinkedHashMap<>();

        jdbc.sql("""
                SELECT c.brand_id, ch.code AS channel_code, count(*) AS baskets
                  FROM (SELECT l.cart_id, min(l.created_at) AS first_line_at
                          FROM ordering.cart_lines l
                         WHERE l.tenant_id = :tenantId
                           AND l.cart_id IN (SELECT w.cart_id
                                               FROM ordering.cart_lines w
                                              WHERE w.tenant_id = :tenantId
                                                AND w.created_at >= :from AND w.created_at < :to)
                         GROUP BY l.cart_id) first_lines
                  JOIN ordering.carts c
                    ON c.id = first_lines.cart_id AND c.tenant_id = :tenantId
                  JOIN tenant.sales_channels ch
                    ON ch.tenant_id = c.tenant_id AND ch.id = c.channel_id
                 WHERE first_lines.first_line_at >= :from AND first_lines.first_line_at < :to
                 GROUP BY c.brand_id, ch.code
                """)
                .param("tenantId", tenantId)
                .param("from", from)
                .param("to", to)
                .query((row, number) -> new Counted(
                        new Key(row.getObject("brand_id", UUID.class), row.getString("channel_code")),
                        row.getInt("baskets")))
                .list()
                .forEach(found -> counts.computeIfAbsent(found.key(), key -> new int[2])[0] = found.count());

        jdbc.sql("""
                SELECT c.brand_id, ch.code AS channel_code, count(DISTINCT a.cart_id) AS checkouts
                  FROM ordering.checkout_attempts a
                  JOIN ordering.carts c
                    ON c.id = a.cart_id AND c.tenant_id = a.tenant_id
                  JOIN tenant.sales_channels ch
                    ON ch.tenant_id = c.tenant_id AND ch.id = c.channel_id
                 WHERE a.tenant_id = :tenantId
                   AND a.created_at >= :from AND a.created_at < :to
                 GROUP BY c.brand_id, ch.code
                """)
                .param("tenantId", tenantId)
                .param("from", from)
                .param("to", to)
                .query((row, number) -> new Counted(
                        new Key(row.getObject("brand_id", UUID.class), row.getString("channel_code")),
                        row.getInt("checkouts")))
                .list()
                .forEach(found -> counts.computeIfAbsent(found.key(), key -> new int[2])[1] = found.count());

        return counts.entrySet().stream()
                .map(entry -> new CartDayCount(
                        entry.getKey().brandId(),
                        entry.getKey().channelCode(),
                        entry.getValue()[0],
                        entry.getValue()[1]))
                .toList();
    }

    private record Key(UUID brandId, String channelCode) {}

    private record Counted(Key key, int count) {}
}
