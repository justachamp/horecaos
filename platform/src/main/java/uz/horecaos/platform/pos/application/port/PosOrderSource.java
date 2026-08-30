package uz.horecaos.platform.pos.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The order facts a till needs, read once and never copied (ADR 0011).
 *
 * <p>Read-only. The POS module never writes {@code ordering.orders}: an order's
 * status is ordering's to decide, and ADR 0011 is explicit that a POS transport
 * failure never reverses a confirmed commercial order.
 *
 * <p>The contact fields are the reason this port exists rather than a join. A
 * till cannot deliver to a hash, so the export genuinely needs the customer's
 * name, telephone number and address — and ADR 0029 keeps all three under
 * envelope encryption with every reveal recorded against a stated purpose. The
 * implementation reveals them for one export and this module writes none of them
 * down; the export row keeps a hash of the number so a recovery read can compare
 * against a candidate without the number being stored twice.
 */
public interface PosOrderSource {

    /**
     * @return empty when no order of that id belongs to this tenant, which is the
     *         same answer as "it does not exist" and deliberately so
     */
    Optional<ExportableOrder> find(UUID tenantId, UUID orderId, String revealPurpose);

    /**
     * @param status               the ADR 0019 status at the moment of the read.
     *                             Carried so the export can refuse an order that
     *                             never reached {@code CONFIRMED}: a kitchen
     *                             ticket for an unconfirmed order is food cooked
     *                             for a customer who may still be refused
     * @param acceptanceMode       whether HorecaOS already confirmed the order or is
     *                             still waiting on a restaurant. Decides whether
     *                             the till is asked to approve or merely told
     * @param customerName         revealed for this call only
     * @param customerPhone        revealed for this call only. Never stored by
     *                             this module; the export row keeps its hash
     * @param customerAddress      revealed for this call only. Null for a pickup
     *                             or dine-in order, which is not an error
     */
    record ExportableOrder(
            UUID orderId,
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
            String customerName,
            String customerPhone,
            String customerAddress,
            List<Line> lines) {

        public ExportableOrder {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }

        /** Never logged: three of these fields are personal data (ADR 0029). */
        @Override
        public String toString() {
            return "ExportableOrder[" + orderId + ", lines=" + lines.size() + "]";
        }

        /**
         * @param sourceVariantId what HorecaOS sold. The external identifier is
         *                        resolved from this through the ADR 0026 mapping
         *                        rather than from the name, because a provider's
         *                        product name is editable in their back office and
         *                        its identifier is not
         */
        public record Line(
                UUID lineId,
                UUID sourceVariantId,
                String productNameSnapshot,
                String variantNameSnapshot,
                int quantity,
                long unitAmountMinor,
                List<UUID> modifierOptionIds) {

            public Line {
                modifierOptionIds = List.copyOf(modifierOptionIds == null ? List.of() : modifierOptionIds);
            }
        }
    }
}
