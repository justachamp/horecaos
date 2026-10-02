package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.api.AudienceMembershipPort;
import uz.horecaos.platform.pricing.api.CustomerOrderHistoryPort;
import uz.horecaos.platform.pricing.api.PromoCodeQueryPort;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.tenancy.api.SalesChannel;

/**
 * Resolves every value the promotion engine reads, before the engine runs
 * (ADR 0140).
 *
 * <p>The engine is a pure function, so everything that could differ between two
 * runs -- a clock, a timezone, an order count, an audience, a counter -- is
 * resolved here and handed over as a value that also enters the context hash. One
 * resolver serves a real quote and the simulator: the simulator supplies synthetic
 * customer facts through {@link Overrides} instead of an account id, and nothing
 * else about the path differs, which is what lets a test assert that the two agree
 * on totals, adjustments and hash.
 *
 * <p>Three paths share this class:
 * <ul>
 *   <li><b>The cart.</b> The payment method and fulfilment mode come from the cart,
 *       the service instant is the clock, history and segments are read fresh.
 *   <li><b>An amendment.</b> The order's recorded inputs are inherited; the promotions
 *       it already holds are evaluated at the definition version recorded on its
 *       ledger rows even if they have since been suspended, archived or edited; any
 *       other active automatic promotion without limits is a candidate; a limited
 *       promotion it does not hold is not (the claim happens in checkout, and an
 *       amendment adds none).
 *   <li><b>The simulator.</b> Candidates and replayed definition versions are added,
 *       and the usage limits are read but never claimed.
 * </ul>
 */
@Component
public class PromotionInputResolver {

    private final JdbcPromoCodeStore promoCodes;
    private final JdbcPromotionStore promotions;
    private final JdbcPricingStore pricing;
    private final PromoCodeEligibilityService promoCodeEligibility;
    private final MenuMembershipLookup membership;
    private final LocationTimeZoneLookup timeZones;
    private final ObjectProvider<CustomerOrderHistoryPort> history;
    private final ObjectProvider<AudienceMembershipPort> audiences;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public PromotionInputResolver(
            JdbcPromoCodeStore promoCodes,
            JdbcPromotionStore promotions,
            JdbcPricingStore pricing,
            PromoCodeEligibilityService promoCodeEligibility,
            MenuMembershipLookup membership,
            LocationTimeZoneLookup timeZones,
            ObjectProvider<CustomerOrderHistoryPort> history,
            ObjectProvider<AudienceMembershipPort> audiences) {
        this.promoCodes = promoCodes;
        this.promotions = promotions;
        this.pricing = pricing;
        this.promoCodeEligibility = promoCodeEligibility;
        this.membership = membership;
        this.timeZones = timeZones;
        this.history = history;
        this.audiences = audiences;
    }

    /**
     * Synthetic facts for the simulator, standing in for what a real account would
     * resolve to. Never an account id: the simulator reads no personal history.
     *
     * @param brandOrderPosition the position the simulated order would take, or null for a guest
     */
    public record Overrides(
            @Nullable Instant serviceInstant,
            @Nullable String fulfillmentMode,
            @Nullable String paymentMethodCode,
            @Nullable Integer brandOrderPosition,
            @Nullable Integer channelOrderPosition,
            Set<String> segments,
            List<PromotionDefinition> candidates,
            Map<UUID, Integer> replayedVersions) {

        public Overrides {
            segments = segments == null ? Set.of() : Set.copyOf(segments);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            replayedVersions = replayedVersions == null ? Map.of() : Map.copyOf(replayedVersions);
        }
    }

    /** What the engine is handed, and the evidence a quote records about it. */
    public record Resolved(PricingEngine.PromotionInputs inputs, RecordedPromotionInputs recorded) {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Resolved resolve(
            QuoteRequest request,
            Instant now,
            Optional<SalesChannel> channel,
            @Nullable ResolvedDeliveryCharge charge,
            Set<UUID> variantIds,
            @Nullable Overrides overrides) {

        QuoteRequest.Frame frame = request.frame();
        boolean amendment = (frame != null && frame.isAmendment()) || request.carriedRedemptionOrderId() != null;
        RecordedPromotionInputs inherited = frame != null && frame.inheritFromQuoteId() != null
                ? pricing.findPromotionInputs(request.tenantId(), frame.inheritFromQuoteId())
                        .map(RecordedPromotionInputs::fromDocument)
                        .orElse(null)
                : null;

        // The service instant: a synthetic one, a fixed one, the one the order was placed
        // under, the order's creation time for a quote that recorded none, or the clock.
        Instant serviceInstant = firstNonNull(
                overrides == null ? null : overrides.serviceInstant(),
                frame == null ? null : frame.serviceInstant(),
                inherited == null ? null : inherited.serviceInstant(),
                frame == null ? null : frame.placedAt(),
                now);
        ZoneId zone = timeZones.zoneOf(request.tenantId(), request.locationId());
        var local = serviceInstant.atZone(zone);

        String fulfillmentMode = firstNonNull(
                overrides == null ? null : overrides.fulfillmentMode(),
                frame == null ? null : frame.fulfillmentMode(),
                inherited == null ? null : inherited.fulfillmentMode(),
                request.delivery() != null ? "DELIVERY" : "PICKUP");

        String paymentMethodCode;
        if (overrides != null && overrides.paymentMethodCode() != null) {
            paymentMethodCode = overrides.paymentMethodCode();
        } else if (frame != null && frame.paymentMethodCode() != null) {
            paymentMethodCode = frame.paymentMethodCode();
        } else if (amendment && inherited != null) {
            paymentMethodCode = inherited.paymentMethodCode();
        } else {
            paymentMethodCode = null;
        }

        String channelType = channel.map(resolved -> resolved.systemType().name())
                .orElse(inherited == null ? null : inherited.channelType());
        UUID channelId = channel.map(SalesChannel::id).orElse(null);
        UUID deliveryZoneId = charge != null && charge.isResolved() ? charge.zoneId() : null;

        List<Promotion> pool = candidatePool(request, now, overrides, amendment);
        UUID accountId = request.customerAccountId();

        // The history and the audiences are only consulted when something on offer reads
        // them, so a brand with no such promotion never pays a query per quote.
        boolean readsSequence = pool.stream()
                .anyMatch(promotion -> promotion.hasCondition(Promotion.Condition.Type.ORDER_SEQUENCE)
                        || promotion.hasCondition(Promotion.Condition.Type.FIRST_ORDER));
        boolean readsSegments =
                pool.stream().anyMatch(promotion -> promotion.hasCondition(Promotion.Condition.Type.CUSTOMER_SEGMENT));

        Integer brandPosition = null;
        Integer channelPosition = null;
        boolean firstOrder = false;
        if (overrides != null && overrides.brandOrderPosition() != null) {
            brandPosition = overrides.brandOrderPosition();
            channelPosition = overrides.channelOrderPosition();
            firstOrder = brandPosition == 1;
        } else if (inherited != null && (inherited.brandOrderPosition() != null || !readsSequence)) {
            // The recorded position is authoritative: an amended first order stays first
            // however many orders the customer placed after it.
            brandPosition = inherited.brandOrderPosition();
            channelPosition = inherited.channelOrderPosition();
            firstOrder = inherited.firstOrder();
        } else if (readsSequence && accountId != null && overrides == null) {
            CustomerOrderHistoryPort port = history.getIfAvailable();
            if (port != null) {
                UUID excluding = request.carriedRedemptionOrderId() != null ? request.carriedRedemptionOrderId() : null;
                brandPosition = 1
                        + port.countPriorOrders(
                                request.tenantId(),
                                request.brandId(),
                                accountId,
                                CustomerOrderHistoryPort.Basis.BRAND,
                                null,
                                serviceInstant,
                                excluding);
                if (channelId != null) {
                    channelPosition = 1
                            + port.countPriorOrders(
                                    request.tenantId(),
                                    request.brandId(),
                                    accountId,
                                    CustomerOrderHistoryPort.Basis.CHANNEL,
                                    channelId,
                                    serviceInstant,
                                    excluding);
                }
                firstOrder = brandPosition == 1;
            }
        }

        Set<String> segments = Set.of();
        if (overrides != null) {
            segments = overrides.segments();
        } else if (inherited != null && !inherited.segments().isEmpty()) {
            segments = inherited.segments();
        } else if (readsSegments && accountId != null) {
            AudienceMembershipPort port = audiences.getIfAvailable();
            if (port != null) {
                segments = port.segmentsOf(request.tenantId(), request.brandId(), accountId);
            }
        }

        // The coupon presented, exactly as before: a presented code is re-checked fresh, and an
        // order's own redeemed code is carried without a new eligibility test.
        Set<UUID> presented = new HashSet<>();
        List<Promotion> promotionsOut = new ArrayList<>(pool);
        if (request.presentedCouponCode() != null
                && !request.presentedCouponCode().isBlank()) {
            PromoCodeQueryPort.Eligibility eligibility = promoCodeEligibility.check(
                    request.tenantId(), request.brandId(), request.presentedCouponCode(), accountId, now);
            if (eligibility.isEligible() && eligibility.promotionId() != null) {
                presented.add(eligibility.promotionId());
            }
        }
        if (request.carriedRedemptionOrderId() != null) {
            Optional<JdbcPromoCodeStore.HeldRedemption> held =
                    promoCodes.findRedemptionHeldByOrder(request.tenantId(), request.carriedRedemptionOrderId());
            if (held.isPresent() && held.get().brandId().equals(request.brandId())) {
                UUID heldPromotionId = held.get().promotionId();
                List<Promotion> heldPromotions = promoCodes.promotionsForPricingByIds(
                        request.tenantId(), request.brandId(), List.of(heldPromotionId));
                if (!heldPromotions.isEmpty()) {
                    promotionsOut.removeIf(promotion -> promotion.promotionId().equals(heldPromotionId));
                    heldPromotions.forEach(promotion -> promotionsOut.add(promotion.heldPastItsWindow()));
                    presented.add(heldPromotionId);
                }
            }
        }

        // Usage limits: read at every price, claimed only at checkout. A promotion an amended order
        // already holds is exempt (its slot is already inside the counters), and one it does not hold
        // is reported as never claimed at placement.
        Set<UUID> heldIds = new HashSet<>();
        if (amendment && request.carriedRedemptionOrderId() != null) {
            // A RELEASED row counts too: the order claimed its slot at placement and the slot
            // stays consumed when the promotion stops applying, so the order keeps its
            // entitlement and the row comes back if a later amendment makes it apply again.
            promotions
                    .heldByOrder(request.tenantId(), request.carriedRedemptionOrderId())
                    .forEach(row -> heldIds.add(row.promotionId()));
        }
        Set<UUID> limitReached = new HashSet<>();
        Set<UUID> notClaimed = new HashSet<>();
        if (amendment) {
            for (Promotion promotion : promotionsOut) {
                if (promotion.isLimited() && !heldIds.contains(promotion.promotionId())) {
                    notClaimed.add(promotion.promotionId());
                }
            }
        } else if (promotionsOut.stream().anyMatch(Promotion::isLimited)) {
            limitReached.addAll(promotions.limitReached(request.tenantId(), request.brandId(), accountId));
        }

        Map<UUID, MenuMembershipLookup.Membership> memberships = variantIds.isEmpty()
                ? Map.of()
                : membership.membershipOf(request.tenantId(), request.brandId(), variantIds);

        var context = new PromotionEvaluator.PromotionContext(
                request.channel(),
                channelType,
                request.locationId(),
                fulfillmentMode,
                paymentMethodCode,
                deliveryZoneId,
                firstOrder,
                brandPosition,
                channelPosition,
                segments,
                presented,
                serviceInstant,
                local.getDayOfWeek().getValue(),
                local.get(ChronoField.MINUTE_OF_DAY),
                limitReached,
                notClaimed);

        RecordedPromotionInputs recorded = new RecordedPromotionInputs(
                serviceInstant,
                fulfillmentMode,
                channelType,
                paymentMethodCode,
                deliveryZoneId,
                brandPosition,
                channelPosition,
                firstOrder,
                segments,
                zone.getId(),
                List.of());

        return new Resolved(new PricingEngine.PromotionInputs(promotionsOut, context, memberships), recorded);
    }

    /**
     * The promotions the engine is handed before the coupon is added.
     *
     * <p>A cart: every {@code ACTIVE} promotion of the brand. An amendment: the
     * promotions the order holds, at the definition version recorded on its ledger
     * rows, plus every other active promotion (limited ones are marked "not claimed
     * at placement" by the caller). The simulator adds its candidates and replays
     * recorded versions in place of the current definition.
     */
    private List<Promotion> candidatePool(
            QuoteRequest request, Instant now, @Nullable Overrides overrides, boolean amendment) {
        List<Promotion> active =
                new ArrayList<>(promoCodes.listActivePromotions(request.tenantId(), request.brandId()));

        if (amendment && request.carriedRedemptionOrderId() != null) {
            for (JdbcPromotionStore.LedgerRow held :
                    promotions.heldByOrder(request.tenantId(), request.carriedRedemptionOrderId())) {
                promotionAtVersion(
                                request.tenantId(),
                                request.brandId(),
                                held.promotionId(),
                                held.definitionVersion(),
                                now)
                        .ifPresent(version -> {
                            active.removeIf(promotion -> promotion.promotionId().equals(held.promotionId()));
                            active.add(version);
                        });
            }
        }

        if (overrides != null) {
            overrides
                    .replayedVersions()
                    .forEach((promotionId, version) -> promotionAtVersion(
                                    request.tenantId(), request.brandId(), promotionId, version, now)
                            .ifPresent(replayed -> {
                                active.removeIf(
                                        promotion -> promotion.promotionId().equals(promotionId));
                                active.add(replayed);
                            }));
            int index = 0;
            for (PromotionDefinition candidate : overrides.candidates()) {
                UUID candidateId = UUID.nameUUIDFromBytes(("candidate:" + index++ + ":" + candidate.code()).getBytes());
                active.add(candidate.toPromotion(candidateId, request.tenantId(), request.brandId(), 1, now));
            }
        }
        return active.stream()
                .sorted(java.util.Comparator.comparing(Promotion::promotionId))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** The promotion as it was at {@code version}: the stored definition history, or the current row when it is that version. */
    private Optional<Promotion> promotionAtVersion(
            UUID tenantId, UUID brandId, UUID promotionId, int version, Instant now) {
        List<Promotion> current = promoCodes.promotionsForPricingByIds(tenantId, brandId, List.of(promotionId));
        if (!current.isEmpty() && current.get(0).definitionVersion() == version) {
            return Optional.of(current.get(0));
        }
        Optional<Promotion> recorded = promotions
                .definitionAt(tenantId, brandId, promotionId, version)
                .map(definition -> definition.toPromotion(promotionId, tenantId, brandId, version, now));
        if (recorded.isPresent()) {
            return recorded;
        }
        // A coupon promotion authored before definitions were recorded: the current row is the best evidence left.
        return current.stream().findFirst();
    }

    @SafeVarargs
    private static <T> T firstNonNull(@Nullable T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        throw new IllegalStateException("A last, non-null fallback is required");
    }
}
