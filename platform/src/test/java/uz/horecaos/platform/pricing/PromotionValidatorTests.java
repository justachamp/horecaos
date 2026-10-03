package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.PromotionValidator;
import uz.horecaos.platform.pricing.application.PromotionValidator.Issue;
import uz.horecaos.platform.pricing.application.PromotionValidator.Peer;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ActionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ConditionDefinition;

/**
 * ADR 0140's validator: every refusal is a stable code a marketer can read, and a
 * promotion cannot leave {@code DRAFT} with one.
 *
 * <p>Each test names the one refusal it provokes and also checks that a definition
 * with that single mistake corrected passes, so a validator that refused
 * everything would not satisfy any of them.
 */
class PromotionValidatorTests {

    private static final UUID PIZZA = UUID.randomUUID();
    private static final UUID COLA_VARIANT = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    /** A brand that owns exactly the things above, sells in UZS, and prices every variant. */
    private static final PromotionValidator.References BRAND = new PromotionValidator.References() {
        @Override
        public Set<UUID> knownProducts(Collection<UUID> ids) {
            return keep(ids, PIZZA);
        }

        @Override
        public Set<UUID> knownCategories(Collection<UUID> ids) {
            return keep(ids, PIZZA);
        }

        @Override
        public Set<UUID> knownVariants(Collection<UUID> ids) {
            return keep(ids, COLA_VARIANT);
        }

        @Override
        public Set<UUID> knownZones(Collection<UUID> ids) {
            return keep(ids, ZONE);
        }

        @Override
        public Set<UUID> knownLocations(Collection<UUID> ids) {
            return keep(ids, LOCATION);
        }

        @Override
        public Set<String> knownChannelCodes(Collection<String> codes) {
            return keepStrings(codes, "STOREFRONT");
        }

        @Override
        public Set<String> knownPaymentMethods(Collection<String> codes) {
            return keepStrings(codes, "CLICK", "CASH");
        }

        @Override
        public Set<String> knownAudiences(Collection<String> ids) {
            return Set.of();
        }

        @Override
        public Optional<String> brandCurrency() {
            return Optional.of("UZS");
        }

        @Override
        public Set<UUID> unpricedVariants(Collection<UUID> ids) {
            return Set.of();
        }

        private Set<UUID> keep(Collection<UUID> ids, UUID known) {
            Set<UUID> kept = new HashSet<>(ids);
            kept.retainAll(Set.of(known));
            return kept;
        }

        private Set<String> keepStrings(Collection<String> values, String... known) {
            Set<String> kept = new HashSet<>(values);
            kept.retainAll(Set.of(known));
            return kept;
        }
    };

    private static ActionDefinition percent(long basisPoints) {
        return new ActionDefinition(
                1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, Map.of("basisPoints", basisPoints));
    }

    private static PromotionDefinition order(List<ConditionDefinition> conditions, List<ActionDefinition> actions) {
        return new PromotionDefinition(
                "RULE",
                "A rule",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "group",
                false,
                0,
                false,
                5_000L,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                conditions,
                actions);
    }

    private static List<String> refusals(PromotionDefinition definition, Peer... peers) {
        return PromotionValidator.validate(null, definition, List.of(peers), BRAND).refusals().stream()
                .map(Issue::code)
                .toList();
    }

    private static ConditionDefinition when(Promotion.Condition.Type type, Object... pairs) {
        Map<String, Object> operands = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            operands.put((String) pairs[i], pairs[i + 1]);
        }
        return new ConditionDefinition(1, type, operands);
    }

    @Test
    @DisplayName("a well-formed promotion passes with nothing to say")
    void aWellFormedPromotionPasses() {
        var report = PromotionValidator.validate(null, order(List.of(), List.of(percent(1_000))), List.of(), BRAND);

        assertThat(report.isValid()).isTrue();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("NO_ACTION: a promotion that cannot change a total")
    void noAction() {
        assertThat(refusals(order(List.of(), List.of()))).contains("NO_ACTION");
    }

    @Test
    @DisplayName("ACTION_SCOPE_MISMATCH: an item action on an order promotion, and a markup action on a discount")
    void actionScopeMismatch() {
        var itemActionOnOrder = order(
                List.of(),
                List.of(new ActionDefinition(
                        1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, Map.of("basisPoints", 1_000L))));
        var markupActionOnDiscount = order(
                List.of(),
                List.of(new ActionDefinition(
                        1, Promotion.Action.Type.ITEM_FIXED_MARKUP, Map.of("amountMinor", 1_000L))));

        assertThat(refusals(itemActionOnOrder)).contains("ACTION_SCOPE_MISMATCH");
        assertThat(refusals(markupActionOnDiscount)).contains("ACTION_SCOPE_MISMATCH");
    }

    @Test
    @DisplayName("MARKUP_SCOPE_INVALID, ORDER_MARKUP_NOT_AVAILABLE and EXCLUSIVE_WITH_MARKUP")
    void markupRules() {
        ActionDefinition markup =
                new ActionDefinition(1, Promotion.Action.Type.ITEM_PERCENTAGE_MARKUP, Map.of("basisPoints", 1_000L));
        PromotionDefinition itemMarkup = markupOf(Promotion.Scope.ITEM, false, markup);

        assertThat(refusals(itemMarkup))
                .as("an item markup is the one that can be authored")
                .isEmpty();
        assertThat(refusals(markupOf(Promotion.Scope.DELIVERY, false, markup))).contains("MARKUP_SCOPE_INVALID");
        assertThat(refusals(markupOf(Promotion.Scope.ORDER, false, markup)))
                .as("an order-level markup waits on the fiscal answer")
                .contains("ORDER_MARKUP_NOT_AVAILABLE");
        assertThat(refusals(markupOf(Promotion.Scope.ITEM, true, markup))).contains("EXCLUSIVE_WITH_MARKUP");
    }

    private static PromotionDefinition markupOf(Promotion.Scope scope, boolean exclusive, ActionDefinition action) {
        return new PromotionDefinition(
                "MARKUP",
                "A markup",
                Promotion.Kind.MARKUP,
                scope,
                "markup",
                exclusive,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(),
                List.of(action));
    }

    @Test
    @DisplayName(
            "STACKING_GROUP_MIXES_SCOPES: an item promotion in a group that holds an order promotion, and not otherwise")
    void stackingGroupMixesScopes() {
        PromotionDefinition item = new PromotionDefinition(
                "ITEM",
                "Item",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ITEM,
                "group",
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(when(Promotion.Condition.Type.CATEGORY, "categoryIds", List.of(PIZZA.toString()))),
                List.of(new ActionDefinition(
                        1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, Map.of("basisPoints", 1_000L))));
        Peer orderPeer = new Peer(UUID.randomUUID(), "group", Promotion.Kind.DISCOUNT, Promotion.Scope.ORDER);
        Peer deliveryPeer = new Peer(UUID.randomUUID(), "group", Promotion.Kind.DISCOUNT, Promotion.Scope.DELIVERY);
        Peer itemPeer = new Peer(UUID.randomUUID(), "group", Promotion.Kind.DISCOUNT, Promotion.Scope.ITEM);
        Peer elsewhere = new Peer(UUID.randomUUID(), "other", Promotion.Kind.DISCOUNT, Promotion.Scope.ORDER);
        Peer markupPeer = new Peer(UUID.randomUUID(), "group", Promotion.Kind.MARKUP, Promotion.Scope.ITEM);

        assertThat(refusals(item, orderPeer)).contains("STACKING_GROUP_MIXES_SCOPES");
        assertThat(refusals(item, deliveryPeer)).contains("STACKING_GROUP_MIXES_SCOPES");
        assertThat(refusals(item, itemPeer)).isEmpty();
        assertThat(refusals(item, elsewhere)).isEmpty();
        assertThat(refusals(item, markupPeer))
                .as("markups contest on their own")
                .isEmpty();
    }

    @Test
    @DisplayName("PERCENTAGE_OVER_100 and OPERAND_INVALID name the action they concern")
    void percentagesAndOperands() {
        var over = PromotionValidator.validate(null, order(List.of(), List.of(percent(10_001))), List.of(), BRAND);
        var zero = PromotionValidator.validate(null, order(List.of(), List.of(percent(0))), List.of(), BRAND);

        assertThat(over.refusals()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("PERCENTAGE_OVER_100");
            assertThat(issue.sequence()).isEqualTo(1);
        });
        assertThat(zero.refusals()).extracting(Issue::code).contains("OPERAND_INVALID");
        assertThat(refusals(order(List.of(), List.of(percent(10_000))))).isEmpty();
    }

    @Test
    @DisplayName("FREE_ITEM_UNBOUNDED: the gift needs a quantity, and PER_MULTIPLE a trigger")
    void freeItemMustBeBounded() {
        PromotionDefinition gift = new PromotionDefinition(
                "GIFT",
                "Gift",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ITEM,
                "gift",
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(when(Promotion.Condition.Type.CATEGORY, "categoryIds", List.of(PIZZA.toString()))),
                List.of(new ActionDefinition(
                        1, Promotion.Action.Type.FREE_ITEM, Map.of("variantIds", List.of(COLA_VARIANT.toString())))));
        PromotionDefinition bounded = new PromotionDefinition(
                gift.code(),
                gift.name(),
                gift.kind(),
                gift.scope(),
                gift.stackingGroup(),
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                gift.loyaltyAccrual(),
                gift.loyaltyRedemption(),
                gift.conditions(),
                List.of(new ActionDefinition(
                        1,
                        Promotion.Action.Type.FREE_ITEM,
                        Map.of(
                                "variantIds",
                                List.of(COLA_VARIANT.toString()),
                                "quantity",
                                1L,
                                "triggerQuantity",
                                2L,
                                "mode",
                                "PER_MULTIPLE"))));

        assertThat(refusals(gift)).contains("FREE_ITEM_UNBOUNDED");
        assertThat(refusals(bounded)).isEmpty();
    }

    @Test
    @DisplayName(
            "UNKNOWN_REFERENCE: a product, category, variant, zone, channel, location, payment method or audience of someone else's")
    void unknownReferences() {
        UUID stranger = UUID.randomUUID();
        assertThat(refusals(order(
                        List.of(when(Promotion.Condition.Type.PRODUCT, "productIds", List.of(stranger.toString()))),
                        List.of(percent(1_000)))))
                .contains("UNKNOWN_REFERENCE");
        assertThat(refusals(order(
                        List.of(when(Promotion.Condition.Type.DELIVERY_ZONE, "zoneIds", List.of(stranger.toString()))),
                        List.of(percent(1_000)))))
                .contains("UNKNOWN_REFERENCE");
        assertThat(refusals(order(
                        List.of(when(Promotion.Condition.Type.LOCATION, "locationIds", List.of(stranger.toString()))),
                        List.of(percent(1_000)))))
                .contains("UNKNOWN_REFERENCE");
        assertThat(refusals(order(
                        List.of(when(Promotion.Condition.Type.CHANNEL, "channels", List.of("NOPE"))),
                        List.of(percent(1_000)))))
                .contains("UNKNOWN_REFERENCE");
        assertThat(refusals(order(
                        List.of(when(
                                Promotion.Condition.Type.PAYMENT_METHOD, "paymentMethodCodes", List.of("BITCOIN"))),
                        List.of(percent(1_000)))))
                .contains("UNKNOWN_REFERENCE");
        assertThat(refusals(order(
                        List.of(when(Promotion.Condition.Type.CUSTOMER_SEGMENT, "segments", List.of("nobody"))),
                        List.of(percent(1_000)))))
                .contains("UNKNOWN_REFERENCE");

        assertThat(refusals(order(
                        List.of(
                                when(Promotion.Condition.Type.DELIVERY_ZONE, "zoneIds", List.of(ZONE.toString())),
                                when(Promotion.Condition.Type.PAYMENT_METHOD, "paymentMethodCodes", List.of("CLICK"))),
                        List.of(percent(1_000)))))
                .as("the brand's own references pass")
                .isEmpty();
    }

    @Test
    @DisplayName("WINDOW_INVERTED and WEEKDAYS_EMPTY")
    void windowsAndWeekdays() {
        PromotionDefinition inverted = new PromotionDefinition(
                "RULE",
                "A rule",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "group",
                false,
                0,
                false,
                null,
                "UZS",
                Instant.parse("2026-10-02T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"),
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(),
                List.of(percent(1_000)));

        assertThat(refusals(inverted)).contains("WINDOW_INVERTED");
        assertThat(refusals(order(
                        List.of(when(
                                Promotion.Condition.Type.TIME_OF_DAY, "fromMinuteOfDay", 600L, "toMinuteOfDay", 600L)),
                        List.of(percent(1_000)))))
                .as("a zero-length intra-day window never matches")
                .contains("WINDOW_INVERTED");
        assertThat(refusals(order(
                        List.of(when(
                                Promotion.Condition.Type.TIME_OF_DAY,
                                "fromMinuteOfDay",
                                22 * 60L,
                                "toMinuteOfDay",
                                2 * 60L)),
                        List.of(percent(1_000)))))
                .as("22:00 to 02:00 wraps past midnight and is legal")
                .isEmpty();
        assertThat(refusals(order(
                        List.of(when(Promotion.Condition.Type.DAY_OF_WEEK, "daysOfWeek", List.of())),
                        List.of(percent(1_000)))))
                .contains("WEEKDAYS_EMPTY");
    }

    @Test
    @DisplayName("CURRENCY_MISMATCH against the brand's price book")
    void currencyMismatch() {
        PromotionDefinition dollars = new PromotionDefinition(
                "RULE",
                "A rule",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "group",
                false,
                0,
                false,
                null,
                "USD",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(),
                List.of(percent(1_000)));

        assertThat(refusals(dollars)).contains("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("LIMIT_ON_COUPON_PROMOTION and SEQUENCE_NEEDS_CUSTOMER_LIMIT")
    void limitRules() {
        PromotionDefinition couponWithLimit = new PromotionDefinition(
                "RULE",
                "A rule",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "group",
                false,
                0,
                true,
                null,
                "UZS",
                null,
                null,
                100,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(),
                List.of(percent(1_000)));
        ConditionDefinition first = when(Promotion.Condition.Type.ORDER_SEQUENCE, "mode", "FIRST", "basis", "BRAND");
        PromotionDefinition firstWithoutLimit = order(List.of(first), List.of(percent(1_000)));
        PromotionDefinition firstWithLimit = new PromotionDefinition(
                "RULE",
                "A rule",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "group",
                false,
                0,
                false,
                5_000L,
                "UZS",
                null,
                null,
                null,
                1,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(first),
                List.of(percent(1_000)));

        assertThat(refusals(couponWithLimit)).contains("LIMIT_ON_COUPON_PROMOTION");
        assertThat(refusals(firstWithoutLimit)).contains("SEQUENCE_NEEDS_CUSTOMER_LIMIT");
        assertThat(refusals(firstWithLimit)).isEmpty();
    }

    @Test
    @DisplayName("warnings do not refuse: an uncapped percentage, and a variant nothing prices")
    void warningsDoNotRefuse() {
        PromotionDefinition uncapped = new PromotionDefinition(
                "RULE",
                "A rule",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "group",
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(),
                List.of(percent(1_000)));

        var report = PromotionValidator.validate(null, uncapped, List.of(), BRAND);

        assertThat(report.isValid()).isTrue();
        assertThat(report.warnings()).extracting(Issue::code).containsExactly("UNCAPPED_PERCENTAGE");
    }
}
