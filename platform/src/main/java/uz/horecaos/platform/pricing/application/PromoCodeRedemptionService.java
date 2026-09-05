package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort.RedemptionResult.Result;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.AppliedCoupon;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.CouponEligibilityRow;

/**
 * The one place a promo-code redemption is recorded (ADR 0072), against
 * V0093's already-built {@code pricing.coupon_codes} / {@code coupon_customer_usage}
 * / {@code coupon_redemptions} schema.
 *
 * <p>Called by {@code CheckoutReservationStep}, inside the same transaction as
 * the inventory hold and the quote acceptance. V0093's own comment on
 * {@code coupon_redemptions.quote_id} says usage is "reserved with the quote
 * and never first at order creation" — ADR 0018's decision. This class
 * deliberately does not implement that literally: it writes the row directly
 * as {@code REDEEMED}, at checkout, not as a {@code RESERVED} row created at
 * every {@code QuoteService.quote()} call. A cart is re-quoted on every edit
 * (line change, destination change, and now a promo-code change all clear
 * the attached quote), so reserving at quote time would consume a low-limit
 * code's slots on abandoned carts and re-quotes rather than completed
 * orders — see ADR 0072's Alternatives table. Eligibility is re-checked, read
 * only, at every price instead, which closes the actual risk ADR 0018 names
 * ("the customer sees a valid discount that vanishes at checkout") without
 * that cost. {@code RESERVED} is not used by this class; {@code RELEASED} is,
 * as {@link #release}'s compensation.
 *
 * <h2>Why the increment-then-claim order is what makes this race-free</h2>
 *
 * {@link JdbcPromoCodeStore#incrementIfWithinLimit} is a conditional
 * {@code UPDATE}: it succeeds only when the coupon is, at this instant,
 * active, in its window, and under its total limit, and the {@code WHERE}
 * clause makes that one atomic check-and-write rather than a read followed by
 * a write. A second concurrent transaction attempting the same {@code UPDATE}
 * against the same {@code coupon_codes} row blocks on Postgres's own row lock
 * until this transaction commits or rolls back — which is what makes "exactly
 * one winner for the last redemption" true without a separate advisory lock.
 *
 * <p>The per-customer slot is claimed only <em>after</em> that increment
 * succeeds, through its own atomic upsert
 * ({@link JdbcPromoCodeStore#claimCustomerSlot}) rather than a
 * read-then-write — a customer racing themselves in two tabs cannot claim two
 * slots either. If the claim fails, the coupon-level increment is
 * compensated (decremented back) before refusing, so the coupon's own limit
 * is never left looking consumed by an attempt that did not actually redeem
 * it.
 */
@Service
public class PromoCodeRedemptionService implements PromoCodeRedemptionPort {

    private static final Logger log = LoggerFactory.getLogger(PromoCodeRedemptionService.class);

    private final JdbcPromoCodeStore store;
    private final Clock clock;

    public PromoCodeRedemptionService(JdbcPromoCodeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RedemptionResult reserveForQuote(
            UUID tenantId, UUID brandId, UUID quoteId, UUID orderId, @Nullable UUID customerAccountId, Instant now) {

        // Which coupon (if any) actually produced an adjustment on this quote —
        // read from the quote's own evidence, never from a value the caller
        // supplies. A request cannot claim a redemption for a code the quote
        // was not actually priced with.
        Optional<AppliedCoupon> applied = store.findCouponAppliedToQuote(tenantId, quoteId);
        if (applied.isEmpty()) {
            return new RedemptionResult(Result.NO_CODE_APPLIED);
        }
        AppliedCoupon coupon = applied.get();

        if (!store.incrementIfWithinLimit(tenantId, coupon.couponId(), now)) {
            // Refused before this transaction changed anything: the coupon was
            // no longer active, outside its window, or already at its total
            // limit at this exact instant. LIMIT_REACHED is the closest single
            // name for all three, and CheckoutReservationStep only needs to
            // know that the discount can no longer be honoured, not which of
            // the three reasons applied.
            return new RedemptionResult(Result.LIMIT_REACHED);
        }

        int maximumPerCustomer = store.findCouponById(tenantId, coupon.couponId())
                .map(CouponEligibilityRow::maximumPerCustomer)
                .orElse(1);

        // A guest checkout's per-customer cap simply does not apply — see
        // JdbcPromoCodeStore's class doc — so only a signed-in customer's
        // attempt claims a slot at all.
        if (customerAccountId != null
                && !store.claimCustomerSlot(tenantId, coupon.couponId(), customerAccountId, maximumPerCustomer)) {
            store.decrementCoupon(tenantId, coupon.couponId());
            log.debug(
                    "Coupon {} refused for customer {}: per-customer limit reached",
                    coupon.couponId(),
                    customerAccountId);
            return new RedemptionResult(Result.PER_CUSTOMER_LIMIT_REACHED);
        }

        store.insertRedemption(
                UUID.randomUUID(),
                tenantId,
                brandId,
                coupon.couponId(),
                coupon.promotionId(),
                quoteId,
                orderId,
                customerAccountId,
                coupon.discountMinor(),
                coupon.currency(),
                now);
        return new RedemptionResult(Result.REDEEMED);
    }

    @Override
    @Transactional
    public boolean release(UUID tenantId, UUID quoteId) {
        Optional<JdbcPromoCodeStore.ReleasedRedemption> released =
                store.releaseRedemptionByQuote(tenantId, quoteId, clock.instant());
        released.ifPresent(redemption -> {
            store.decrementCoupon(tenantId, redemption.couponId());
            if (redemption.customerAccountId() != null) {
                store.releaseCustomerSlot(tenantId, redemption.couponId(), redemption.customerAccountId());
            }
        });
        return released.isPresent();
    }
}
