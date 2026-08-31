package uz.horecaos.platform.partner.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.partner.domain.DiscountFunding;
import uz.horecaos.platform.partner.domain.ExternalReference;
import uz.horecaos.platform.partner.domain.ExternalTotals;
import uz.horecaos.platform.partner.domain.HandoverChallengeType;

/**
 * Creates the {@code ordering.orders} row for an accepted partner push
 * (ADR 0040).
 *
 * <p>A port rather than a direct call because of what it crosses. ADR 0040
 * decided that an aggregator order is a first-class order and not a second
 * aggregate, which means somebody has to write another module's table; the
 * alternative — a marketplace aggregate that projects into the order list — is
 * the alternative the ADR rejected, because every operations screen, filter,
 * report, cancellation, refund and audit query would then need a second
 * implementation and the two would drift exactly where it matters. Naming the
 * write as a port keeps the crossing in one file and one interface, so moving it
 * behind an intake port published by {@code ordering} is a change of adapter
 * rather than a change of design.
 *
 * <p>Everything here happens inside the caller's transaction. No external call
 * of any kind belongs in it, per ADR 0019: an HTTP call to a partner inside the
 * transaction that creates the order is how one slow partner holds a row lock
 * across a whole dinner service.
 */
public interface MarketplaceOrderIntake {

    /**
     * Writes the {@code ordering.orders} row for an accepted partner push.
     *
     * @return the identifiers of the created order, so the caller can write the
     *         staging row that points at it in the same transaction
     */
    Created create(NewMarketplaceOrder order);

    record Created(UUID orderId, String publicOrderNumber, UUID handoverChallengeId) {}

    /**
     * What creates one order from one accepted partner push.
     *
     * @param guestReferenceHash a keyed hash of {@code (binding, external order
     *                           id)}, and deliberately not a hash of the
     *                           customer's contact. Aggregators proxy phone
     *                           numbers and recycle the proxy pool, so matching
     *                           on one would merge unrelated people into a single
     *                           record and attach their addresses, history and
     *                           consent to each other. A marketplace order never
     *                           creates or matches a {@code customer_account}, and
     *                           ADR 0015's identity model is untouched by it.
     * @param orderId            chosen by the caller rather than here, because
     *                           the handover hash is bound to it and a hash the
     *                           adapter computed after the fact could be lifted
     *                           from one order and replayed against another
     * @param handoverCodeHash   the peppered HMAC of the partner's code, or null
     *                           when the challenge type is {@code NONE}. The plain
     *                           code never reaches this port.
     */
    record NewMarketplaceOrder(
            UUID orderId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String channelCode,
            UUID bindingId,
            String externalOrderId,
            String fulfillmentMode,
            String guestReferenceHash,
            String idempotencyKey,
            ExternalTotals totals,
            DiscountFunding discountFunding,
            String rawTotalsJson,
            List<IntakeLine> lines,
            List<ExternalReference> references,
            HandoverChallengeType challengeType,
            @Nullable String handoverCodeHash,
            String challengeIssuedBy,
            Instant createdAt) {

        public NewMarketplaceOrder {
            lines = List.copyOf(lines);
            references = List.copyOf(references);
        }
    }

    /**
     * One line of a partner push, mapped to a catalogue variant where one exists.
     *
     * @param productId null: a marketplace order carries no HorecaOS product
     *                  identifier, only the resolved variant
     * @param variantId null exactly when the partner sent an item the catalogue
     *                  does not carry. The line keeps the partner's own name and
     *                  amount and is flagged {@code UNMAPPED} rather than
     *                  refusing the order, because refusing it means a customer
     *                  who has already paid the aggregator gets nothing over a
     *                  menu-sync lag on one item.
     */
    record IntakeLine(
            @Nullable UUID productId,
            @Nullable UUID variantId,
            String externalItemReference,
            String nameSnapshot,
            int quantity,
            long unitAmountMinor,
            long lineAmountMinor,
            long taxAmountMinor) {

        public boolean unmapped() {
            return variantId == null;
        }
    }
}
