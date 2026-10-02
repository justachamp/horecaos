package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.pricing.api.PromotionQueryPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.LedgerRow;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.NewRedemption;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.QuotePromotionAmount;

/**
 * The redemption ledger and the usage limits of automatic promotions (ADR 0140),
 * the companion of {@link PromoCodeRedemptionService}.
 *
 * <h2>One row per (order, promotion)</h2>
 *
 * Written at checkout for every automatic promotion the accepted quote carries,
 * limited or not, because the ledger is also the source of report 7.9. An
 * amendment that reprices the order moves the row in place ({@link
 * #restateForOrder}); it never writes a second one, which is what stops report 7.9
 * counting the discount given twice. The row's {@code claimed_quote_id} is the
 * checkout quote and never changes, so a retried checkout is idempotent; its
 * {@code current_quote_id} is the quote behind the order's current revision, and
 * its amounts equal the sum of that promotion's adjustments on that quote.
 *
 * <h2>Why the claim is race-free</h2>
 *
 * The same two conditional writes ADR 0072 uses for coupons: a conditional {@code
 * UPDATE} of {@code consumed_count}, whose row lock serialises concurrent
 * checkouts so exactly one wins the last slot, and an upsert into {@code
 * promotion_customer_usage}. A promotion with a {@code FIRST} or {@code NTH}
 * sequence condition must declare a per-customer limit of one (the validator
 * refuses it otherwise), so the claim re-verifies "first" inside the transaction
 * and two quick same-account checkouts cannot both win. {@code EVERY_NTH} keeps a
 * small race window the record accepts.
 *
 * <p>A cancelled order does not return its slot, and an amendment never claims
 * one: a promotion that stops applying is {@code RELEASED} in the ledger and its
 * counter stays consumed (the rule ADR 0072 sets for coupons).
 */
@Service
public class PromotionRedemptionService
        implements PromotionRedemptionPort, PromotionRedemptionSource, PromotionQueryPort {

    private static final Logger log = LoggerFactory.getLogger(PromotionRedemptionService.class);

    private final JdbcPromotionStore store;

    public PromotionRedemptionService(JdbcPromotionStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public Result claimForQuote(
            UUID tenantId, UUID brandId, UUID quoteId, UUID orderId, @Nullable UUID customerAccountId, Instant now) {

        // What applied, read from the quote's own adjustments and never from a value the caller
        // supplies: a request cannot claim a redemption for a promotion the quote was not priced with.
        List<QuotePromotionAmount> applied = store.promotionAmountsOnQuote(tenantId, quoteId);
        if (applied.isEmpty()) {
            return new Result(Result.Outcome.CLAIMED, null);
        }
        // A retried checkout finds its own rows and claims nothing twice.
        if (!store.byClaimedQuote(tenantId, quoteId).isEmpty()) {
            return new Result(Result.Outcome.CLAIMED, null);
        }

        // In promotion-id order, so two checkouts claiming overlapping sets take row locks in the same
        // order and cannot deadlock each other.
        List<QuotePromotionAmount> ordered = new ArrayList<>(applied);
        ordered.sort(Comparator.comparing(QuotePromotionAmount::promotionId));

        List<Claimed> claimed = new ArrayList<>();
        for (QuotePromotionAmount amount : ordered) {
            var promotion = store.find(tenantId, brandId, amount.promotionId());
            if (promotion.isEmpty()) {
                // A promotion the quote names that this brand does not own: nothing of ours to count.
                continue;
            }
            Integer perCustomer = promotion.get().definition().maximumPerCustomer();
            boolean limited = promotion.get().definition().maximumRedemptions() != null || perCustomer != null;
            if (limited) {
                if (!store.claimTotal(tenantId, brandId, amount.promotionId(), now)) {
                    giveBack(tenantId, brandId, claimed);
                    return new Result(Result.Outcome.LIMIT_REACHED, amount.promotionId());
                }
                boolean customerSlot = false;
                // A guest's per-customer cap is not enforced, as decided for coupons: there is no
                // identity to count a guest against.
                if (perCustomer != null && customerAccountId != null) {
                    if (!store.claimCustomerSlot(
                            tenantId, brandId, amount.promotionId(), customerAccountId, perCustomer)) {
                        store.releaseTotal(tenantId, brandId, amount.promotionId());
                        giveBack(tenantId, brandId, claimed);
                        return new Result(Result.Outcome.PER_CUSTOMER_LIMIT_REACHED, amount.promotionId());
                    }
                    customerSlot = true;
                }
                claimed.add(new Claimed(amount.promotionId(), true, customerSlot ? customerAccountId : null));
            } else {
                claimed.add(new Claimed(amount.promotionId(), false, null));
            }
            store.insertRedemption(
                    new NewRedemption(
                            Ids.newId(),
                            tenantId,
                            brandId,
                            amount.promotionId(),
                            amount.definitionVersion(),
                            quoteId,
                            1,
                            orderId,
                            customerAccountId,
                            amount.discountMinor(),
                            amount.markupMinor(),
                            amount.currency()),
                    now);
        }
        return new Result(Result.Outcome.CLAIMED, null);
    }

    /** Gives back the counters a partly successful claim already took. */
    private void giveBack(UUID tenantId, UUID brandId, List<Claimed> claimed) {
        for (Claimed taken : claimed) {
            if (taken.counted()) {
                store.releaseTotal(tenantId, brandId, taken.promotionId());
            }
            if (taken.customerAccountId() != null) {
                store.releaseCustomerSlot(tenantId, taken.promotionId(), taken.customerAccountId());
            }
        }
    }

    private record Claimed(
            UUID promotionId, boolean counted, @Nullable UUID customerAccountId) {}

    @Override
    @Transactional
    public boolean releaseForQuote(UUID tenantId, UUID quoteId) {
        List<LedgerRow> rows = store.deleteByClaimedQuote(tenantId, quoteId);
        for (LedgerRow row : rows) {
            store.find(tenantId, row.brandId(), row.promotionId()).ifPresent(promotion -> {
                var definition = promotion.definition();
                if (definition.maximumRedemptions() != null || definition.maximumPerCustomer() != null) {
                    store.releaseTotal(tenantId, row.brandId(), row.promotionId());
                    if (definition.maximumPerCustomer() != null && row.customerAccountId() != null) {
                        store.releaseCustomerSlot(tenantId, row.promotionId(), row.customerAccountId());
                    }
                }
            });
        }
        return !rows.isEmpty();
    }

    @Override
    @Transactional
    public void restateForOrder(
            UUID tenantId,
            UUID brandId,
            UUID orderId,
            UUID amendedQuoteId,
            int revision,
            @Nullable UUID customerAccountId,
            Instant now) {

        Map<UUID, QuotePromotionAmount> nowApplied = new HashMap<>();
        store.promotionAmountsOnQuote(tenantId, amendedQuoteId)
                .forEach(amount -> nowApplied.put(amount.promotionId(), amount));
        Map<UUID, LedgerRow> held = new HashMap<>();
        store.heldByOrder(tenantId, orderId).forEach(row -> held.put(row.promotionId(), row));

        for (QuotePromotionAmount amount : nowApplied.values()) {
            LedgerRow existing = held.get(amount.promotionId());
            if (existing == null) {
                // Newly applies: unlimited by construction (an amendment cannot claim a slot), so no
                // counter moves. Both quote ids are the amendment's.
                store.insertRedemption(
                        new NewRedemption(
                                Ids.newId(),
                                tenantId,
                                brandId,
                                amount.promotionId(),
                                amount.definitionVersion(),
                                amendedQuoteId,
                                revision,
                                orderId,
                                customerAccountId,
                                amount.discountMinor(),
                                amount.markupMinor(),
                                amount.currency()),
                        now);
            } else if ("REDEEMED".equals(existing.status())) {
                store.restate(
                        tenantId,
                        orderId,
                        amount.promotionId(),
                        amendedQuoteId,
                        revision,
                        amount.discountMinor(),
                        amount.markupMinor());
            } else {
                // Applied, stopped, and applies again: the same row comes back, still one per (order, promotion).
                store.reinstate(
                        tenantId,
                        orderId,
                        amount.promotionId(),
                        amendedQuoteId,
                        revision,
                        amount.discountMinor(),
                        amount.markupMinor());
            }
        }
        for (LedgerRow row : held.values()) {
            if ("REDEEMED".equals(row.status()) && !nowApplied.containsKey(row.promotionId())) {
                // Stopped applying: the row is RELEASED and the counter stays consumed.
                store.markReleased(tenantId, orderId, row.promotionId(), revision, now);
                log.debug(
                        "Promotion {} stopped applying to order {} at revision {}",
                        row.promotionId(),
                        orderId,
                        revision);
            }
        }
    }

    // ----------------------------------------------------------------- report

    @Override
    @Transactional(readOnly = true)
    public List<Redemption> redeemedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive) {
        return store.redemptionFactRows(tenantId, fromInclusive, toExclusive).stream()
                .map(row -> new Redemption(
                        row.redemptionId(),
                        row.brandId(),
                        row.promotionId(),
                        row.promotionCode(),
                        row.definitionVersion(),
                        Redemption.Kind.valueOf(row.sourceKind()),
                        row.couponId(),
                        row.orderId(),
                        row.customerAccountId(),
                        row.discountMinor(),
                        row.markupMinor(),
                        row.currency(),
                        row.redeemedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ query

    @Override
    @Transactional(readOnly = true)
    public boolean paymentMethodChangesTheTotal(UUID tenantId, UUID brandId, @Nullable UUID orderId) {
        return store.hasActivePaymentMethodPromotion(tenantId, brandId)
                || (orderId != null && store.orderHoldsPaymentMethodPromotion(tenantId, orderId));
    }
}
