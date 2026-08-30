package uz.horecaos.platform.pos.infrastructure.ordering;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.pos.application.port.PosOrderSource;

/**
 * Reads the order an export sends (ADR 0011, ADR 0029).
 *
 * <p>Reads, and only reads. The POS module has no write path into
 * {@code ordering}; whether an order is confirmed is ordering's decision, and a
 * till that failed to receive it does not get to change that.
 *
 * <p>Three of the columns here are envelope-encrypted personal data, and each
 * reveal is recorded against the purpose the caller stated. That is not a
 * formality: ADR 0029's whole argument for the purpose parameter is that one
 * agent opening one customer and an integration revealing five thousand numbers
 * in an hour are different events, and an export path is exactly the second
 * shape. Nothing revealed here is stored, logged, or put on an event.
 *
 * <p>An order whose snapshot has been anonymized by the ADR 0029 retention job
 * reads back with nulls, which is correct and final. An order old enough to have
 * been anonymized is not an order anybody is still exporting to a kitchen.
 */
@Component
public class JdbcPosOrderSource implements PosOrderSource {

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    public JdbcPosOrderSource(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    public Optional<ExportableOrder> find(UUID tenantId, UUID orderId, String revealPurpose) {
        Optional<Header> header = jdbc.sql("""
                SELECT o.id, o.tenant_id, o.brand_id, o.location_id, o.public_order_number,
                       o.status, o.acceptance_mode_snapshot, o.fulfillment_mode,
                       o.currency, o.total_minor, o.created_at, o.customer_account_id,
                       s.display_name_encrypted, s.contact_encrypted, s.address_encrypted,
                       s.anonymized_at
                  FROM ordering.orders o
                  LEFT JOIN ordering.order_customer_snapshots s
                         ON s.order_id = o.id AND s.tenant_id = o.tenant_id
                 WHERE o.tenant_id = :tenantId AND o.id = :orderId
                """)
                // Every tenant-owned query carries the tenant predicate, and this
                // one carries it on the join as well: a snapshot matched on order
                // id alone would be a cross-tenant read of somebody's address.
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new Header(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getString("status"),
                        row.getString("acceptance_mode_snapshot"),
                        row.getString("fulfillment_mode"),
                        row.getString("currency"),
                        row.getLong("total_minor"),
                        instant(row.getObject("created_at", OffsetDateTime.class)),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("display_name_encrypted"),
                        row.getString("contact_encrypted"),
                        row.getString("address_encrypted"),
                        row.getObject("anonymized_at", OffsetDateTime.class) != null))
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }
        Header found = header.get();

        List<ExportableOrder.Line> lines = readLines(tenantId, orderId);

        return Optional.of(new ExportableOrder(
                found.id(),
                found.tenantId(),
                found.brandId(),
                found.locationId(),
                found.publicOrderNumber(),
                found.status(),
                found.acceptanceMode(),
                found.fulfillmentMode(),
                found.currency(),
                found.totalMinor(),
                found.placedAt(),
                found.customerAccountId(),
                reveal(tenantId, orderId, "display_name_encrypted", found.displayNameEncrypted(), revealPurpose),
                reveal(tenantId, orderId, "contact_encrypted", found.contactEncrypted(), revealPurpose),
                reveal(tenantId, orderId, "address_encrypted", found.addressEncrypted(), revealPurpose),
                lines));
    }

    private List<ExportableOrder.Line> readLines(UUID tenantId, UUID orderId) {
        Map<UUID, List<UUID>> modifiersByLine = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.order_line_id, m.source_option_id
                  FROM ordering.order_line_modifiers m
                  JOIN ordering.order_lines l
                    ON l.id = m.order_line_id AND l.tenant_id = m.tenant_id
                 WHERE m.tenant_id = :tenantId AND l.order_id = :orderId
                 ORDER BY m.order_line_id, m.source_option_id
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> Map.entry(
                        row.getObject("order_line_id", UUID.class), row.getObject("source_option_id", UUID.class)))
                .list()
                .forEach(entry -> modifiersByLine
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));

        return jdbc.sql("""
                SELECT id, source_variant_id, product_name_snapshot, variant_name_snapshot,
                       quantity, unit_amount_minor
                  FROM ordering.order_lines
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                 ORDER BY line_number
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> {
                    UUID lineId = row.getObject("id", UUID.class);
                    return new ExportableOrder.Line(
                            lineId,
                            row.getObject("source_variant_id", UUID.class),
                            row.getString("product_name_snapshot"),
                            row.getString("variant_name_snapshot"),
                            row.getInt("quantity"),
                            row.getLong("unit_amount_minor"),
                            modifiersByLine.getOrDefault(lineId, List.of()));
                })
                .list();
    }

    /**
     * @return null for an absent or anonymized value, which is an ordinary answer
     *         rather than a failure — a pickup order has no address, and an old
     *         order has had its snapshot blanked on purpose
     */
    private String reveal(UUID tenantId, UUID orderId, String column, String stored, String purpose) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(stored),
                new FieldProtection.RecordRef(SNAPSHOT_TABLE, column, orderId),
                purpose);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** The row as read, before anything is decrypted. */
    private record Header(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String publicOrderNumber,
            String status,
            String acceptanceMode,
            String fulfillmentMode,
            String currency,
            long totalMinor,
            Instant placedAt,
            UUID customerAccountId,
            String displayNameEncrypted,
            String contactEncrypted,
            String addressEncrypted,
            boolean anonymized) {}

    /**
     * The classification these three columns carry, stated here so the reason the
     * reveal takes a purpose is visible at the place it happens.
     */
    static final DataClass CONTACT_CLASS = DataClass.PERSONAL;
}
