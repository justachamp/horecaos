package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderErasureStore;

/**
 * Takes the copies of a customer's identity out of her orders and carts (ADR 0029: "Order snapshots are anonymized
 * rather than deleted").
 *
 * <p>{@code CustomerErasureService} overwrites only the {@code customer} schema's own columns, so before this
 * participant existed an order's recipient phone and address stayed decryptable after the account was gone. An order
 * copies the customer at checkout because "a customer who later edits their address has not changed where last Tuesday's
 * order went" (V0022), and that copy is exactly what a customer who asks to be forgotten is asking about.
 *
 * <p>What goes: the order's name, contact, address and delivery note (blanked, with {@code anonymized_at} set, the
 * mechanism V0022 built for it); the note on each line and on each outcome; the contact and address an amendment typed
 * in; the destination and the notes on the carts orders came from; and any cart still open. What stays: the order, its
 * lines, prices, tax, tender, settlement and fiscal documents, because "financial history survives and the person does
 * not". A guest's order, with no account, is not reachable from an account and is not touched.
 */
@Component
@ErasesCustomerData(
        name = "ordering",
        tables = {
            "ordering.order_customer_snapshots",
            "ordering.order_lines",
            "ordering.order_outcomes",
            "ordering.order_amendment_commands",
            "ordering.carts",
            "ordering.cart_lines",
            "ordering.cart_fulfillment"
        })
public class OrderingErasureParticipant implements CustomerErasureParticipant {

    private static final String TOMBSTONE = "ERASED";
    private static final String DESTINATION_TABLE = "ordering.cart_fulfillment";

    private final JdbcOrderErasureStore orders;
    private final FieldProtection protection;
    private final Clock clock;

    public OrderingErasureParticipant(JdbcOrderErasureStore orders, FieldProtection protection, Clock clock) {
        this.orders = orders;
        this.protection = protection;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        var now = clock.instant();
        ErasureCounts.Builder counts = ErasureCounts.builder()
                .add("ordering.order_customer_snapshots", orders.anonymizeSnapshots(tenantId, customerAccountId, now))
                .add("ordering.order_lines", orders.eraseLineNotes(tenantId, customerAccountId))
                .add("ordering.order_outcomes", orders.eraseOutcomeNotes(tenantId, customerAccountId))
                .add(
                        "ordering.order_amendment_commands",
                        orders.eraseAmendmentContactPayloads(tenantId, customerAccountId))
                .add("ordering.carts", orders.deleteOpenCarts(tenantId, customerAccountId))
                .add("ordering.cart_lines", orders.eraseCartLineNotes(tenantId, customerAccountId));
        int destinations = 0;
        for (UUID cartId : orders.cartsWithDestination(tenantId, customerAccountId)) {
            destinations += orders.eraseDestination(
                    tenantId,
                    cartId,
                    tombstone(tenantId, cartId, "address_encrypted"),
                    tombstone(tenantId, cartId, "delivery_instructions_encrypted"),
                    tombstone(tenantId, cartId, "recipient_name_encrypted"),
                    tombstone(tenantId, cartId, "recipient_phone_encrypted"),
                    now);
        }
        return counts.add(DESTINATION_TABLE, destinations).build();
    }

    private String tombstone(UUID tenantId, UUID cartId, String column) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(DESTINATION_TABLE, column, cartId), TOMBSTONE)
                .serialize();
    }
}
