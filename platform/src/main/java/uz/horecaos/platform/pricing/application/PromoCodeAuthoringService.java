package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.PromoCodeAuthoringRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a brand's promo codes (ADR 0072, operations §6.2 Promo codes).
 *
 * <p><strong>V0093 already built the schema this reads and writes</strong> —
 * {@code pricing.promotions}, {@code promotion_conditions},
 * {@code promotion_actions}, {@code coupon_codes},
 * {@code coupon_customer_usage} and {@code coupon_redemptions} — an earlier
 * wave's implementation of ADR 0018's coupon design with no authoring surface
 * above it. This class is that surface, not a new model.
 *
 * <p><strong>A promo code authors one {@code pricing.promotions} row and one
 * {@code pricing.coupon_codes} row together, as a single unit.</strong> The
 * promotion is this class's own implementation detail, always
 * {@code exclusive = true} and always {@code requiresCoupon = true}: every
 * promotion this authoring surface creates exists to be redeemed by exactly
 * one code. Draft, activate and retire always move both rows together, in
 * one transaction.
 *
 * <p><strong>V0093's lifecycle is honoured exactly, not re-simplified to
 * DRAFT/ACTIVE/RETIRED.</strong> {@code pricing.promotions.status} is
 * {@code DRAFT → VALIDATED → ACTIVE → SUSPENDED/ARCHIVED}, already matching
 * ADR 0018's own API list ({@code .../promotions/{id}/validate} then
 * {@code .../activate}). {@link #activate} sets {@code validated_at} and
 * {@code activated_at} together because this authoring surface's closed,
 * pre-validated discount shapes need no separate human review step between
 * the two — a future general rule-authoring surface might reintroduce one.
 * {@code pricing.coupon_codes} has no {@code DRAFT} of its own; a drafted
 * code is created {@code SUSPENDED} so it cannot be redeemed before its
 * promotion activates, and {@link #retire} moves both rows to
 * {@code ARCHIVED} together.
 *
 * <p><strong>A closed set of three discount shapes</strong> — see
 * {@link DiscountShape} — translated into the {@code Promotion.Action} the
 * pricing engine already evaluates. An operator cannot author an item-level
 * discount, a time window, or a customer-segment restriction through this
 * screen — see ADR 0072's Alternatives table.
 *
 * <p><strong>Unlike a loyalty policy, more than one promo code may be live
 * for a brand at once.</strong> Activation therefore never retires a
 * sibling.
 */
@Service
public class PromoCodeAuthoringService {

    private static final int MAX_PERCENTAGE_BASIS_POINTS = 10_000;

    private final JdbcPromoCodeStore store;
    private final Clock clock;

    public PromoCodeAuthoringService(JdbcPromoCodeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** ADR 0072's closed discount-shape set. Never widened without a new ADR. */
    public enum DiscountShape {
        /** {@code value} is basis points of the goods subtotal, 1-10000. */
        PERCENTAGE_OFF_ORDER,
        /** {@code value} is minor units off the goods subtotal. */
        FIXED_AMOUNT_OFF_ORDER,
        /** {@code value} is ignored and must be zero. */
        FREE_DELIVERY
    }

    /**
     * @param minBasketMinor    0 for no minimum
     * @param channels          channel codes; empty means every channel
     * @param locationIds       empty means every location in the brand
     * @param totalLimit        null for uncapped total redemptions
     * @param perCustomerLimit  {@code pricing.coupon_codes.maximum_per_customer}
     *                          is not nullable — every code carries some cap,
     *                          at least 1. There is no "unlimited per customer"
     *                          in this schema
     * @param validFrom         null takes effect immediately on activation
     */
    public record PromoCodeDraft(
            String name,
            String code,
            DiscountShape shape,
            long value,
            @Nullable Long maximumDiscountMinor,
            String currency,
            long minBasketMinor,
            List<String> channels,
            List<UUID> locationIds,
            @Nullable Integer totalLimit,
            int perCustomerLimit,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {

        public PromoCodeDraft {
            channels = channels == null ? List.of() : List.copyOf(channels);
            locationIds = locationIds == null ? List.of() : List.copyOf(locationIds);
        }
    }

    @Transactional(readOnly = true)
    public List<PromoCodeAuthoringRow> list(UUID tenantId, UUID brandId) {
        return store.listPromoCodes(tenantId, brandId);
    }

    @Transactional
    public PromoCodeAuthoringRow draft(UUID tenantId, UUID brandId, PromoCodeDraft draft) {
        validate(draft);
        String normalizedCode = JdbcPromoCodeStore.normalize(draft.code());

        Instant now = clock.instant();
        Instant validFrom = draft.validFrom() != null ? draft.validFrom() : now;

        UUID promotionId = UUID.randomUUID();
        UUID couponId = UUID.randomUUID();

        store.insertPromotionDraft(
                promotionId,
                tenantId,
                brandId,
                // The promotion's own operator-facing handle (pricing.promotions.code,
                // unique per brand) and the customer-facing redeemable code are the
                // same string for a promo code: this authoring surface's whole point
                // is that the two are one thing, unlike a general automatic promotion
                // that would have a handle but no code at all.
                normalizedCode,
                draft.name(),
                scopeOf(draft.shape()),
                // A stacking group unique to this promotion. exclusive = true
                // already means no other promotion combines with this one, so the
                // group only has to be non-null and never collide with a sibling
                // promo code's own group, which naming it after the coupon it
                // belongs to guarantees.
                "PROMO_CODE:" + couponId,
                true,
                0,
                true,
                draft.maximumDiscountMinor(),
                draft.currency(),
                validFrom,
                draft.validUntil(),
                now);

        int sequence = 0;
        if (draft.minBasketMinor() > 0) {
            store.insertPromotionCondition(
                    promotionId,
                    tenantId,
                    brandId,
                    ++sequence,
                    "SUBTOTAL_AT_LEAST",
                    Map.of("amountMinor", draft.minBasketMinor()));
        }
        if (!draft.channels().isEmpty()) {
            store.insertPromotionCondition(
                    promotionId, tenantId, brandId, ++sequence, "CHANNEL", Map.of("channels", draft.channels()));
        }
        if (!draft.locationIds().isEmpty()) {
            store.insertPromotionCondition(
                    promotionId,
                    tenantId,
                    brandId,
                    ++sequence,
                    "LOCATION",
                    Map.of(
                            "locationIds",
                            draft.locationIds().stream().map(UUID::toString).toList()));
        }

        store.insertPromotionAction(
                promotionId, tenantId, brandId, 1, actionTypeOf(draft.shape()), actionAttributes(draft));

        store.insertCouponDraft(
                couponId,
                tenantId,
                brandId,
                promotionId,
                normalizedCode,
                draft.totalLimit(),
                draft.perCustomerLimit(),
                validFrom,
                draft.validUntil(),
                now);

        return store.findPromoCodeById(tenantId, brandId, couponId, normalizedCode)
                .orElseThrow(() -> new IllegalStateException("Just-inserted promo code vanished mid-transaction"));
    }

    /** Promotes both rows together: the promotion DRAFT to ACTIVE, and the coupon SUSPENDED to ACTIVE. */
    @Transactional
    public void activate(UUID tenantId, UUID brandId, UUID couponId) {
        PromoCodeAuthoringRow row = require(tenantId, brandId, couponId);
        if (!"SUSPENDED".equals(row.status())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only a freshly drafted promo code can be activated; this one is " + row.status());
        }
        Instant now = clock.instant();
        if (!store.activatePromotion(tenantId, brandId, row.promotionId(), now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This promo code was already activated or retired");
        }
        if (!store.activateCoupon(tenantId, brandId, couponId, now)) {
            // Rolled back with the promotion update above: both rows always
            // move together, or neither does.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This promo code was already activated or retired");
        }
    }

    /** Withdraws a live code, or discards a draft nobody activated. Both rows move to ARCHIVED together. */
    @Transactional
    public void retire(UUID tenantId, UUID brandId, UUID couponId) {
        PromoCodeAuthoringRow row = require(tenantId, brandId, couponId);
        Instant now = clock.instant();
        // Best-effort cleanup: the coupon update below is the authority on
        // whether this call succeeds, and the promotion may already be
        // ARCHIVED (or, in principle, moved through some other path) without
        // that being an error here.
        store.retirePromotion(tenantId, brandId, row.promotionId(), now);
        if (!store.retireCoupon(tenantId, brandId, couponId, now)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such promo code to retire");
        }
    }

    private PromoCodeAuthoringRow require(UUID tenantId, UUID brandId, UUID couponId) {
        return store.findPromoCodeById(tenantId, brandId, couponId, null)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such promo code"));
    }

    private static String scopeOf(DiscountShape shape) {
        return shape == DiscountShape.FREE_DELIVERY ? "DELIVERY" : "ORDER";
    }

    private static String actionTypeOf(DiscountShape shape) {
        return switch (shape) {
            case PERCENTAGE_OFF_ORDER -> "ORDER_PERCENTAGE_DISCOUNT";
            case FIXED_AMOUNT_OFF_ORDER -> "ORDER_FIXED_DISCOUNT";
            case FREE_DELIVERY -> "FREE_DELIVERY";
        };
    }

    private static Map<String, Object> actionAttributes(PromoCodeDraft draft) {
        return switch (draft.shape()) {
            case PERCENTAGE_OFF_ORDER -> Map.of("basisPoints", draft.value());
            case FIXED_AMOUNT_OFF_ORDER -> Map.of("amountMinor", draft.value());
            case FREE_DELIVERY -> Map.of();
        };
    }

    private void validate(PromoCodeDraft draft) {
        List<String> problems = new ArrayList<>();
        if (draft.name() == null || draft.name().isBlank()) {
            problems.add("name is required");
        }
        String normalized = draft.code() == null ? "" : JdbcPromoCodeStore.normalize(draft.code());
        if (!normalized.matches("^[A-Z0-9]{4,32}$")) {
            problems.add("code must be 4-32 upper-case letters or digits");
        }
        if (draft.currency() == null || !draft.currency().matches("^[A-Z]{3}$")) {
            problems.add("currency must be a 3-letter ISO code");
        }
        switch (draft.shape()) {
            case PERCENTAGE_OFF_ORDER -> {
                if (draft.value() <= 0 || draft.value() > MAX_PERCENTAGE_BASIS_POINTS) {
                    problems.add("value must be between 1 and 10000 basis points for a percentage discount");
                }
            }
            case FIXED_AMOUNT_OFF_ORDER -> {
                if (draft.value() <= 0) {
                    problems.add("value must be positive for a fixed-amount discount");
                }
            }
            case FREE_DELIVERY -> {
                if (draft.value() != 0) {
                    problems.add("value must be zero for a free-delivery code");
                }
            }
        }
        if (draft.maximumDiscountMinor() != null && draft.maximumDiscountMinor() <= 0) {
            problems.add("maximumDiscountMinor must be positive when set, or omitted for uncapped");
        }
        if (draft.minBasketMinor() < 0) {
            problems.add("minBasketMinor cannot be negative");
        }
        if (draft.totalLimit() != null && draft.totalLimit() <= 0) {
            problems.add("totalLimit must be positive when set, or omitted for uncapped");
        }
        if (draft.perCustomerLimit() <= 0) {
            problems.add("perCustomerLimit must be positive — this schema has no uncapped-per-customer option");
        }
        if (draft.validUntil() != null
                && draft.validFrom() != null
                && !draft.validUntil().isAfter(draft.validFrom())) {
            problems.add("validUntil must be after validFrom");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
    }
}
