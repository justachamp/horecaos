package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.CompositePricing.ComboComponentFact;
import uz.horecaos.platform.pricing.application.CompositePricing.ComboGroupFact;
import uz.horecaos.platform.pricing.application.CompositePricing.CompositeInputs;
import uz.horecaos.platform.pricing.application.CompositePricing.CompositeSelectionException;
import uz.horecaos.platform.pricing.application.CompositePricing.HiddenCharge;
import uz.horecaos.platform.pricing.application.CompositePricing.NestedGroupFact;
import uz.horecaos.platform.pricing.application.CompositePricing.OptionFact;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.PricingInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * ADR 0136's pricing rules, on literals: a combo becomes ordinary lines at the
 * component price map, a hidden auto-selected modifier is charged on the order types
 * it names, and a second level of modifiers is priced and bounded.
 *
 * <p>Every number here can be checked by hand, because the rule being proved is that
 * a combo is arithmetically indistinguishable from ordering its components
 * separately -- and the way to prove that is to say what the separate orders cost.
 */
class CompositePricingEngineTests {

    private static final UUID TENANT = uuid(0xa1);
    private static final UUID BRAND = uuid(0xb1);
    private static final UUID LOCATION = uuid(0xc1);
    private static final UUID PUBLICATION = uuid(0xd1);
    private static final UUID PRICE_BOOK = uuid(0xd2);
    private static final UUID TAX_PROFILE = uuid(0xd3);
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    // The sellable combos. Containers: never priced.
    private static final UUID LUNCH = uuid(0x10);
    private static final UUID FAMILY = uuid(0x11);

    // Real dishes and drinks.
    private static final UUID BURGER = uuid(0x20);
    private static final UUID WRAP = uuid(0x21);
    private static final UUID FRIES = uuid(0x22);
    private static final UUID WINGS = uuid(0x23);
    private static final UUID COLA = uuid(0x24);

    // Groups of LUNCH, in the order a customer meets them.
    private static final UUID MAIN_GROUP = uuid(0x30);
    private static final UUID SIDE_GROUP = uuid(0x31);
    private static final UUID DRINK_GROUP = uuid(0x32);
    private static final UUID FAMILY_DRINK_GROUP = uuid(0x33);

    // Pairings: the keys of the price map.
    private static final UUID BURGER_IN_LUNCH = uuid(0x40);
    private static final UUID WRAP_IN_LUNCH = uuid(0x41);
    private static final UUID FRIES_IN_LUNCH = uuid(0x42);
    private static final UUID WINGS_IN_LUNCH = uuid(0x43);
    private static final UUID COLA_IN_LUNCH = uuid(0x44);
    private static final UUID COLA_IN_FAMILY = uuid(0x45);

    private static final UUID BOX_GROUP = uuid(0x50);
    private static final UUID BOX_OPTION = uuid(0x51);
    private static final UUID FREE_TAG_GROUP = uuid(0x52);
    private static final UUID FREE_TAG_OPTION = uuid(0x53);

    // Nested modifiers: the option "Add a side" links the variant SIDE_MEAL, which offers dips.
    private static final UUID ADD_SIDE_OPTION = uuid(0x60);
    private static final UUID SIDE_MEAL = uuid(0x61);
    private static final UUID DIP_GROUP = uuid(0x62);
    private static final UUID KETCHUP_OPTION = uuid(0x63);
    private static final UUID MAYO_OPTION = uuid(0x64);
    private static final UUID SPICY_GROUP = uuid(0x65);
    private static final UUID SPICY_OPTION = uuid(0x66);
    private static final UUID PLAIN_OPTION = uuid(0x67);
    private static final UUID NESTED_DEEPER_OPTION = uuid(0x68);
    private static final UUID SELECTION = uuid(0x70);

    private final PricingEngine engine = new PricingEngine();

    // ------------------------------------------------------------------ combos

    @Test
    @DisplayName("a combo becomes one ordinary line per picked component, each at its own price")
    void aComboBecomesItsComponents() {
        var request = request(line("lunch-1", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        assertThat(result.lines()).hasSize(2);
        Quote.QuoteLine burger = result.lines().get(0);
        Quote.QuoteLine cola = result.lines().get(1);
        assertThat(burger.lineId()).isEqualTo("lunch-1~1");
        assertThat(burger.variantId()).isEqualTo(BURGER);
        assertThat(burger.unitAmount().minor()).isEqualTo(30_000L);
        assertThat(cola.lineId()).isEqualTo("lunch-1~2");
        assertThat(cola.variantId()).isEqualTo(COLA);
        assertThat(cola.unitAmount().minor()).isEqualTo(3_000L);
        assertThat(result.lines())
                .as("one purchase, one grouping key, and the container named on every component")
                .allSatisfy(component -> {
                    assertThat(component.comboSelectionId()).isEqualTo(SELECTION);
                    assertThat(component.comboContainerVariantId()).isEqualTo(LUNCH);
                });
        assertThat(result.lines())
                .extracting(Quote.QuoteLine::variantId)
                .as("the container itself is never a line")
                .doesNotContain(LUNCH);
        assertThat(result.total().minor())
                .as("the same money as ordering a burger and a cola separately at those prices")
                .isEqualTo(33_000L);
    }

    @Test
    @DisplayName("a combo's total reconciles like any other: subtotal + tax - discount + fee, lines summing to it")
    void aComboReconcilesLikeAnyOtherOrder() {
        var request = request(
                line("lunch-1", LUNCH, 2, pick(WRAP_IN_LUNCH, 1), pick(FRIES_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        long lines = result.lines().stream()
                .mapToLong(line -> line.finalAmount().minor())
                .sum();
        assertThat(lines).isEqualTo(2 * (28_000L + 8_000L + 3_000L));
        assertThat(result.total().minor()).isEqualTo(lines);
        assertThat(result.subtotal().minor()
                        + result.tax().minor()
                        + result.fees().minor()
                        - result.discount().minor())
                .as("ck_order_total_reconciles holds with no change to it (ADR 0072's lesson)")
                .isEqualTo(result.total().minor());
        assertThat(result.lines().stream()
                        .mapToLong(line -> line.taxAmount().minor())
                        .sum())
                .as("each component carries a tax share, and the shares are the total tax")
                .isEqualTo(result.tax().minor());
    }

    @Test
    @DisplayName("a component's quantity is the line quantity times its default quantity times how often it was picked")
    void quantitiesMultiply() {
        // 2 lunch boxes x 6 wings per pick x 2 picks of the wings = 24 wings, priced per wing.
        var request = request(
                line("lunch-1", LUNCH, 2, pick(BURGER_IN_LUNCH, 1), pick(WINGS_IN_LUNCH, 2), pick(COLA_IN_LUNCH, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        Quote.QuoteLine wings = result.lines().stream()
                .filter(line -> WINGS.equals(line.variantId()))
                .findFirst()
                .orElseThrow();
        assertThat(wings.quantity()).isEqualByComparingTo("24");
        assertThat(wings.unitAmount().minor()).isEqualTo(2_000L);
        assertThat(wings.finalAmount().minor()).isEqualTo(48_000L);
    }

    @Test
    @DisplayName("the same drink sits in two combos at two prices, because the price is keyed to the pairing")
    void theSameVariantHasADifferentPriceInEachCombo() {
        var request = request(
                line("lunch-1", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)),
                line("family-1", FAMILY, 1, pick(COLA_IN_FAMILY, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        List<Quote.QuoteLine> colas = result.lines().stream()
                .filter(line -> COLA.equals(line.variantId()))
                .toList();
        assertThat(colas).extracting(line -> line.unitAmount().minor()).containsExactly(3_000L, 0L);
        assertThat(colas)
                .extracting(Quote.QuoteLine::comboContainerVariantId)
                .as("and each is a component of its own combo")
                .containsExactly(LUNCH, FAMILY);
    }

    @Test
    @DisplayName("the same selection prices to the same lines, adjustments and hash however the picks are listed")
    void pricingACombosSelectionIsDeterministic() {
        var forward = request(
                line("lunch-1", LUNCH, 1, pick(COLA_IN_LUNCH, 1), pick(BURGER_IN_LUNCH, 1), pick(FRIES_IN_LUNCH, 1)));
        var shuffled = request(
                line("lunch-1", LUNCH, 1, pick(FRIES_IN_LUNCH, 1), pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)));
        var split = request(
                line("lunch-1", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1), pick(FRIES_IN_LUNCH, 1)));

        var first = engine.price(forward, inputs(world()), NOW);

        assertThat(engine.price(shuffled, inputs(world()), NOW))
                .as("the order a client lists picks in is not an input")
                .isEqualTo(first);
        assertThat(engine.price(split, inputs(world()), NOW)).isEqualTo(first);
        assertThat(engine.price(forward, inputs(world()), NOW))
                .as("and a second run over the same request is the same answer")
                .isEqualTo(first);
        assertThat(first.lines())
                .extracting(Quote.QuoteLine::variantId)
                .as("lines come out in group, then component, order -- not request order")
                .containsExactly(BURGER, FRIES, COLA);
    }

    @Test
    @DisplayName("the same component picked twice in a request is one pick of two")
    void duplicatePicksMerge() {
        var request = request(line(
                "lunch-1",
                LUNCH,
                1,
                pick(BURGER_IN_LUNCH, 1),
                pick(FRIES_IN_LUNCH, 1),
                pick(FRIES_IN_LUNCH, 1),
                pick(COLA_IN_LUNCH, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        assertThat(result.lines().stream().filter(line -> FRIES.equals(line.variantId())))
                .hasSize(1)
                .first()
                .extracting(Quote.QuoteLine::quantity)
                .satisfies(quantity -> assertThat(quantity).isEqualByComparingTo("2"));
    }

    @Test
    @DisplayName("a component with no COMBO_COMPONENT price refuses the cart, naming the pairing")
    void anUnpricedComponentRefusesTheCart() {
        World world = world();
        world.componentPrices.remove(WRAP_IN_LUNCH);

        Throwable refused = catchThrowable(() -> engine.price(
                request(line("lunch-1", LUNCH, 1, pick(WRAP_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1))),
                inputs(world),
                NOW));

        assertThat(refused).isInstanceOf(PricingEngine.UnpricedItemException.class);
        assertThat(((PricingEngine.UnpricedItemException) refused).priceableId())
                .as("not priced at zero: the variant may have a perfectly good VARIANT price that is not this")
                .isEqualTo(WRAP_IN_LUNCH);
    }

    @Test
    @DisplayName("a free component is a price of zero, not a missing price")
    void aFreeComponentIsPricedAtZero() {
        var request = request(line("family-1", FAMILY, 1, pick(COLA_IN_FAMILY, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        assertThat(result.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.finalAmount().minor()).isZero());
        assertThat(result.total().minor()).isZero();
    }

    // ------------------------------------------------- combo selection refusals

    @Test
    @DisplayName("each rule of a combo's shape is refused with its own code")
    void theShapeOfAComboIsEnforced() {
        World world = world();

        // The container is never sold directly.
        assertRefused(world, line("l", LUNCH, 1), "COMBO_SELECTION_REQUIRED", LUNCH);
        // A pick on something that is not a combo.
        assertRefused(world, line("l", BURGER, 1, pick(BURGER_IN_LUNCH, 1)), "COMBO_NOT_CONFIGURED", BURGER);
        // A component of another combo.
        assertRefused(
                world,
                line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1), pick(COLA_IN_FAMILY, 1)),
                "COMBO_COMPONENT_NOT_OFFERED",
                COLA_IN_FAMILY);
        // A component that does not exist at all.
        UUID unknown = uuid(0x99);
        assertRefused(world, line("l", LUNCH, 1, pick(unknown, 1)), "COMBO_COMPONENT_NOT_OFFERED", unknown);
        // The drink group needs exactly one.
        assertRefused(world, line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1)), "COMBO_GROUP_MINIMUM_NOT_MET", DRINK_GROUP);
        // The main group allows one.
        assertRefused(
                world,
                line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(WRAP_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)),
                "COMBO_GROUP_MAXIMUM_EXCEEDED",
                MAIN_GROUP);
        // Two burgers is the same component twice in a group that does not allow it.
        assertRefused(
                world,
                line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 2), pick(COLA_IN_LUNCH, 1)),
                "COMBO_COMPONENT_NOT_REPEATABLE",
                BURGER_IN_LUNCH);
        // A combo line has no priced line of its own for a modifier to land on.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        LUNCH,
                        1,
                        List.of(BOX_OPTION),
                        List.of(pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)),
                        List.of()),
                "COMBO_MODIFIERS_NOT_SUPPORTED",
                LUNCH);
    }

    @Test
    @DisplayName("a group the customer skipped entirely is the one whose minimum is unmet")
    void aSkippedGroupIsCaught() {
        World world = world();

        // Burger and cola satisfy MAIN and DRINK; SIDE has a minimum of zero, so skipping it is fine.
        var fine = engine.price(
                request(line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1))), inputs(world), NOW);
        assertThat(fine.lines()).hasSize(2);

        // Raise SIDE's minimum and the same selection is now refused for the group it never touched.
        world.groups.put(SIDE_GROUP, new ComboGroupFact(SIDE_GROUP, LUNCH, 1, 2, true, 1));
        assertRefused(
                world,
                line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)),
                "COMBO_GROUP_MINIMUM_NOT_MET",
                SIDE_GROUP);
    }

    @Test
    @DisplayName("a group that allows repeats lets one component be picked up to its maximum")
    void repeatsAreAllowedWhereTheGroupSaysSo() {
        var request =
                request(line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(FRIES_IN_LUNCH, 2), pick(COLA_IN_LUNCH, 1)));

        var result = engine.price(request, inputs(world()), NOW);

        assertThat(result.lines().stream().filter(line -> FRIES.equals(line.variantId())))
                .singleElement()
                .extracting(Quote.QuoteLine::quantity)
                .satisfies(quantity -> assertThat(quantity).isEqualByComparingTo("2"));
    }

    @Test
    @DisplayName("a combo line's id must leave room for its component suffix")
    void aComboLineIdLeavesRoomForTheSuffix() {
        String sixtyChars = "x".repeat(QuoteRequest.Line.MAX_COMBO_LINE_ID_LENGTH);
        String sixtyOne = sixtyChars + "y";

        assertThat(new QuoteRequest.Line(sixtyChars, LUNCH, 1, List.of(), List.of(pick(BURGER_IN_LUNCH, 1)), List.of())
                        .lineId())
                .hasSize(60);
        Throwable tooLong = catchThrowable(() ->
                new QuoteRequest.Line(sixtyOne, LUNCH, 1, List.of(), List.of(pick(BURGER_IN_LUNCH, 1)), List.of()));
        assertThat(tooLong).isInstanceOf(IllegalArgumentException.class);
        assertThat(new QuoteRequest.Line(sixtyOne, BURGER, 1, List.of()).lineId())
                .as("an ordinary line keeps the full 64 it always had")
                .hasSize(61);
    }

    // ----------------------------------------------------- hidden modifiers

    @Test
    @DisplayName("a hidden group's option is charged on the line without the customer choosing it")
    void aHiddenChargeIsAddedToTheLine() {
        World world = world();
        world.hidden.put(BURGER, List.of(new HiddenCharge(BOX_GROUP, BOX_OPTION)));
        world.modifierPrices.put(BOX_OPTION, 1_500L);

        var result = engine.price(request(line("burger-1", BURGER, 2)), inputs(world), NOW);

        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitAmount().minor()).as("30,000 + the 1,500 box").isEqualTo(31_500L);
            assertThat(line.finalAmount().minor()).isEqualTo(63_000L);
        });
        assertThat(result.adjustments())
                .filteredOn(adjustment -> PricingEngine.HIDDEN_MODIFIER_SOURCE.equals(adjustment.sourceType()))
                .singleElement()
                .satisfies(adjustment -> {
                    assertThat(adjustment.sourceId())
                            .as("the evidence an order reads to learn which option the server chose")
                            .isEqualTo(BOX_OPTION);
                    assertThat(adjustment.amount().minor())
                            .as("per unit x quantity")
                            .isEqualTo(3_000L);
                    assertThat(adjustment.descriptionCode()).isEqualTo("HIDDEN_MODIFIER");
                    assertThat(adjustment.lineId()).isEqualTo("burger-1");
                });
    }

    @Test
    @DisplayName("a free hidden option is still recorded, so an order knows which option to snapshot")
    void aFreeHiddenOptionLeavesItsAdjustment() {
        World world = world();
        world.hidden.put(BURGER, List.of(new HiddenCharge(FREE_TAG_GROUP, FREE_TAG_OPTION)));
        world.modifierPrices.put(FREE_TAG_OPTION, 0L);

        var result = engine.price(request(line("burger-1", BURGER, 1)), inputs(world), NOW);

        assertThat(result.lines().getFirst().unitAmount().minor()).isEqualTo(30_000L);
        assertThat(result.adjustments())
                .filteredOn(adjustment -> "HIDDEN_MODIFIER".equals(adjustment.descriptionCode()))
                .singleElement()
                .satisfies(adjustment -> assertThat(adjustment.amount().minor()).isZero());
    }

    @Test
    @DisplayName("a hidden option with no price refuses the cart rather than pricing a free box nobody agreed to give")
    void anUnpricedHiddenOptionRefusesTheCart() {
        World world = world();
        world.hidden.put(BURGER, List.of(new HiddenCharge(BOX_GROUP, BOX_OPTION)));

        Throwable refused =
                catchThrowable(() -> engine.price(request(line("burger-1", BURGER, 1)), inputs(world), NOW));

        assertThat(refused).isInstanceOf(PricingEngine.UnpricedItemException.class);
        assertThat(((PricingEngine.UnpricedItemException) refused).priceableId())
                .isEqualTo(BOX_OPTION);
    }

    @Test
    @DisplayName("each component carries the hidden charges of its own variant; the container carries none")
    void hiddenChargesFollowTheComponents() {
        World world = world();
        world.hidden.put(COLA, List.of(new HiddenCharge(BOX_GROUP, BOX_OPTION)));
        // A charge keyed to the container is never read: it has no line to land on.
        world.hidden.put(LUNCH, List.of(new HiddenCharge(FREE_TAG_GROUP, FREE_TAG_OPTION)));
        world.modifierPrices.put(BOX_OPTION, 500L);
        world.modifierPrices.put(FREE_TAG_OPTION, 9_999L);

        var result = engine.price(
                request(line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1))), inputs(world), NOW);

        assertThat(result.lines()).extracting(line -> line.unitAmount().minor()).containsExactly(30_000L, 3_500L);
        assertThat(result.total().minor()).isEqualTo(33_500L);
    }

    @Test
    @DisplayName(
            "the goods subtotal handed to the delivery resolver is the engine's own, hidden charges and components included")
    void theGoodsSubtotalIsThePricedGross() {
        World world = world();
        world.hidden.put(COLA, List.of(new HiddenCharge(BOX_GROUP, BOX_OPTION)));
        world.modifierPrices.put(BOX_OPTION, 500L);
        var request =
                request(line("l", LUNCH, 2, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)), line("b", BURGER, 1));

        var subtotal = engine.goodsSubtotal(request, inputs(world));

        assertThat(subtotal).as("2 x (30,000 + 3,500) + 30,000").isEqualTo(97_000L);
        assertThat(engine.price(request, inputs(world), NOW).total().minor())
                .as("the total, with no discount and no delivery, is that same number")
                .isEqualTo(subtotal);
    }

    // ------------------------------------------------------------ the hash

    @Test
    @DisplayName("a cart with no composite products hashes exactly as it did before ADR 0136")
    void aLegacyCartKeepsItsHash() {
        UUID tenant = uuidOf("00000000-0000-0000-0000-0000000000a1");
        UUID brand = uuidOf("00000000-0000-0000-0000-0000000000b1");
        UUID location = uuidOf("00000000-0000-0000-0000-0000000000c1");
        UUID publication = uuidOf("00000000-0000-0000-0000-0000000000d1");
        UUID book = uuidOf("00000000-0000-0000-0000-0000000000d2");
        UUID tax = uuidOf("00000000-0000-0000-0000-0000000000d3");
        UUID burger = uuidOf("00000000-0000-0000-0000-0000000000e1");
        UUID cheese = uuidOf("00000000-0000-0000-0000-0000000000e2");

        var request = new QuoteRequest(
                tenant,
                brand,
                location,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-1", burger, 2, List.of(cheese))),
                null);
        var legacy = new PricingInputs(
                "UZS",
                publication,
                book,
                7,
                tax,
                3,
                1200,
                TaxMode.INCLUSIVE,
                Map.of(burger, 30_000L),
                Map.of(cheese, 4_000L),
                Map.of());

        assertThat(engine.price(request, legacy, NOW).contextHash())
                .as("computed from the pre-record formula at calculation version 3 (ADR 0140's bump): the "
                        + "composite record adds nothing to a cart with no composite product")
                .isEqualTo("11d4fbdc8da7374a45d9f117e9e52812fff3bf1b33613c0a2300f35556ddd4c3");
    }

    @Test
    @DisplayName("picks, a component's price and a hidden charge each move the hash")
    void compositeInputsAreInTheHash() {
        World world = world();
        var base = request(line("l", LUNCH, 1, pick(BURGER_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1)));
        String baseline = engine.price(base, inputs(world), NOW).contextHash();

        assertThat(engine.price(
                                request(line("l", LUNCH, 1, pick(WRAP_IN_LUNCH, 1), pick(COLA_IN_LUNCH, 1))),
                                inputs(world),
                                NOW)
                        .contextHash())
                .as("a different pick")
                .isNotEqualTo(baseline);

        World repriced = world();
        repriced.componentPrices.put(BURGER_IN_LUNCH, 31_000L);
        assertThat(engine.price(base, inputs(repriced), NOW).contextHash())
                .as("the same pick at another price")
                .isNotEqualTo(baseline);

        World withBox = world();
        withBox.hidden.put(COLA, List.of(new HiddenCharge(BOX_GROUP, BOX_OPTION)));
        withBox.modifierPrices.put(BOX_OPTION, 500L);
        String boxed = engine.price(base, inputs(withBox), NOW).contextHash();
        assertThat(boxed).as("a hidden charge appearing").isNotEqualTo(baseline);

        var delivery = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                base.lines(),
                null,
                null,
                null,
                null,
                FulfillmentMode.DELIVERY);
        var dineIn = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                base.lines(),
                null,
                null,
                null,
                null,
                FulfillmentMode.DINE_IN);
        assertThat(engine.price(delivery, inputs(withBox), NOW).contextHash())
                .as("the mode a hidden charge was applied for")
                .isNotEqualTo(engine.price(dineIn, inputs(withBox), NOW).contextHash());
        assertThat(engine.price(delivery, inputs(world), NOW).contextHash())
                .as("but the mode alone, with nothing hidden to apply, changes no quote")
                .isEqualTo(engine.price(dineIn, inputs(world), NOW).contextHash());
    }

    @Test
    @DisplayName("a request with no stated fulfilment mode means what it always meant")
    void theFulfilmentModeDefaultsFromTheDestination() {
        var collected =
                new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(line("l", BURGER, 1)), null);
        var delivered = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(line("l", BURGER, 1)),
                null,
                new QuoteRequest.Delivery(
                        new uz.horecaos.platform.tenancy.api.GeoPoint(41.3, 69.2),
                        uz.horecaos.platform.fulfillment.api.PricingAuthority.HORECAOS));
        var dineIn = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(line("l", BURGER, 1)),
                null,
                null,
                null,
                null,
                FulfillmentMode.DINE_IN);

        assertThat(collected.effectiveFulfillmentMode()).isEqualTo(FulfillmentMode.PICKUP);
        assertThat(delivered.effectiveFulfillmentMode()).isEqualTo(FulfillmentMode.DELIVERY);
        assertThat(dineIn.effectiveFulfillmentMode())
                .as("dine-in has to be said: nothing else tells it from a collection")
                .isEqualTo(FulfillmentMode.DINE_IN);
    }

    // ------------------------------------------------------------- nesting

    @Test
    @DisplayName("a nested option is priced like a first-level one and shown as its own adjustment")
    void aNestedOptionIsPriced() {
        World world = nestedWorld();

        var result = engine.price(
                request(new QuoteRequest.Line(
                        "l",
                        BURGER,
                        2,
                        List.of(ADD_SIDE_OPTION),
                        List.of(),
                        List.of(new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, KETCHUP_OPTION)))),
                inputs(world),
                NOW);

        assertThat(result.lines().getFirst().unitAmount().minor())
                .as("30,000 + 10,000 for the side + 500 for the dip")
                .isEqualTo(40_500L);
        assertThat(result.adjustments())
                .filteredOn(adjustment -> "NESTED_MODIFIERS".equals(adjustment.descriptionCode()))
                .singleElement()
                .satisfies(adjustment -> assertThat(adjustment.amount().minor()).isEqualTo(1_000L));
    }

    @Test
    @DisplayName("each rule of the second level is refused with its own code")
    void theSecondLevelIsBounded() {
        World world = nestedWorld();

        // A dip under an option that was never selected.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(),
                        List.of(),
                        List.of(new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, KETCHUP_OPTION))),
                "MODIFIER_NESTED_PARENT_NOT_SELECTED",
                ADD_SIDE_OPTION);
        // A third level: the parent is itself a nested option.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(ADD_SIDE_OPTION),
                        List.of(),
                        List.of(
                                new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, KETCHUP_OPTION),
                                new QuoteRequest.NestedModifier(KETCHUP_OPTION, NESTED_DEEPER_OPTION))),
                "MODIFIER_NESTING_DEPTH_EXCEEDED",
                NESTED_DEEPER_OPTION);
        // An option the linked variant does not offer.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(ADD_SIDE_OPTION),
                        List.of(),
                        List.of(new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, uuid(0x98)))),
                "MODIFIER_NESTED_OPTION_NOT_OFFERED",
                uuid(0x98));
        // Two dips from a group that allows one.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(ADD_SIDE_OPTION),
                        List.of(),
                        List.of(
                                new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, KETCHUP_OPTION),
                                new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, MAYO_OPTION))),
                "MODIFIER_GROUP_MAXIMUM_EXCEEDED",
                DIP_GROUP);
        // The same dip twice in a group that does not allow repeats.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(ADD_SIDE_OPTION),
                        List.of(),
                        List.of(
                                new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, KETCHUP_OPTION),
                                new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, KETCHUP_OPTION))),
                "MODIFIER_GROUP_MAXIMUM_EXCEEDED",
                DIP_GROUP);
        // A second-level selection under an option that links nothing.
        assertRefused(
                world,
                new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(PLAIN_OPTION),
                        List.of(),
                        List.of(new QuoteRequest.NestedModifier(PLAIN_OPTION, KETCHUP_OPTION))),
                "MODIFIER_NESTED_OPTION_NOT_OFFERED",
                KETCHUP_OPTION);
    }

    @Test
    @DisplayName("a required group of the linked variant is enforced even when the customer sent no nested selection")
    void anUnansweredRequiredNestedGroupIsRefused() {
        World world = nestedWorld();
        world.nestedGroups.put(
                SIDE_MEAL,
                List.of(
                        new NestedGroupFact(DIP_GROUP, false, 0, 1, false, Set.of(KETCHUP_OPTION, MAYO_OPTION)),
                        new NestedGroupFact(SPICY_GROUP, true, 1, 1, false, Set.of(SPICY_OPTION))));

        assertRefused(
                world,
                new QuoteRequest.Line("l", BURGER, 1, List.of(ADD_SIDE_OPTION), List.of(), List.of()),
                "MODIFIER_GROUP_MINIMUM_NOT_MET",
                SPICY_GROUP);

        var answered = engine.price(
                request(new QuoteRequest.Line(
                        "l",
                        BURGER,
                        1,
                        List.of(ADD_SIDE_OPTION),
                        List.of(),
                        List.of(new QuoteRequest.NestedModifier(ADD_SIDE_OPTION, SPICY_OPTION)))),
                inputs(world),
                NOW);
        assertThat(answered.lines()).hasSize(1);
    }

    @Test
    @DisplayName("an option with choices of its own cannot be selected twice on one line")
    void aParentWithChoicesIsNotRepeatable() {
        assertRefused(
                nestedWorld(),
                new QuoteRequest.Line("l", BURGER, 1, List.of(ADD_SIDE_OPTION, ADD_SIDE_OPTION), List.of(), List.of()),
                "MODIFIER_NESTED_PARENT_REPEATED",
                ADD_SIDE_OPTION);
    }

    @Test
    @DisplayName("an option linking a variant with no groups takes no nested selection and costs only itself")
    void aLinkedVariantWithoutGroupsIsAnOrdinaryOption() {
        World world = nestedWorld();
        world.nestedGroups.clear();

        var result = engine.price(
                request(new QuoteRequest.Line("l", BURGER, 1, List.of(ADD_SIDE_OPTION), List.of(), List.of())),
                inputs(world),
                NOW);

        assertThat(result.lines().getFirst().unitAmount().minor()).isEqualTo(40_000L);
    }

    // ---------------------------------------------------------------- fixtures

    private void assertRefused(World world, QuoteRequest.Line line, String code, UUID subject) {
        Throwable refused = catchThrowable(() -> engine.price(request(line), inputs(world), NOW));

        assertThat(refused).as("%s for %s", code, line).isInstanceOf(CompositeSelectionException.class);
        assertThat(((CompositeSelectionException) refused).code()).isEqualTo(code);
        assertThat(((CompositeSelectionException) refused).subjectId()).isEqualTo(subject);
    }

    private static QuoteRequest.Line line(String id, UUID variant, int quantity, QuoteRequest.ComboPick... picks) {
        return new QuoteRequest.Line(id, variant, quantity, List.of(), List.of(picks), List.of());
    }

    private static QuoteRequest.ComboPick pick(UUID component, int quantity) {
        return new QuoteRequest.ComboPick(component, quantity);
    }

    private static QuoteRequest request(QuoteRequest.Line... lines) {
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(lines), null);
    }

    private static PricingInputs inputs(World world) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                7,
                TAX_PROFILE,
                3,
                1200,
                TaxMode.INCLUSIVE,
                world.variantPrices,
                world.modifierPrices,
                world.descriptions,
                null,
                null,
                new CompositeInputs(
                        world.groups,
                        groupIdsByContainer(world),
                        world.components,
                        world.componentPrices,
                        world.hidden,
                        world.options,
                        world.nestedGroups,
                        Map.of("lunch-1", SELECTION, "l", SELECTION, "family-1", uuid(0x71))));
    }

    private static Map<UUID, List<UUID>> groupIdsByContainer(World world) {
        Map<UUID, List<UUID>> byContainer = new LinkedHashMap<>();
        world.groups.values().stream()
                .sorted(java.util.Comparator.comparingInt(ComboGroupFact::sortOrder))
                .forEach(group -> byContainer
                        .computeIfAbsent(group.containerVariantId(), key -> new ArrayList<>())
                        .add(group.id()));
        return byContainer;
    }

    /** LUNCH: choose a main, optionally up to two sides, and a drink. FAMILY: a drink, free. */
    private static World world() {
        World world = new World();
        world.groups.put(MAIN_GROUP, new ComboGroupFact(MAIN_GROUP, LUNCH, 1, 1, false, 0));
        world.groups.put(SIDE_GROUP, new ComboGroupFact(SIDE_GROUP, LUNCH, 0, 2, true, 1));
        world.groups.put(DRINK_GROUP, new ComboGroupFact(DRINK_GROUP, LUNCH, 1, 1, false, 2));
        world.groups.put(FAMILY_DRINK_GROUP, new ComboGroupFact(FAMILY_DRINK_GROUP, FAMILY, 1, 1, false, 0));

        world.component(BURGER_IN_LUNCH, MAIN_GROUP, BURGER, 1, 0, 30_000L);
        world.component(WRAP_IN_LUNCH, MAIN_GROUP, WRAP, 1, 1, 28_000L);
        world.component(FRIES_IN_LUNCH, SIDE_GROUP, FRIES, 1, 0, 8_000L);
        world.component(WINGS_IN_LUNCH, SIDE_GROUP, WINGS, 6, 1, 2_000L);
        world.component(COLA_IN_LUNCH, DRINK_GROUP, COLA, 1, 0, 3_000L);
        world.component(COLA_IN_FAMILY, FAMILY_DRINK_GROUP, COLA, 1, 0, 0L);

        // The standalone dishes are also sold by themselves.
        world.variantPrices.put(BURGER, 30_000L);
        world.descriptions.put(BURGER, "Burger");
        world.descriptions.put(COLA, "Cola");
        world.descriptions.put(WRAP, "Wrap");
        world.descriptions.put(FRIES, "Fries");
        world.descriptions.put(WINGS, "Wings");
        return world;
    }

    /** BURGER offers "Add a side" (10,000), which links SIDE_MEAL; SIDE_MEAL offers one dip (500). */
    private static World nestedWorld() {
        World world = world();
        world.modifierPrices.put(ADD_SIDE_OPTION, 10_000L);
        world.modifierPrices.put(PLAIN_OPTION, 0L);
        world.modifierPrices.put(KETCHUP_OPTION, 500L);
        world.modifierPrices.put(MAYO_OPTION, 700L);
        world.modifierPrices.put(SPICY_OPTION, 0L);
        world.modifierPrices.put(NESTED_DEEPER_OPTION, 100L);
        world.options.put(ADD_SIDE_OPTION, new OptionFact(ADD_SIDE_OPTION, uuid(0x69), SIDE_MEAL, 1));
        world.options.put(PLAIN_OPTION, new OptionFact(PLAIN_OPTION, uuid(0x69), null, 1));
        world.options.put(KETCHUP_OPTION, new OptionFact(KETCHUP_OPTION, DIP_GROUP, null, 1));
        world.options.put(MAYO_OPTION, new OptionFact(MAYO_OPTION, DIP_GROUP, null, 1));
        world.options.put(SPICY_OPTION, new OptionFact(SPICY_OPTION, SPICY_GROUP, null, 1));
        world.options.put(NESTED_DEEPER_OPTION, new OptionFact(NESTED_DEEPER_OPTION, DIP_GROUP, null, 1));
        world.nestedGroups.put(
                SIDE_MEAL,
                List.of(new NestedGroupFact(DIP_GROUP, false, 0, 1, false, Set.of(KETCHUP_OPTION, MAYO_OPTION))));
        return world;
    }

    private static UUID uuid(int tail) {
        return UUID.fromString("018f9f10-5000-7000-8000-%012x".formatted(tail));
    }

    private static UUID uuidOf(String value) {
        return UUID.fromString(value);
    }

    /** Mutable fixture the tests adjust before building the immutable inputs. */
    private static final class World {
        final Map<UUID, ComboGroupFact> groups = new LinkedHashMap<>();
        final Map<UUID, ComboComponentFact> components = new LinkedHashMap<>();
        final Map<UUID, Long> componentPrices = new LinkedHashMap<>();
        final Map<UUID, Long> variantPrices = new LinkedHashMap<>();
        final Map<UUID, Long> modifierPrices = new LinkedHashMap<>();
        final Map<UUID, String> descriptions = new LinkedHashMap<>();
        final Map<UUID, List<HiddenCharge>> hidden = new LinkedHashMap<>();
        final Map<UUID, OptionFact> options = new LinkedHashMap<>();
        final Map<UUID, List<NestedGroupFact>> nestedGroups = new LinkedHashMap<>();

        void component(UUID id, UUID group, UUID variant, int defaultQuantity, int sortOrder, long price) {
            components.put(id, new ComboComponentFact(id, group, variant, defaultQuantity, sortOrder));
            componentPrices.put(id, price);
        }
    }
}
