package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a data-subject export reads of a customer's orders and carts (ADR 0029). Every statement fetches at most
 * {@code limit} rows, newest first, so the caller can tell that a table was cut off. Encrypted columns come back as they
 * are stored: the participant opens them, under the producing person's purpose.
 */
@Repository
public class JdbcOrderExportStore {

    private final JdbcClient jdbc;

    public JdbcOrderExportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The account's orders with the copy of her identity each one took at checkout. */
    public List<Map<String, Object>> orders(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT o.id, o.public_order_number, o.brand_id, o.location_id, o.channel_code_snapshot,
                       o.fulfillment_mode, o.status, o.payment_status_projection, o.currency,
                       o.subtotal_minor, o.tax_minor, o.discount_minor, o.fee_minor, o.total_minor, o.created_at,
                       s.display_name_encrypted, s.contact_encrypted, s.address_encrypted,
                       s.delivery_instructions_encrypted, s.anonymized_at
                  FROM ordering.orders o
                  LEFT JOIN ordering.order_customer_snapshots s ON s.tenant_id = o.tenant_id AND s.order_id = o.id
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                 ORDER BY o.created_at DESC, o.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> orderLines(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT l.id, l.order_id, l.line_number, l.product_name_snapshot, l.variant_name_snapshot,
                       l.quantity, l.unit_amount_minor, l.final_amount_minor, l.tax_amount_minor, l.note_encrypted
                  FROM ordering.order_lines l
                  JOIN ordering.orders o ON o.tenant_id = l.tenant_id AND o.id = l.order_id
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                 ORDER BY o.created_at DESC, o.id DESC, l.line_number
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> orderOutcomes(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT oc.order_id, oc.kind, oc.system_category, oc.note_encrypted
                  FROM ordering.order_outcomes oc
                  JOIN ordering.orders o ON o.tenant_id = oc.tenant_id AND o.id = oc.order_id
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                 ORDER BY o.created_at DESC, o.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    /** The contact and address changes made to her orders, with what was typed (plain JSON until an erasure blanks it). */
    public List<Map<String, Object>> amendmentContactCommands(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT a.order_id, c.amendment_id, c.sequence, c.command_type, c.payload_json::text AS payload
                  FROM ordering.order_amendment_commands c
                  JOIN ordering.order_amendments a ON a.tenant_id = c.tenant_id AND a.id = c.amendment_id
                  JOIN ordering.orders o ON o.tenant_id = a.tenant_id AND o.id = a.order_id
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                   AND c.command_type IN ('CHANGE_CONTACT', 'CHANGE_DELIVERY_ADDRESS')
                 ORDER BY o.created_at DESC, c.amendment_id, c.sequence
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    /** Her carts, those she checked out and those she did not: each holds what she chose and where she asked it to go. */
    public List<Map<String, Object>> carts(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.brand_id, c.location_id, c.fulfillment_mode, c.currency, c.status, c.created_at
                  FROM ordering.carts c
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                 ORDER BY c.created_at DESC, c.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> cartLines(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT l.id, l.cart_id, l.variant_id, l.quantity, l.customer_note_encrypted, l.created_at
                  FROM ordering.cart_lines l
                  JOIN ordering.carts c ON c.tenant_id = l.tenant_id AND c.id = l.cart_id
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                 ORDER BY c.created_at DESC, c.id DESC, l.created_at
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> cartDestinations(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT f.cart_id, f.address_encrypted, f.delivery_instructions_encrypted,
                       f.recipient_name_encrypted, f.recipient_phone_encrypted, f.latitude, f.longitude
                  FROM ordering.cart_fulfillment f
                  JOIN ordering.carts c ON c.tenant_id = f.tenant_id AND c.id = f.cart_id
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                 ORDER BY c.created_at DESC, c.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }
}
