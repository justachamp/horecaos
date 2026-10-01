package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where an automatic promotion's redemption is recorded and limited (ADR 0140),
 * the companion of {@link PromoCodeRedemptionPort}.
 *
 * <p>Called by {@code CheckoutReservationStep} inside the transaction that holds
 * the stock and accepts the quote, and by {@code OrderAmendmentService} inside the
 * transaction that inserts a revision. A row is written for every automatic
 * promotion an order carries, limited or not, because the ledger is also the
 * source of report 7.9; the two counters move only for a limited one.
 */
public interface PromotionRedemptionPort {

    /**
     * Finds the automatic promotions the quote was priced with -- from the quote's
     * own adjustments, never from a value on the request -- claims each limited
     * one's slots atomically, and writes the ledger rows.
     *
     * <p>Idempotent per quote: a retried checkout finds its own rows and claims
     * nothing twice.
     */
    Result claimForQuote(
            UUID tenantId, UUID brandId, UUID quoteId, UUID orderId, @Nullable UUID customerAccountId, Instant now);

    /** Compensates {@link #claimForQuote} when a later step of the same checkout fails. */
    boolean releaseForQuote(UUID tenantId, UUID quoteId);

    /**
     * Moves an order's ledger rows to the quote an amendment repriced it under.
     *
     * <p>Not a claim: an amendment never increments a counter. The row is keyed by
     * (order, promotion), so the order keeps exactly one row per promotion; its
     * {@code claimed_quote_id} never changes and its amounts move in place. A
     * promotion that stopped applying is released and its counter stays consumed
     * (the rule a cancellation follows too); one that newly applies gets a row
     * with both quote ids set to the amendment's quote.
     */
    void restateForOrder(
            UUID tenantId,
            UUID brandId,
            UUID orderId,
            UUID amendedQuoteId,
            int revision,
            @Nullable UUID customerAccountId,
            Instant now);

    record Result(Outcome outcome, @Nullable UUID promotionId) {

        public boolean isRefused() {
            return outcome == Outcome.LIMIT_REACHED || outcome == Outcome.PER_CUSTOMER_LIMIT_REACHED;
        }

        public enum Outcome {
            /** No automatic promotion was priced into the quote, or none carries a limit. Nothing refused. */
            CLAIMED,
            /** A limited promotion's total was already used up by another checkout. */
            LIMIT_REACHED,
            /** This customer already holds every redemption the promotion allows them. */
            PER_CUSTOMER_LIMIT_REACHED
        }
    }
}
