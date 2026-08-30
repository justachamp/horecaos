package uz.horecaos.platform.dinein.infrastructure.ordering;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource;

/**
 * Reads the order facts a table session is built from (ADR 0047).
 *
 * <p>Reads, and only reads, in the shape {@code JdbcKitchenOrderSource} already
 * set for ADR 0041: a module that needs a handful of order columns takes them
 * through a port and keeps no copy, rather than importing an ordering service and
 * making the two modules mutually aware.
 *
 * <p>No line, no note, no customer. A dine-in bill needs a currency and a total
 * per round; the guest's own words on a line are ADR 0029 personal data living
 * encrypted on the order, and a display resolves them through an authorized read
 * against the order rather than through a copy the dining room keeps.
 *
 * <p>The bill excludes rounds that died. A rejected, expired or cancelled round is
 * food nobody ate, and a total that included it is a bill an operator would have
 * to explain at the table.
 */
@Component
public class JdbcSessionOrderSource implements SessionOrderSource {

    /**
     * Statuses whose money still counts. Transcribed from ADR 0019's terminal
     * flags rather than inverted from them, so that an added status has to be
     * considered here rather than silently joining the bill.
     */
    private static final String BILLABLE = """
            ('RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'CONFIRMED',
             'PREPARING', 'READY', 'FULFILLING', 'COMPLETED')""";

    private final JdbcClient jdbc;

    public JdbcSessionOrderSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderForSession> find(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, tenant_id, location_id, fulfillment_mode, status, currency, total_minor
                FROM ordering.orders
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", orderId)
                .query((row, number) -> new OrderForSession(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("fulfillment_mode"),
                        row.getString("status"),
                        row.getString("currency"),
                        row.getLong("total_minor")))
                .optional();
    }

    @Override
    public SessionBill bill(UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                SELECT MAX(o.currency) AS currency,
                       COALESCE(SUM(o.total_minor) FILTER (WHERE o.status IN %s), 0) AS total_minor,
                       COUNT(*) AS round_count,
                       COUNT(*) FILTER (WHERE o.status IN
                           ('RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'CONFIRMED',
                            'PREPARING', 'READY', 'FULFILLING')) AS open_round_count
                FROM dinein.session_orders so
                JOIN ordering.orders o ON o.id = so.order_id AND o.tenant_id = so.tenant_id
                WHERE so.tenant_id = :tenantId AND so.session_id = :sessionId
                """.formatted(BILLABLE))
                .param("tenantId", tenantId)
                .param("sessionId", sessionId)
                .query((row, number) -> new SessionBill(
                        // Null exactly when the session has no rounds yet. A
                        // currency read as an empty string there would be a
                        // three-character column nobody could parse.
                        row.getString("currency"),
                        row.getLong("total_minor"),
                        row.getInt("round_count"),
                        row.getInt("open_round_count")))
                .single();
    }
}
