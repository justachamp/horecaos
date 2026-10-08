package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.ordering.api.LateOrderDirectory;

/**
 * {@link LateOrderDirectory} over {@code ordering.orders}.
 *
 * <p>An order is late when it closed more than {@code lateByMinutes} after {@code
 * promised_at}. Both columns are the order's own facts: the promise is decided once at
 * checkout and never recomputed (ADR 0036), and {@code closed_at} is written in the same
 * statement as the terminal status (see {@code JdbcOrderStore#transition}), so a completed
 * row never has a null close time to compare.
 */
@Repository
public class JdbcLateOrderDirectory implements LateOrderDirectory {

    private final JdbcClient jdbc;

    public JdbcLateOrderDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LateOrder> completedLate(
            UUID tenantId, UUID brandId, int lateByMinutes, Instant closedAfter, Instant closedBefore, int limit) {
        return jdbc.sql("""
                SELECT id, public_order_number, customer_account_id, promised_at, closed_at
                  FROM ordering.orders
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND status = 'COMPLETED'
                   AND customer_account_id IS NOT NULL
                   AND promised_at IS NOT NULL
                   AND closed_at > :closedAfter AND closed_at <= :closedBefore
                   AND closed_at > promised_at + make_interval(mins => :lateByMinutes)
                 ORDER BY closed_at, id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("lateByMinutes", lateByMinutes)
                .param("closedAfter", OffsetDateTime.ofInstant(closedAfter, ZoneOffset.UTC))
                .param("closedBefore", OffsetDateTime.ofInstant(closedBefore, ZoneOffset.UTC))
                .param("limit", limit)
                .query((row, number) -> new LateOrder(
                        tenantId,
                        brandId,
                        row.getObject("id", UUID.class),
                        row.getString("public_order_number"),
                        row.getObject("customer_account_id", UUID.class),
                        row.getObject("promised_at", OffsetDateTime.class).toInstant(),
                        row.getObject("closed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Read straight from {@code payments.order_remedies} by SQL, as {@code JdbcOrderStore}
     * already reads it for the order's own remedy summary: the fact is the order's, the table is
     * payments', and no payments type crosses the module line.
     */
    @Override
    public boolean hasRemedy(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM payments.order_remedies
                     WHERE tenant_id = :tenantId AND order_id = :orderId)
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(Boolean.class)
                .single();
    }
}
