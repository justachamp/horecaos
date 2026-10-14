package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The statements that take a customer's identity out of the order records that copied it (ADR 0029, ADR 0019).
 *
 * <p>An order is a historical fact and these methods never touch what makes it one: totals, tax, lines, prices, tender and
 * settlement stay exactly as they were. What they reach is the words and places a person gave: the name, contact and
 * address snapshot, the note on a line, the note on an outcome, the recipient on a cart, and the contact and address an
 * amendment typed in. Every statement is bounded by the order's {@code customer_account_id}, so a guest's order (no
 * account) is not reachable from here, and by the tenant.
 */
@Repository
public class JdbcOrderErasureStore {

    /** What a payload becomes once its words are erased; the V0681 trigger accepts nothing else. */
    static final String ERASED_PAYLOAD = "{\"erased\": true}";

    private final JdbcClient jdbc;

    public JdbcOrderErasureStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Blanks the customer snapshot of every order the account placed: name, contact, address and the delivery note.
     * The row stays and {@code anonymized_at} says when (V0022: "the row stays so the order still knows it once had a
     * customer"); every reader already treats a set {@code anonymized_at} as "no one to show".
     */
    public int anonymizeSnapshots(UUID tenantId, UUID customerAccountId, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_customer_snapshots s
                   SET display_name_encrypted = NULL, contact_encrypted = NULL, address_encrypted = NULL,
                       delivery_instructions_encrypted = NULL, anonymized_at = :now
                  FROM ordering.orders o
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                   AND s.tenant_id = :tenantId AND s.order_id = o.id AND s.anonymized_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .param("now", at(now))
                .update();
    }

    /** Blanks the note on every line of the account's orders: what the customer asked the kitchen for. */
    public int eraseLineNotes(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                UPDATE ordering.order_lines l
                   SET note_encrypted = NULL
                  FROM ordering.orders o
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                   AND l.tenant_id = :tenantId AND l.order_id = o.id AND l.note_encrypted IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
    }

    /** Blanks the staff note on the outcome (cancellation, rejection) of the account's orders. */
    public int eraseOutcomeNotes(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                UPDATE ordering.order_outcomes oc
                   SET note_encrypted = NULL
                  FROM ordering.orders o
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                   AND oc.tenant_id = :tenantId AND oc.order_id = o.id AND oc.note_encrypted IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
    }

    /**
     * Replaces the payload of the contact and delivery-address amendments of the account's orders with the erasure
     * marker. Those commands carried the new recipient name, phone and address in the clear (V0029's
     * {@code payload_json}); the amendment, its sequence, its type and the price consequence stay.
     */
    public int eraseAmendmentContactPayloads(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                UPDATE ordering.order_amendment_commands c
                   SET payload_json = CAST(:erased AS jsonb)
                  FROM ordering.order_amendments a
                  JOIN ordering.orders o ON o.tenant_id = a.tenant_id AND o.id = a.order_id
                 WHERE o.tenant_id = :tenantId AND o.customer_account_id = :accountId
                   AND c.tenant_id = :tenantId AND c.amendment_id = a.id
                   AND c.command_type IN ('CHANGE_CONTACT', 'CHANGE_DELIVERY_ADDRESS')
                   AND c.payload_json IS DISTINCT FROM CAST(:erased AS jsonb)
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .param("erased", ERASED_PAYLOAD)
                .update();
    }

    /**
     * Deletes the carts the account still has open: never became an order, so nothing needs them. Lines and
     * destination go with them through the cascades V0022 and V0056 declare. A cart an order names is kept (it is
     * that order's history) and is handled by {@link #eraseOrderedCarts}.
     */
    public int deleteOpenCarts(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                DELETE FROM ordering.carts c
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                   AND c.status <> 'CONVERTED'
                   AND NOT EXISTS (
                       SELECT 1 FROM ordering.orders o WHERE o.tenant_id = c.tenant_id AND o.cart_id = c.id)
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
    }

    /** Blanks the customer's note on every line of the carts that remain (the ones orders came from). */
    public int eraseCartLineNotes(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                UPDATE ordering.cart_lines l
                   SET customer_note_encrypted = NULL, version = l.version + 1, updated_at = now()
                  FROM ordering.carts c
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                   AND l.tenant_id = :tenantId AND l.cart_id = c.id AND l.customer_note_encrypted IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
    }

    /** The carts of the account that still hold a destination (the ones orders came from). */
    public List<UUID> cartsWithDestination(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT f.cart_id
                  FROM ordering.cart_fulfillment f
                  JOIN ordering.carts c ON c.tenant_id = f.tenant_id AND c.id = f.cart_id
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                 ORDER BY f.cart_id
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .query(UUID.class)
                .list();
    }

    /**
     * Overwrites one cart's destination. The address is NOT NULL (V0056), so it takes a tombstone ciphertext; the
     * recipient and the delivery note are tombstoned where they were set and left null where they were not. The
     * coordinate is in the clear and NOT NULL, and identifies a building, so it is reset to the origin: the cart
     * is a converted one and nothing routes from it.
     */
    public int eraseDestination(
            UUID tenantId,
            UUID cartId,
            String addressTombstone,
            String instructionsTombstone,
            String recipientNameTombstone,
            String recipientPhoneTombstone,
            Instant now) {
        return jdbc.sql("""
                UPDATE ordering.cart_fulfillment
                   SET address_encrypted = :address,
                       delivery_instructions_encrypted =
                           CASE WHEN delivery_instructions_encrypted IS NULL THEN NULL ELSE :instructions END,
                       recipient_name_encrypted =
                           CASE WHEN recipient_name_encrypted IS NULL THEN NULL ELSE :recipientName END,
                       recipient_phone_encrypted =
                           CASE WHEN recipient_phone_encrypted IS NULL THEN NULL ELSE :recipientPhone END,
                       customer_address_id = NULL, latitude = 0, longitude = 0, updated_at = :now
                 WHERE tenant_id = :tenantId AND cart_id = :cartId
                """)
                .param("address", addressTombstone)
                .param("instructions", instructionsTombstone)
                .param("recipientName", recipientNameTombstone)
                .param("recipientPhone", recipientPhoneTombstone)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("cartId", cartId)
                .update();
    }

    /** How many orders of the account are still on their way: not yet completed, cancelled, rejected or expired. */
    public int openOrders(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT count(*) FROM ordering.orders
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                   AND status IN ('RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'CONFIRMED',
                                  'PREPARING', 'READY', 'FULFILLING')
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .query(Integer.class)
                .single();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
