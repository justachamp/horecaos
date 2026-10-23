package uz.horecaos.platform.payments.application;

import java.util.OptionalLong;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.payments.domain.FiscalDocument;

/**
 * What the payments side knows that a receipt has to add up to (ADR 0166, ADR 0046).
 *
 * <p>A fiscal document covers one money tender, and the lines of its receipt must equal what that
 * tender collected to the minor unit. A points redemption on the same order is not on the receipt as a
 * tender: it is a discount spread across the goods, so the builder needs to know how much was redeemed.
 * Both are payments' to answer and neither is the order's, so they sit behind a small seam the builder
 * reads and a test can state in one line.
 */
public interface ReceiptPaymentFacts {

    /**
     * What the provider payment behind this document was asked to collect.
     *
     * @return empty when the document names no payment intent, or the intent cannot be read
     */
    default OptionalLong providerAmountMinor(UUID tenantId, FiscalDocument document) {
        return providerAmountMinor(tenantId, document.paymentIntentId());
    }

    /**
     * What the provider payment behind this intent was asked to collect: the same answer for a receipt built
     * before a document exists (Payme's, fixed with the checkout).
     *
     * @return empty when no intent is named, or it cannot be read
     */
    OptionalLong providerAmountMinor(UUID tenantId, @Nullable UUID paymentIntentId);

    /**
     * The points tender of the order's settlement that is held or has settled, or zero when there is none.
     *
     * <p>Held counts: Payme's lines are built at presentation, before the money arrives, when the balance tender is
     * still reserved. A tender that was released, failed or reversed went back to the customer and is not counted.
     */
    long pointsRedeemedMinor(UUID tenantId, UUID orderId);
}
