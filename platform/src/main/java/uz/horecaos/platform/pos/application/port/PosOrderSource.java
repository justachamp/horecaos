package uz.horecaos.platform.pos.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
     * The order facts a till needs, revealed for this call and never stored.
     *
     * @return empty when no order of that id belongs to this tenant, which is the
     *         same answer as "it does not exist" and deliberately so
     */
    Optional<ExportableOrder> find(UUID tenantId, UUID orderId, String revealPurpose);

    /**
     * One order's facts, as a till needs to receive them.
     *
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
     * @param acceptedByActorType  {@code ordering.orders.accepted_by_actor_type}
     *                             (V0029) — {@code "USER"} when a signed-in
     *                             operator moved the order to {@code CONFIRMED},
     *                             null when nobody has yet, and one of {@code
     *                             SERVICE}/{@code SYSTEM_JOB}/{@code PROVIDER}/
     *                             {@code CUSTOMER} for every other path. Carried
     *                             so an export can attribute the order to a POS
     *                             operator (operations-gap-map.md {@code 9.2c})
     *                             without this port reaching back into ordering
     *                             a second time
     * @param acceptedByActorId    the matching actor id — a Keycloak subject
     *                             (parseable as a {@link UUID}) only when {@code
     *                             acceptedByActorType} is {@code "USER"}; for
     *                             every other actor type this is an opaque
     *                             string (for example {@code "pos:clopos"}) and
     *                             must not be parsed as one
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
            @Nullable Instant placedAt,
            @Nullable UUID customerAccountId,
            @Nullable String customerName,
            @Nullable String customerPhone,
            @Nullable String customerAddress,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId,
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
         * One line of the order, as a till needs to receive it.
         *
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
                @Nullable String variantNameSnapshot,
                int quantity,
                long unitAmountMinor,
                List<UUID> modifierOptionIds,
                // Row 2.1b: catalog.comment_presets.id for every preset this
                // line was checked out carrying — mapped to a provider
                // modifier code through ADR 0026 exactly as modifierOptionIds
                // already is.
                List<UUID> commentPresetIds) {

            public Line {
                modifierOptionIds = List.copyOf(modifierOptionIds == null ? List.of() : modifierOptionIds);
                commentPresetIds = List.copyOf(commentPresetIds == null ? List.of() : commentPresetIds);
            }
        }
    }
}
