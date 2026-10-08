package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Context;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Injection;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Offer;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Outcome;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Skipped;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Band;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Kind;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Mode;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Trigger;

/**
 * ADR 0158's one pure function, over literals: each kind, the edges of a band, a step at the
 * threshold and one unit below it, {@code repeat_per_multiple}, clamping, a zero raw quantity, the
 * context filter, and the proof that an injected line can never trigger a rule.
 */
class AutoAddEvaluatorTests {

    private static final UUID BURGER = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID PLOV = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID COLA = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID BAG = UUID.fromString("00000000-0000-7000-8000-000000000010");
    private static final UUID CUTLERY = UUID.fromString("00000000-0000-7000-8000-000000000011");
    private static final UUID NAPKIN = UUID.fromString("00000000-0000-7000-8000-000000000012");
    private static final UUID BURGER_PRODUCT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID MAINS = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID LOCATION = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID OTHER_LOCATION = UUID.fromString("00000000-0000-7000-8000-0000000000f2");

    private static final Context DELIVERY_ON_WEB = new Context("DELIVERY", "WEB", LOCATION);

    @Test
    @DisplayName("a plain rule adds its fixed quantity whenever the context matches")
    void aPlainRuleAddsItsQuantity() {
        PublishedAutoAddRule rule = plain("cutlery", CUTLERY, "1");

        List<Outcome> outcomes = evaluate(List.of(rule), basket(line(BURGER, "1")), DELIVERY_ON_WEB);

        assertThat(outcomes).singleElement().isInstanceOfSatisfying(Injection.class, injection -> {
            assertThat(injection.variantId()).isEqualTo(CUTLERY);
            assertThat(injection.quantity()).isEqualByComparingTo("1");
            assertThat(injection.rule().ruleId()).isEqualTo(rule.ruleId());
        });
    }

    @Test
    @DisplayName("a rule applies only to its fulfilment modes, its channel types and its locations")
    void theContextFiltersRules() {
        PublishedAutoAddRule rule = new Builder("bag", Kind.PLAIN)
                .variant(BAG)
                .quantity("1")
                .modes("DELIVERY")
                .channels("WEB", "IOS")
                .locations(LOCATION)
                .build();
        BasketFacts basket = basket(line(BURGER, "1"));

        assertThat(evaluate(List.of(rule), basket, DELIVERY_ON_WEB)).hasSize(1);
        assertThat(evaluate(List.of(rule), basket, new Context("PICKUP", "WEB", LOCATION)))
                .as("a pickup order")
                .isEmpty();
        assertThat(evaluate(List.of(rule), basket, new Context("DELIVERY", "TELEGRAM", LOCATION)))
                .as("a channel type the rule does not name")
                .isEmpty();
        assertThat(evaluate(List.of(rule), basket, new Context("DELIVERY", "WEB", OTHER_LOCATION)))
                .as("another location of the brand")
                .isEmpty();
        assertThat(evaluate(List.of(rule), basket, new Context("DELIVERY", "WEB", null)))
                .as("no location at all cannot satisfy a location rule")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule with no location rows applies at every location")
    void noLocationRowsMeansEveryLocation() {
        PublishedAutoAddRule rule = plain("bag", BAG, "1");

        assertThat(evaluate(List.of(rule), basket(line(BURGER, "1")), new Context("DELIVERY", "WEB", OTHER_LOCATION)))
                .hasSize(1);
    }

    @Test
    @DisplayName("a product-triggered rule fires once at the threshold and not one unit below it")
    void aStepAtTheThresholdAndOneBelow() {
        PublishedAutoAddRule rule =
                triggered("bag-per-order", BAG, "1", "3", false, new Trigger(null, BURGER_PRODUCT, null));

        assertThat(evaluate(List.of(rule), basket(line(BURGER, "2")), DELIVERY_ON_WEB))
                .as("two units of a three-unit step")
                .isEmpty();
        assertThat(evaluate(List.of(rule), basket(line(BURGER, "3")), DELIVERY_ON_WEB))
                .singleElement()
                .isInstanceOfSatisfying(
                        Injection.class,
                        injection -> assertThat(injection.quantity()).isEqualByComparingTo("1"));
        assertThat(evaluate(List.of(rule), basket(line(BURGER, "7")), DELIVERY_ON_WEB))
                .as("without repeat_per_multiple seven units are still one step")
                .singleElement()
                .isInstanceOfSatisfying(
                        Injection.class,
                        injection -> assertThat(injection.quantity()).isEqualByComparingTo("1"));
    }

    @Test
    @DisplayName("repeat_per_multiple adds one quantity per whole multiple of the threshold")
    void repeatPerMultipleCountsWholeSteps() {
        PublishedAutoAddRule rule = triggered("box-per-two", BAG, "1", "2", true, new Trigger(BURGER, null, null));

        assertThat(quantityOf(evaluate(List.of(rule), basket(line(BURGER, "1")), DELIVERY_ON_WEB)))
                .as("one unit is not a step")
                .isNull();
        assertThat(quantityOf(evaluate(List.of(rule), basket(line(BURGER, "2")), DELIVERY_ON_WEB)))
                .isEqualByComparingTo("1");
        assertThat(quantityOf(evaluate(List.of(rule), basket(line(BURGER, "5")), DELIVERY_ON_WEB)))
                .as("floor(5 / 2) = 2 steps")
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a trigger node may be a variant, a product or a category, and units from all of them add up")
    void triggerNodesCountTheirUnitsTogether() {
        PublishedAutoAddRule rule =
                triggered("bag", BAG, "1", "3", false, new Trigger(PLOV, null, null), new Trigger(null, null, MAINS));
        BasketFacts basket = new BasketFacts(List.of(
                BasketFacts.Line.of(PLOV, null, Set.of(), new BigDecimal("1"), null),
                BasketFacts.Line.of(BURGER, BURGER_PRODUCT, Set.of(MAINS), new BigDecimal("2"), null),
                BasketFacts.Line.of(COLA, null, Set.of(), new BigDecimal("9"), null)));

        assertThat(evaluate(List.of(rule), basket, DELIVERY_ON_WEB))
                .as("1 plov + 2 mains = 3 trigger units; the cola is not a trigger")
                .hasSize(1);
    }

    @Test
    @DisplayName("an injected line never triggers a rule, so rules cannot chain")
    void anInjectedLineCannotTriggerAnotherRule() {
        // The cart builds BasketFacts from the customer's own lines only. Here a rule injects a
        // bag and a second rule is triggered by bags: the second must stay silent because the
        // basket it is evaluated against holds no bag.
        PublishedAutoAddRule addsTheBag = plain("bag", BAG, "3");
        PublishedAutoAddRule triggeredByBags =
                triggered("napkins", NAPKIN, "1", "1", false, new Trigger(BAG, null, null));
        BasketFacts customersLinesOnly = basket(line(BURGER, "1"));

        List<Outcome> outcomes = evaluate(List.of(addsTheBag, triggeredByBags), customersLinesOnly, DELIVERY_ON_WEB);

        assertThat(outcomes)
                .extracting(outcome -> outcome.rule().code())
                .as("only the plain rule fired; the one waiting for a bag did not")
                .containsExactly("bag");
    }

    @Test
    @DisplayName("a portion band takes the variant and quantity of the band that holds the total")
    void aPortionBandMatchesTheTotalPortions() {
        PublishedAutoAddRule rule = bands(
                "cutlery-sets",
                new Band(new BigDecimal("0"), new BigDecimal("2"), CUTLERY, new BigDecimal("1")),
                new Band(new BigDecimal("2"), new BigDecimal("5"), CUTLERY, new BigDecimal("2")),
                new Band(new BigDecimal("5"), null, NAPKIN, new BigDecimal("4")));

        assertThat(single(evaluate(List.of(rule), portions("1"), DELIVERY_ON_WEB)))
                .isInstanceOfSatisfying(Injection.class, injection -> {
                    assertThat(injection.variantId()).isEqualTo(CUTLERY);
                    assertThat(injection.quantity()).isEqualByComparingTo("1");
                });
        assertThat(quantityOf(evaluate(List.of(rule), portions("2"), DELIVERY_ON_WEB)))
                .as("the lower edge is inclusive: 2 portions is the second band")
                .isEqualByComparingTo("2");
        assertThat(quantityOf(evaluate(List.of(rule), portions("4.999"), DELIVERY_ON_WEB)))
                .as("the upper edge is exclusive")
                .isEqualByComparingTo("2");
        assertThat(single(evaluate(List.of(rule), portions("5"), DELIVERY_ON_WEB)))
                .isInstanceOfSatisfying(Injection.class, injection -> {
                    assertThat(injection.variantId())
                            .as("the open-ended band names another variant")
                            .isEqualTo(NAPKIN);
                    assertThat(injection.quantity()).isEqualByComparingTo("4");
                });
        assertThat(quantityOf(evaluate(List.of(rule), portions("500"), DELIVERY_ON_WEB)))
                .as("clamped to the rule's maximum of twenty")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a portion band never fires on a basket that counts no portions, even one that starts at zero")
    void aBasketOfNoPortionsFiresNoBand() {
        PublishedAutoAddRule rule = bands("cutlery-sets", new Band(BigDecimal.ZERO, null, CUTLERY, BigDecimal.ONE));
        BasketFacts blankPortions =
                new BasketFacts(List.of(BasketFacts.Line.of(COLA, null, Set.of(), new BigDecimal("3"), null)));

        assertThat(evaluate(List.of(rule), blankPortions, DELIVERY_ON_WEB))
                .as("a blank portions_per_unit counts as zero, and zero portions fire nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a gap between bands injects nothing for a total that falls in it")
    void aGapInjectsNothing() {
        PublishedAutoAddRule rule = bands(
                "cutlery-sets",
                new Band(new BigDecimal("1"), new BigDecimal("2"), CUTLERY, BigDecimal.ONE),
                new Band(new BigDecimal("4"), null, CUTLERY, new BigDecimal("2")));

        assertThat(evaluate(List.of(rule), portions("3"), DELIVERY_ON_WEB)).isEmpty();
    }

    @Test
    @DisplayName("portions are the sum of each line's units times what one unit is worth")
    void portionsAreUnitsTimesWhatOneUnitIsWorth() {
        BasketFacts basket = new BasketFacts(List.of(
                BasketFacts.Line.of(BURGER, null, Set.of(), new BigDecimal("2"), new BigDecimal("1.5")),
                BasketFacts.Line.of(PLOV, null, Set.of(), new BigDecimal("0.5"), new BigDecimal("2"))));

        assertThat(basket.totalPortions()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("the quantity is clamped into the rule's minimum and maximum")
    void theQuantityIsClamped() {
        PublishedAutoAddRule low = new Builder("low", Kind.PLAIN)
                .variant(BAG)
                .quantity("1")
                .bounds("2", "5")
                .build();
        PublishedAutoAddRule high = new Builder("high", Kind.PLAIN)
                .variant(CUTLERY)
                .quantity("9")
                .bounds("1", "5")
                .build();

        assertThat(evaluate(List.of(low, high), basket(line(BURGER, "1")), DELIVERY_ON_WEB))
                .extracting(outcome -> ((Injection) outcome).quantity())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("5"), new BigDecimal("2"));
    }

    @Test
    @DisplayName("a zero raw quantity means the rule does not fire, whatever the minimum is")
    void aZeroRawQuantityDoesNotFire() {
        PublishedAutoAddRule rule = triggered("bag", BAG, "1", "10", true, new Trigger(BURGER, null, null));

        assertThat(evaluate(List.of(rule), basket(line(BURGER, "9")), DELIVERY_ON_WEB))
                .as("floor(9 / 10) = 0 steps; the minimum of one must not turn that into a line")
                .isEmpty();
    }

    @Test
    @DisplayName("an OFFER rule yields an offer and writes nothing")
    void anOfferRuleYieldsAnOffer() {
        PublishedAutoAddRule rule = new Builder("gift-napkins", Kind.PLAIN)
                .variant(NAPKIN)
                .quantity("2")
                .mode(Mode.OFFER)
                .build();

        assertThat(evaluate(List.of(rule), basket(line(BURGER, "1")), DELIVERY_ON_WEB))
                .singleElement()
                .isInstanceOf(Offer.class);
    }

    @Test
    @DisplayName("a rule the customer declined is skipped with DECLINED, and so is one whose variant cannot be sold")
    void declinedAndUnsellableRulesAreSkipped() {
        PublishedAutoAddRule declined = plain("declined", BAG, "1");
        PublishedAutoAddRule unsellable = plain("unsellable", CUTLERY, "1");

        List<Outcome> outcomes = AutoAddEvaluator.evaluate(
                List.of(declined, unsellable),
                basket(line(BURGER, "1")),
                DELIVERY_ON_WEB,
                Set.of(declined.ruleId()),
                (rule, variantId) -> variantId.equals(CUTLERY) ? Optional.of("SOLD_OUT") : Optional.empty());

        assertThat(outcomes)
                .extracting(outcome -> ((Skipped) outcome).reason())
                .containsExactlyInAnyOrder("DECLINED", "SOLD_OUT");
    }

    @Test
    @DisplayName("the same basket always yields the same list, ordered by rule code")
    void theOrderIsDeterministic() {
        List<PublishedAutoAddRule> rules = List.of(plain("zeta", BAG, "1"), plain("alpha", CUTLERY, "1"));

        assertThat(evaluate(rules, basket(line(BURGER, "1")), DELIVERY_ON_WEB))
                .extracting(outcome -> outcome.rule().code())
                .containsExactly("alpha", "zeta");
    }

    // ------------------------------------------------------------------------------ fixtures

    private static List<Outcome> evaluate(List<PublishedAutoAddRule> rules, BasketFacts basket, Context context) {
        return AutoAddEvaluator.evaluate(rules, basket, context, Set.of(), AutoAddEvaluator.Eligibility.ALL);
    }

    private static @Nullable BigDecimal quantityOf(List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return null;
        }
        return ((Injection) outcomes.get(0)).quantity();
    }

    private static Outcome single(List<Outcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.get(0);
    }

    private static BasketFacts basket(BasketFacts.Line... lines) {
        return new BasketFacts(List.of(lines));
    }

    private static BasketFacts.Line line(UUID variant, String units) {
        return BasketFacts.Line.of(variant, BURGER_PRODUCT, Set.of(MAINS), new BigDecimal(units), null);
    }

    /** One line worth {@code portions} portions in total. */
    private static BasketFacts portions(String portions) {
        return new BasketFacts(
                List.of(BasketFacts.Line.of(PLOV, null, Set.of(), BigDecimal.ONE, new BigDecimal(portions))));
    }

    private static PublishedAutoAddRule plain(String code, UUID variant, String quantity) {
        return new Builder(code, Kind.PLAIN).variant(variant).quantity(quantity).build();
    }

    private static PublishedAutoAddRule triggered(
            String code, UUID variant, String quantity, String threshold, boolean repeat, Trigger... triggers) {
        return new Builder(code, Kind.PRODUCT_TRIGGERED)
                .variant(variant)
                .quantity(quantity)
                .threshold(threshold)
                .repeat(repeat)
                .triggers(triggers)
                .build();
    }

    private static PublishedAutoAddRule bands(String code, Band... bands) {
        return new Builder(code, Kind.PORTION_BAND).bands(bands).build();
    }

    /** A rule on every fulfilment mode, every cart-built channel type and every location unless narrowed. */
    private static final class Builder {
        private final String code;
        private final Kind kind;
        private Mode mode = Mode.INJECT;
        private @Nullable UUID variant;
        private @Nullable BigDecimal quantity;
        private @Nullable BigDecimal threshold;
        private boolean repeat;
        private BigDecimal minimum = BigDecimal.ONE;
        private BigDecimal maximum = new BigDecimal("20");
        private Set<String> modes = Set.of("DELIVERY", "PICKUP", "DINE_IN");
        private Set<String> channels = Set.of("WEB", "IOS", "ANDROID", "TELEGRAM", "KIOSK", "QR_TABLE", "CALL_CENTRE");
        private Set<UUID> locations = Set.of();
        private List<Trigger> triggers = List.of();
        private List<Band> bands = List.of();

        Builder(String code, Kind kind) {
            this.code = code;
            this.kind = kind;
        }

        Builder mode(Mode value) {
            this.mode = value;
            return this;
        }

        Builder variant(UUID value) {
            this.variant = value;
            return this;
        }

        Builder quantity(String value) {
            this.quantity = new BigDecimal(value);
            return this;
        }

        Builder threshold(String value) {
            this.threshold = new BigDecimal(value);
            return this;
        }

        Builder repeat(boolean value) {
            this.repeat = value;
            return this;
        }

        Builder bounds(String min, String max) {
            this.minimum = new BigDecimal(min);
            this.maximum = new BigDecimal(max);
            return this;
        }

        Builder modes(String... values) {
            this.modes = Set.of(values);
            return this;
        }

        Builder channels(String... values) {
            this.channels = Set.of(values);
            return this;
        }

        Builder locations(UUID... values) {
            this.locations = Set.of(values);
            return this;
        }

        Builder triggers(Trigger... values) {
            this.triggers = List.of(values);
            return this;
        }

        Builder bands(Band... values) {
            this.bands = List.of(values);
            return this;
        }

        PublishedAutoAddRule build() {
            return new PublishedAutoAddRule(
                    UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    1,
                    code,
                    kind,
                    mode,
                    variant,
                    quantity,
                    threshold,
                    repeat,
                    minimum,
                    maximum,
                    false,
                    modes,
                    channels,
                    locations,
                    triggers,
                    bands,
                    Map.of("en", code));
        }
    }
}
