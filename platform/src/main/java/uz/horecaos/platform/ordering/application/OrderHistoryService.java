package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.api.CustomerOrderHistoryPort;

/**
 * How many orders a customer has already placed, for the Nth-order and
 * first-order promotion conditions (ADR 0140).
 *
 * <p>The count is exact, not a projection: it reads {@code ordering.orders} in the
 * caller's own transaction and does not touch {@code marketing.customer_metrics},
 * which is eventually consistent, so two quick orders cannot both be "first". An
 * order that was {@code CANCELLED} or {@code REJECTED} never happened as far as
 * "first" is concerned. The order being amended is excluded by id, and only
 * orders placed at or before the order's own instant count, so an amended first
 * order is never its own successor. "At or before" rather than "before": an order
 * placed at exactly the same instant is a predecessor, and the safe direction for a
 * first-order promotion is never to grant "first" twice.
 */
@Service
public class OrderHistoryService implements CustomerOrderHistoryPort {

    private final JdbcClient jdbc;

    public OrderHistoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public int countPriorOrders(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            Basis basis,
            @Nullable UUID channelId,
            Instant placedBefore,
            @Nullable UUID excludingOrderId) {
        if (basis == Basis.CHANNEL && channelId == null) {
            throw new IllegalArgumentException("A per-channel count needs the channel");
        }
        return jdbc.sql("""
                SELECT count(*)
                FROM ordering.orders
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :accountId
                  AND status NOT IN ('CANCELLED', 'REJECTED')
                  AND created_at <= :placedBefore
                  AND (CAST(:excluding AS uuid) IS NULL OR id <> CAST(:excluding AS uuid))
                  AND (:perChannel = false OR channel_id = CAST(:channelId AS uuid))
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .param("placedBefore", java.time.OffsetDateTime.ofInstant(placedBefore, java.time.ZoneOffset.UTC))
                .param("excluding", excludingOrderId)
                .param("perChannel", basis == Basis.CHANNEL)
                .param("channelId", channelId)
                .query(Integer.class)
                .single();
    }
}
