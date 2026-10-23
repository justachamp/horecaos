package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportReveal;
import uz.horecaos.platform.customers.spi.ExportRow;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderExportStore;

/**
 * A customer's orders as she can be given them (ADR 0029): what she ordered and paid, and the name, number and address the
 * order took at checkout, opened under the producing person's purpose. Alongside them, her carts (those that became an order
 * hold the recipient and the note she gave, which can be someone else's name) and the contact or address changes made to an
 * order afterwards.
 *
 * <p>What was erased is simply not there: an order whose copy of her identity was anonymised comes back with those fields
 * empty and {@code anonymizedAt} set, which is the truth about what the platform still holds. Money is an amount in minor
 * units beside its currency.
 */
@Component
@ExportsCustomerData(
        name = "ordering",
        tables = {
            "ordering.orders",
            "ordering.order_customer_snapshots",
            "ordering.order_lines",
            "ordering.order_outcomes",
            "ordering.order_amendment_commands",
            "ordering.carts",
            "ordering.cart_lines",
            "ordering.cart_fulfillment"
        })
public class OrderingExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private static final String SNAPSHOT = "ordering.order_customer_snapshots";
    private static final String LINES = "ordering.order_lines";
    private static final String OUTCOMES = "ordering.order_outcomes";
    private static final String CART_LINES = "ordering.cart_lines";
    private static final String DESTINATION = "ordering.cart_fulfillment";

    private final JdbcOrderExportStore orders;
    private final FieldProtection protection;

    public OrderingExportParticipant(JdbcOrderExportStore orders, FieldProtection protection) {
        this.orders = orders;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        UUID tenant = subject.tenantId();
        UUID account = subject.customerAccountId();

        List<Map<String, @Nullable Object>> orderRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.orders(tenant, account, FETCH)) {
            UUID id = ExportRow.id(raw, "id");
            String currency = ExportRow.text(raw, "currency");
            orderRows.add(ExportRow.row()
                    .put("id", id)
                    .put("number", raw.get("public_order_number"))
                    .put("brandId", raw.get("brand_id"))
                    .put("locationId", raw.get("location_id"))
                    .put("channel", raw.get("channel_code_snapshot"))
                    .put("fulfillmentMode", raw.get("fulfillment_mode"))
                    .put("status", raw.get("status"))
                    .put("paymentStatus", raw.get("payment_status_projection"))
                    .money("subtotal", ExportRow.number(raw, "subtotal_minor"), currency)
                    .money("tax", ExportRow.number(raw, "tax_minor"), currency)
                    .money("discount", ExportRow.number(raw, "discount_minor"), currency)
                    .money("fee", ExportRow.number(raw, "fee_minor"), currency)
                    .money("total", ExportRow.number(raw, "total_minor"), currency)
                    .put("placedAt", raw.get("created_at"))
                    .build());
        }
        ExportTable ordersTable = ExportTable.ofNewestFirst("ordering.orders", orderRows);

        List<Map<String, @Nullable Object>> snapshotRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.orders(tenant, account, FETCH)) {
            UUID id = ExportRow.id(raw, "id");
            snapshotRows.add(ExportRow.row()
                    .put("orderId", id)
                    .put(
                            "name",
                            reveal(subject, SNAPSHOT, "display_name_encrypted", id, raw.get("display_name_encrypted")))
                    .put("contact", reveal(subject, SNAPSHOT, "contact_encrypted", id, raw.get("contact_encrypted")))
                    .put("address", reveal(subject, SNAPSHOT, "address_encrypted", id, raw.get("address_encrypted")))
                    .put(
                            "deliveryInstructions",
                            reveal(
                                    subject,
                                    SNAPSHOT,
                                    "delivery_instructions_encrypted",
                                    id,
                                    raw.get("delivery_instructions_encrypted")))
                    .put("anonymizedAt", raw.get("anonymized_at"))
                    .build());
        }

        List<Map<String, @Nullable Object>> lineRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.orderLines(tenant, account, FETCH)) {
            UUID id = ExportRow.id(raw, "id");
            lineRows.add(ExportRow.row()
                    .put("id", id)
                    .put("orderId", raw.get("order_id"))
                    .put("lineNumber", raw.get("line_number"))
                    .put("product", raw.get("product_name_snapshot"))
                    .put("variant", raw.get("variant_name_snapshot"))
                    .put("quantity", raw.get("quantity"))
                    .put("unitAmountMinor", raw.get("unit_amount_minor"))
                    .put("finalAmountMinor", raw.get("final_amount_minor"))
                    .put("taxAmountMinor", raw.get("tax_amount_minor"))
                    .put("note", reveal(subject, LINES, "note_encrypted", id, raw.get("note_encrypted")))
                    .build());
        }

        List<Map<String, @Nullable Object>> outcomeRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.orderOutcomes(tenant, account, FETCH)) {
            UUID orderId = ExportRow.id(raw, "order_id");
            outcomeRows.add(ExportRow.row()
                    .put("orderId", orderId)
                    .put("kind", raw.get("kind"))
                    .put("category", raw.get("system_category"))
                    .put("note", reveal(subject, OUTCOMES, "note_encrypted", orderId, raw.get("note_encrypted")))
                    .build());
        }

        List<Map<String, @Nullable Object>> amendmentRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.amendmentContactCommands(tenant, account, FETCH)) {
            amendmentRows.add(ExportRow.render(raw));
        }

        List<Map<String, @Nullable Object>> cartRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.carts(tenant, account, FETCH)) {
            cartRows.add(ExportRow.render(raw));
        }

        List<Map<String, @Nullable Object>> cartLineRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.cartLines(tenant, account, FETCH)) {
            UUID id = ExportRow.id(raw, "id");
            cartLineRows.add(ExportRow.row()
                    .put("id", id)
                    .put("cartId", raw.get("cart_id"))
                    .put("variantId", raw.get("variant_id"))
                    .put("quantity", raw.get("quantity"))
                    .put(
                            "note",
                            reveal(
                                    subject,
                                    CART_LINES,
                                    "customer_note_encrypted",
                                    id,
                                    raw.get("customer_note_encrypted")))
                    .put("addedAt", raw.get("created_at"))
                    .build());
        }

        List<Map<String, @Nullable Object>> destinationRows = new ArrayList<>();
        for (Map<String, Object> raw : orders.cartDestinations(tenant, account, FETCH)) {
            UUID cartId = ExportRow.id(raw, "cart_id");
            destinationRows.add(ExportRow.row()
                    .put("cartId", cartId)
                    .put(
                            "address",
                            reveal(subject, DESTINATION, "address_encrypted", cartId, raw.get("address_encrypted")))
                    .put(
                            "deliveryInstructions",
                            reveal(
                                    subject,
                                    DESTINATION,
                                    "delivery_instructions_encrypted",
                                    cartId,
                                    raw.get("delivery_instructions_encrypted")))
                    .put(
                            "recipientName",
                            reveal(
                                    subject,
                                    DESTINATION,
                                    "recipient_name_encrypted",
                                    cartId,
                                    raw.get("recipient_name_encrypted")))
                    .put(
                            "recipientPhone",
                            reveal(
                                    subject,
                                    DESTINATION,
                                    "recipient_phone_encrypted",
                                    cartId,
                                    raw.get("recipient_phone_encrypted")))
                    .put("latitude", raw.get("latitude"))
                    .put("longitude", raw.get("longitude"))
                    .build());
        }

        return ExportSection.of(
                ordersTable,
                ExportTable.ofNewestFirst(SNAPSHOT, snapshotRows),
                ExportTable.ofNewestFirst(LINES, lineRows),
                ExportTable.ofNewestFirst(OUTCOMES, outcomeRows),
                ExportTable.ofNewestFirst("ordering.order_amendment_commands", amendmentRows),
                ExportTable.ofNewestFirst("ordering.carts", cartRows),
                ExportTable.ofNewestFirst(CART_LINES, cartLineRows),
                ExportTable.ofNewestFirst(DESTINATION, destinationRows));
    }

    private @Nullable String reveal(
            ExportSubject subject, String table, String column, UUID rowId, @Nullable Object stored) {
        return ExportReveal.text(protection, subject, table, column, rowId, stored);
    }
}
