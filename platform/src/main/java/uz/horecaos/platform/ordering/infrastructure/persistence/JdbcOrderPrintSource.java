package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderPrintSource;

/**
 * {@link OrderPrintSource} over the order tables (ADR 0154).
 *
 * <p>Reads, and only reads. Every statement names the tenant, and none selects a customer's name, phone
 * or address: {@code ordering.order_customer_snapshots} is not joined here, so there is nothing for a
 * paper document to leak. The one decrypted value is a line's free-text note, revealed under the stated
 * purpose.
 */
@Component
class JdbcOrderPrintSource implements OrderPrintSource {

    private static final String ORDER_LINE_TABLE = "ordering.order_lines";
    private static final String NOTE_COLUMN = "note_encrypted";

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    JdbcOrderPrintSource(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrintableOrder> find(UUID tenantId, UUID orderId, @Nullable String revealPurpose) {
        Optional<Header> header = jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, public_order_number, status, fulfillment_mode,
                       channel_code_snapshot, created_at, confirmed_at, version, currency,
                       subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                       cash_tendered_expected_minor, kitchen_note
                  FROM ordering.orders
                 WHERE tenant_id = :tenantId AND id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new Header(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getString("status"),
                        row.getString("fulfillment_mode"),
                        row.getString("channel_code_snapshot"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(row.getObject("confirmed_at", OffsetDateTime.class)),
                        row.getInt("version"),
                        row.getString("currency"),
                        row.getLong("subtotal_minor"),
                        row.getLong("tax_minor"),
                        row.getLong("discount_minor"),
                        row.getLong("fee_minor"),
                        row.getLong("total_minor"),
                        row.getObject("cash_tendered_expected_minor", Long.class),
                        row.getString("kitchen_note")))
                .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Header found = header.get();
        Long tendered = found.cashTendered();
        return Optional.of(new PrintableOrder(
                found.id(),
                found.tenantId(),
                found.brandId(),
                found.locationId(),
                found.publicOrderNumber(),
                found.status(),
                found.fulfillmentMode(),
                found.channelCode(),
                found.placedAt(),
                found.confirmedAt(),
                found.version(),
                found.currency(),
                found.subtotal(),
                found.tax(),
                found.discount(),
                found.fee(),
                found.total(),
                tendered,
                // The same arithmetic, in the same place the order detail does it: tendered minus total.
                tendered == null ? null : tendered - found.total(),
                found.kitchenNote(),
                lines(tenantId, orderId, revealPurpose)));
    }

    private List<Line> lines(UUID tenantId, UUID orderId, @Nullable String revealPurpose) {
        Map<UUID, List<Modifier>> modifiers = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.order_line_id, m.id, m.parent_order_line_modifier_id, m.group_name_snapshot,
                       m.option_name_snapshot, m.quantity, m.final_amount_minor
                  FROM ordering.order_line_modifiers m
                  JOIN ordering.order_lines l ON l.id = m.order_line_id AND l.tenant_id = m.tenant_id
                 WHERE m.tenant_id = :tenantId AND l.order_id = :orderId AND l.revision_to IS NULL
                 ORDER BY l.line_number, (m.parent_order_line_modifier_id IS NOT NULL), m.id
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> Map.entry(
                        row.getObject("order_line_id", UUID.class),
                        new Modifier(
                                row.getObject("id", UUID.class),
                                row.getObject("parent_order_line_modifier_id", UUID.class),
                                row.getString("group_name_snapshot"),
                                row.getString("option_name_snapshot"),
                                row.getInt("quantity"),
                                row.getLong("final_amount_minor"))))
                .list()
                .forEach(entry -> modifiers
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));

        Map<UUID, List<Preset>> presets = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT p.order_line_id, p.code_snapshot, p.label_ru_snapshot, p.label_uz_snapshot, p.label_en_snapshot
                  FROM ordering.order_line_comment_presets p
                  JOIN ordering.order_lines l ON l.id = p.order_line_id AND l.tenant_id = p.tenant_id
                 WHERE p.tenant_id = :tenantId AND l.order_id = :orderId AND l.revision_to IS NULL
                 ORDER BY l.line_number, p.sort_order
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> Map.entry(
                        row.getObject("order_line_id", UUID.class),
                        new Preset(
                                row.getString("code_snapshot"),
                                row.getString("label_ru_snapshot"),
                                row.getString("label_uz_snapshot"),
                                row.getString("label_en_snapshot"))))
                .list()
                .forEach(entry -> presets.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));

        return jdbc.sql("""
                SELECT id, line_number, product_name_snapshot, variant_name_snapshot, quantity,
                       unit_amount_minor, final_amount_minor, note_encrypted,
                       combo_selection_id, combo_name_snapshot, combo_quantity
                  FROM ordering.order_lines
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                   -- An amendment closes a line and appends its replacement (ADR 0039): a document is
                   -- made of what the order holds now, not also of what it once held.
                   AND revision_to IS NULL
                 ORDER BY line_number
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> {
                    UUID lineId = row.getObject("id", UUID.class);
                    return new Line(
                            lineId,
                            row.getInt("line_number"),
                            row.getString("product_name_snapshot"),
                            row.getString("variant_name_snapshot"),
                            row.getBigDecimal("quantity"),
                            row.getLong("unit_amount_minor"),
                            row.getLong("final_amount_minor"),
                            note(tenantId, lineId, row.getString("note_encrypted"), revealPurpose),
                            modifiers.getOrDefault(lineId, List.of()),
                            presets.getOrDefault(lineId, List.of()),
                            row.getObject("combo_selection_id", UUID.class),
                            row.getString("combo_name_snapshot"),
                            row.getObject("combo_quantity", Integer.class));
                })
                .list();
    }

    private @Nullable String note(UUID tenantId, UUID lineId, @Nullable String stored, @Nullable String purpose) {
        if (stored == null || stored.isBlank() || purpose == null) {
            return null;
        }
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(stored),
                new FieldProtection.RecordRef(ORDER_LINE_TABLE, NOTE_COLUMN, lineId),
                purpose);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfirmedOrder> confirmedBetween(UUID tenantId, UUID locationId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                SELECT id, version, confirmed_at
                  FROM ordering.orders
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                   AND confirmed_at >= :from AND confirmed_at < :to
                 ORDER BY confirmed_at, id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.atOffset(java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query((row, number) -> new ConfirmedOrder(
                        row.getObject("id", UUID.class),
                        row.getInt("version"),
                        row.getObject("confirmed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record Header(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String publicOrderNumber,
            String status,
            String fulfillmentMode,
            String channelCode,
            Instant placedAt,
            @Nullable Instant confirmedAt,
            int version,
            String currency,
            long subtotal,
            long tax,
            long discount,
            long fee,
            long total,
            @Nullable Long cashTendered,
            @Nullable String kitchenNote) {}
}
