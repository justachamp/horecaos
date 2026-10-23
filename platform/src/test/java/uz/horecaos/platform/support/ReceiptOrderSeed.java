package uz.horecaos.platform.support;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows a fiscal receipt is built from, written the way production writes them (ADR 0166).
 *
 * <p>A fixture that does in setup what production never does proves nothing (platform/CLAUDE.md), so
 * this writes only what the platform itself writes: a catalog node and its classification row, an order
 * line with the amounts the quote gave it, the adjustment that itemises an option's charge, and the
 * tax rule the quote recorded in its calculation document. It does not compute a receipt line.
 *
 * <p>Every method takes the {@link JdbcClient} of the test's own database and the tenant and brand it
 * seeds under; none assumes the rows it needs from a sibling method exist, beyond the foreign keys the
 * schema itself enforces.
 */
public final class ReceiptOrderSeed {

    private ReceiptOrderSeed() {}

    /** A product and its default variant; returns the variant, the node an order line names. */
    public static UUID variant(JdbcClient jdbc, UUID tenantId, UUID brandId, String code) {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :t, :b, :code)")
                .param("id", product)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .update();
        jdbc.sql("INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id) VALUES (:id, :t, :b, :p)")
                .param("id", variant)
                .param("t", tenantId)
                .param("b", brandId)
                .param("p", product)
                .update();
        return variant;
    }

    /** A modifier option in a group of its own; returns the option. */
    public static UUID modifierOption(JdbcClient jdbc, UUID tenantId, UUID brandId, String code) {
        UUID group = UUID.randomUUID();
        UUID option = UUID.randomUUID();
        jdbc.sql("INSERT INTO catalog.modifier_groups (id, tenant_id, brand_id, code, status)"
                        + " VALUES (:id, :t, :b, :code, 'ACTIVE')")
                .param("id", group)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", "G-" + code)
                .update();
        jdbc.sql("INSERT INTO catalog.modifier_options (id, tenant_id, brand_id, modifier_group_id, code, status)"
                        + " VALUES (:id, :t, :b, :g, :code, 'ACTIVE')")
                .param("id", option)
                .param("t", tenantId)
                .param("b", brandId)
                .param("g", group)
                .param("code", code)
                .update();
        return option;
    }

    /** The brand's delivery fee node, created the way the catalog creates it on demand. */
    public static UUID deliveryFee(JdbcClient jdbc, UUID tenantId, UUID brandId) {
        jdbc.sql("INSERT INTO catalog.fees (id, tenant_id, brand_id, code) VALUES (:id, :t, :b, 'DELIVERY')"
                        + " ON CONFLICT (tenant_id, brand_id, code) DO NOTHING")
                .param("id", UUID.randomUUID())
                .param("t", tenantId)
                .param("b", brandId)
                .update();
        return jdbc.sql("SELECT id FROM catalog.fees WHERE tenant_id = :t AND brand_id = :b AND code = 'DELIVERY'")
                .param("t", tenantId)
                .param("b", brandId)
                .query(UUID.class)
                .single();
    }

    /** Which kind of node a classification names. */
    public enum Node {
        VARIANT("variant_id"),
        MODIFIER_OPTION("modifier_option_id"),
        FEE("fee_id");

        final String column;

        Node(String column) {
            this.column = column;
        }
    }

    /** A complete classification (ИКПУ, package code, unit code, fiscal name) for a node. */
    public static void classify(
            JdbcClient jdbc,
            UUID tenantId,
            UUID brandId,
            Node kind,
            UUID nodeId,
            String mxik,
            String packageCode,
            int unitCode,
            String fiscalName) {
        jdbc.sql(("INSERT INTO catalog.fiscal_classifications"
                                + " (id, tenant_id, brand_id, %s, mxik_code, package_code, fiscal_unit_code, fiscal_name, source)"
                                + " VALUES (:id, :t, :b, :node, :mxik, :pkg, :unit, :name, 'MANUAL')")
                        .formatted(kind.column))
                .param("id", UUID.randomUUID())
                .param("t", tenantId)
                .param("b", brandId)
                .param("node", nodeId)
                .param("mxik", mxik)
                .param("pkg", packageCode)
                .param("unit", unitCode)
                .param("name", fiscalName)
                .update();
    }

    /**
     * A live order line with the amounts the quote gave it.
     *
     * @return the line's id
     */
    public static UUID orderLine(
            JdbcClient jdbc,
            UUID tenantId,
            UUID orderId,
            int lineNumber,
            UUID variantId,
            String name,
            BigDecimal quantity,
            long baseMinor,
            long finalMinor,
            long taxMinor) {
        UUID id = UUID.randomUUID();
        // A live line belongs to a revision of its order (fk_order_line_revision): the checkout's own,
        // copied from the order exactly as checkout writes it.
        jdbc.sql("""
                INSERT INTO ordering.order_revisions (order_id, revision, tenant_id, source, pricing_quote_id,
                    pricing_context_hash, currency, subtotal_minor, tax_minor, discount_minor, fee_minor,
                    total_minor)
                SELECT id, 1, tenant_id, 'CHECKOUT', pricing_quote_id, pricing_context_hash, currency,
                       subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor
                  FROM ordering.orders WHERE tenant_id = :t AND id = :o
                ON CONFLICT DO NOTHING
                """).param("t", tenantId).param("o", orderId).update();
        jdbc.sql("""
                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number, source_variant_id,
                    product_name_snapshot, quantity, unit_amount_minor, base_amount_minor, final_amount_minor,
                    tax_amount_minor)
                VALUES (:id, :t, :o, :n, :variant, :name, :quantity, :unit, :base, :final, :tax)
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("o", orderId)
                .param("n", lineNumber)
                .param("variant", variantId)
                .param("name", name)
                .param("quantity", quantity)
                .param("unit", baseMinor)
                .param("base", baseMinor)
                .param("final", finalMinor)
                .param("tax", taxMinor)
                .update();
        return id;
    }

    /** The adjustment that itemises what a modifier option the customer chose charged a line. */
    public static void optionCharge(
            JdbcClient jdbc, UUID tenantId, UUID orderId, int sequence, UUID lineId, UUID optionId, long amountMinor) {
        jdbc.sql("""
                INSERT INTO ordering.order_adjustments (order_id, sequence, tenant_id, order_line_id,
                    adjustment_type, source_type, source_id, description_code, amount_minor)
                VALUES (:o, :seq, :t, :line, 'MODIFIER', 'MODIFIER_OPTION', :option, 'MODIFIERS', :amount)
                """)
                .param("o", orderId)
                .param("seq", sequence)
                .param("t", tenantId)
                .param("line", lineId)
                .param("option", optionId)
                .param("amount", amountMinor)
                .update();
    }

    /** Any adjustment of an order, for a case the other helpers do not state (a hidden option, a legacy step). */
    public static void adjustment(
            JdbcClient jdbc,
            UUID tenantId,
            UUID orderId,
            int sequence,
            UUID lineId,
            String type,
            String sourceType,
            UUID sourceId,
            long amountMinor) {
        jdbc.sql("""
                INSERT INTO ordering.order_adjustments (order_id, sequence, tenant_id, order_line_id,
                    adjustment_type, source_type, source_id, description_code, amount_minor)
                VALUES (:o, :seq, :t, :line, :type, :sourceType, :source, 'SEEDED', :amount)
                """)
                .param("o", orderId)
                .param("seq", sequence)
                .param("t", tenantId)
                .param("line", lineId)
                .param("type", type)
                .param("sourceType", sourceType)
                .param("source", sourceId)
                .param("amount", amountMinor)
                .update();
    }

    /** The tax rule a quote recorded in its calculation document: what the receipt's VAT rate is read from. */
    public static void quoteTax(JdbcClient jdbc, UUID tenantId, UUID quoteId, int rateBasisPoints, String mode) {
        jdbc.sql("""
                UPDATE pricing.quotes
                   SET calculation_document = jsonb_build_object(
                           'taxProfileId', CAST(:profile AS text),
                           'taxRateBasisPoints', CAST(:rate AS integer),
                           'taxMode', CAST(:mode AS text))
                 WHERE tenant_id = :t AND id = :q
                """)
                .param("profile", UUID.randomUUID().toString())
                .param("rate", rateBasisPoints)
                .param("mode", mode)
                .param("t", tenantId)
                .param("q", quoteId)
                .update();
    }
}
