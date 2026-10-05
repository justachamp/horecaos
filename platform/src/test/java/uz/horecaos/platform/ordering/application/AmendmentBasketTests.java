package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.application.OrderAmendmentService.AmendmentRefusedException;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.pricing.api.CartPricingPort.PricingCommand;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;

/**
 * How an amendment prices the live basket again (ADR 0039, ADR 0136), on literals.
 *
 * <p>The property under test is the one that would silently corrupt money: an order holds a
 * combo as several ordinary lines, and an amendment that handed those lines back to pricing
 * as ordinary lines would charge each component at its own variant price instead of its combo
 * price -- the order total would move by the difference on a change that never touched the
 * combo. Every test here would still pass if the basket treated every line as ordinary except
 * the ones that assert a combo comes back as one item with its picks.
 */
class AmendmentBasketTests {

    private static final UUID LUNCH = UUID.randomUUID();
    private static final UUID BURGER = UUID.randomUUID();
    private static final UUID COLA = UUID.randomUUID();
    private static final UUID BURGER_IN_LUNCH = UUID.randomUUID();
    private static final UUID COLA_IN_LUNCH = UUID.randomUUID();
    private static final UUID SELECTION = UUID.randomUUID();

    private static final UUID PLAIN_VARIANT = UUID.randomUUID();

    // -------------------------------------------------------------- folding

    @Test
    @DisplayName("the component lines of a combo come back as one unit, and an ordinary line as itself")
    void unitsFoldComponentLines() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 2, 1, 2);
        OrderLineRow plain = plain(2, PLAIN_VARIANT, 1);
        OrderLineRow cola = component(3, COLA, COLA_IN_LUNCH, 2, 1, 2);

        List<AmendmentBasket.Unit> units = AmendmentBasket.units(List.of(burger, plain, cola));

        assertThat(units).hasSize(2);
        assertThat(units.get(0).lines())
                .as("a combo sits where its first component was bought, and keeps its components together")
                .containsExactly(burger, cola);
        assertThat(units.get(0).combo()).isTrue();
        assertThat(units.get(0).key()).isEqualTo(SELECTION.toString());
        assertThat(units.get(1).lines()).containsExactly(plain);
        assertThat(units.get(1).combo()).isFalse();
        assertThat(units.get(1).key()).isEqualTo(plain.lineId().toString());
    }

    @Test
    @DisplayName("a combo is priced again as its container and its picks, never as its components")
    void aComboIsPricedAsAComboAndNotAsItsComponents() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 2, 1, 2);
        OrderLineRow cola = component(2, COLA, COLA_IN_LUNCH, 2, 1, 2);

        List<PricingCommand.Item> items =
                AmendmentBasket.pricingItems(AmendmentBasket.units(List.of(burger, cola)), Map.of(), Map.of());

        assertThat(items).hasSize(1);
        PricingCommand.Item item = items.get(0);
        assertThat(item.variantId())
                .as("the container is what pricing knows; the components are its picks")
                .isEqualTo(LUNCH);
        assertThat(item.lineKey()).isEqualTo(SELECTION.toString());
        assertThat(item.quantity())
                .as("combos bought, not units of any component")
                .isEqualByComparingTo("2");
        assertThat(item.comboPicks())
                .extracting(PricingCommand.ComboPick::componentId, PricingCommand.ComboPick::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BURGER_IN_LUNCH, 1),
                        org.assertj.core.groups.Tuple.tuple(COLA_IN_LUNCH, 1));
        assertThat(item.modifierOptionIds()).isEmpty();
    }

    @Test
    @DisplayName("what the server applied is not handed back as a choice, and a nested choice names its parent")
    void appliedAndNestedSelectionsAreSeparatedFromChoices() {
        OrderLineRow line = plain(1, PLAIN_VARIANT, 1);
        UUID sauce = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        UUID spice = UUID.randomUUID();
        UUID sauceRow = UUID.randomUUID();

        List<OrderModifierRow> rows = List.of(
                modifier(line, sauceRow, sauce, null, false),
                modifier(line, UUID.randomUUID(), spice, sauceRow, false),
                modifier(line, UUID.randomUUID(), box, null, true));

        PricingCommand.Item item = AmendmentBasket.pricingItems(
                        AmendmentBasket.units(List.of(line)), Map.of(), Map.of(line.lineId(), rows))
                .get(0);

        assertThat(item.modifierOptionIds())
                .as("a hidden delivery box is applied again by pricing for the order's mode; "
                        + "passed back as a choice it would be charged twice")
                .containsExactly(sauce);
        assertThat(item.nestedModifiers())
                .extracting(PricingCommand.NestedModifier::parentOptionId, PricingCommand.NestedModifier::optionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(sauce, spice));
    }

    // ----------------------------------------------------------- quantities

    @Test
    @DisplayName("asking for more units of one component, in whole combos, grows the combo")
    void aWholeNumberOfCombosScalesTheCombo() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 2, 1, 2);
        OrderLineRow cola = component(2, COLA, COLA_IN_LUNCH, 2, 1, 2);

        PricingCommand.Item item = AmendmentBasket.pricingItems(
                        AmendmentBasket.units(List.of(burger, cola)),
                        Map.of(burger.lineId(), BigDecimal.valueOf(5)),
                        Map.of())
                .get(0);

        assertThat(item.quantity())
                .as("5 burgers at one per combo is 5 combos, and the drink follows")
                .isEqualByComparingTo("5");
        assertThat(item.comboPicks()).hasSize(2);
    }

    @Test
    @DisplayName("a quantity that is not a whole number of combos is refused with its own code")
    void aFractionOfAComboIsRefused() {
        // Two wings per combo, two combos bought: four units. Asking for five is two and a half.
        OrderLineRow wings = component(1, BURGER, BURGER_IN_LUNCH, 4, 2, 2);

        Throwable refused = catchThrowable(() -> AmendmentBasket.pricingItems(
                AmendmentBasket.units(List.of(wings)), Map.of(wings.lineId(), BigDecimal.valueOf(5)), Map.of()));

        assertThat(refused).isInstanceOf(AmendmentRefusedException.class);
        assertThat(((AmendmentRefusedException) refused).code()).isEqualTo("COMBO_QUANTITY_NOT_WHOLE");
    }

    @Test
    @DisplayName("two components of one purchase asked for different numbers of combos is a conflict")
    void disagreeingComponentsConflict() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 2, 1, 2);
        OrderLineRow cola = component(2, COLA, COLA_IN_LUNCH, 2, 1, 2);

        Throwable refused = catchThrowable(() -> AmendmentBasket.pricingItems(
                AmendmentBasket.units(List.of(burger, cola)),
                Map.of(burger.lineId(), BigDecimal.valueOf(3), cola.lineId(), BigDecimal.valueOf(4)),
                Map.of()));

        assertThat(refused).isInstanceOf(AmendmentRefusedException.class);
        assertThat(((AmendmentRefusedException) refused).code()).isEqualTo("COMBO_QUANTITY_CONFLICT");
    }

    @Test
    @DisplayName("a decrease is refused for a combo exactly as for an ordinary line")
    void aDecreaseIsRefused() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 4, 1, 4);

        Throwable refused = catchThrowable(() -> AmendmentBasket.pricingItems(
                AmendmentBasket.units(List.of(burger)), Map.of(burger.lineId(), BigDecimal.valueOf(2)), Map.of()));

        assertThat(refused).isInstanceOf(AmendmentRefusedException.class);
        assertThat(((AmendmentRefusedException) refused).code()).isEqualTo("QUANTITY_DECREASE_NOT_SUPPORTED");
    }

    // ------------------------------------------------- decimal quantities (ADR 0137)

    @Test
    @DisplayName("a line sold by the portion is priced again at the fraction it was changed to")
    void aPlainLineTakesADecimalQuantity() {
        OrderLineRow plov = plain(1, PLAIN_VARIANT, 1);

        PricingCommand.Item item = AmendmentBasket.pricingItems(
                        AmendmentBasket.units(List.of(plov)), Map.of(plov.lineId(), new BigDecimal("1.50")), Map.of())
                .get(0);

        assertThat(item.quantity())
                .as("one and a half portions, not 1 and not 2")
                .isEqualByComparingTo("1.5");
        assertThat(item.quantity().scale())
                .as("the canonical form: no trailing zero, so equal baskets compare equal")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("half a portion added to a whole one is an increase; the same quantity is not")
    void onlyAStrictlyLargerQuantityIsAnIncrease() {
        OrderLineRow plov = plain(1, PLAIN_VARIANT, 1);

        assertThat(AmendmentBasket.pricingItems(
                                AmendmentBasket.units(List.of(plov)),
                                Map.of(plov.lineId(), new BigDecimal("1.001")),
                                Map.of())
                        .get(0)
                        .quantity())
                .isEqualByComparingTo("1.001");
        for (String notLarger : List.of("1", "1.000", "0.5")) {
            Throwable refused = catchThrowable(() -> AmendmentBasket.pricingItems(
                    AmendmentBasket.units(List.of(plov)), Map.of(plov.lineId(), new BigDecimal(notLarger)), Map.of()));
            assertThat(refused).as(notLarger + " is not larger than 1").isInstanceOf(AmendmentRefusedException.class);
            assertThat(((AmendmentRefusedException) refused).code()).isEqualTo("QUANTITY_DECREASE_NOT_SUPPORTED");
        }
    }

    @Test
    @DisplayName("a combo changes by whole combos: a fraction of one is refused with the combo code")
    void aFractionOfAComboIsRefusedWhateverItsSize() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 2, 1, 2);

        Throwable refused = catchThrowable(() -> AmendmentBasket.pricingItems(
                AmendmentBasket.units(List.of(burger)), Map.of(burger.lineId(), new BigDecimal("2.5")), Map.of()));

        assertThat(refused).isInstanceOf(AmendmentRefusedException.class);
        assertThat(((AmendmentRefusedException) refused).code()).isEqualTo("COMBO_QUANTITY_NOT_WHOLE");
    }

    @Test
    @DisplayName("stock is held in whole units, so only the units beyond those already held are asked for")
    void aFractionReservesTheDifferenceOfWholeUnits() {
        OrderLineRow half = plain(1, PLAIN_VARIANT, new BigDecimal("0.5"));
        OrderLineRow whole = plain(2, PLAIN_VARIANT, 1);
        List<AmendmentBasket.Unit> units = AmendmentBasket.units(List.of(half, whole));

        // 0.5 -> 1 : the half plate already holds a whole one at checkout, so nothing more is held.
        assertThat(AmendmentBasket.increases(
                        quote(plainQuoted(half.lineId().toString(), PLAIN_VARIANT, new BigDecimal("1"))),
                        AmendmentBasket.touched(units, Set.of(half.lineId())),
                        Set.of()))
                .isEmpty();
        // 0.5 -> 1.5 : a second plate.
        assertThat(AmendmentBasket.increases(
                        quote(plainQuoted(half.lineId().toString(), PLAIN_VARIANT, new BigDecimal("1.5"))),
                        AmendmentBasket.touched(units, Set.of(half.lineId())),
                        Set.of()))
                .containsOnly(Map.entry(PLAIN_VARIANT, 1));
        // 1 -> 1.5 : the extra half rounds up to a plate; 1 -> 2.5 holds two.
        assertThat(AmendmentBasket.increases(
                        quote(plainQuoted(whole.lineId().toString(), PLAIN_VARIANT, new BigDecimal("1.5"))),
                        AmendmentBasket.touched(units, Set.of(whole.lineId())),
                        Set.of()))
                .containsOnly(Map.entry(PLAIN_VARIANT, 1));
        assertThat(AmendmentBasket.increases(
                        quote(plainQuoted(whole.lineId().toString(), PLAIN_VARIANT, new BigDecimal("2.5"))),
                        AmendmentBasket.touched(units, Set.of(whole.lineId())),
                        Set.of()))
                .containsOnly(Map.entry(PLAIN_VARIANT, 2));
    }

    // ---------------------------------------------------------------- weighing

    @Test
    @DisplayName("a weight captured at the pass rides on the weighed line only, over the basket an amendment prices")
    void aCapturedWeightRidesOnTheWeighedLineOnly() {
        UUID fishVariant = UUID.randomUUID();
        OrderLineRow weighedNow = weighed(1, fishVariant, null);
        OrderLineRow weighedEarlier = weighed(2, fishVariant, 1_250);
        OrderLineRow burger = component(3, BURGER, BURGER_IN_LUNCH, 1, 1, 1);
        OrderLineRow cola = component(4, COLA, COLA_IN_LUNCH, 1, 1, 1);
        UUID chili = UUID.randomUUID();
        UUID hot = UUID.randomUUID();
        UUID packing = UUID.randomUUID();
        UUID chiliRow = UUID.randomUUID();
        List<OrderModifierRow> earlierRows = List.of(
                modifier(weighedEarlier, chiliRow, chili, null, false),
                modifier(weighedEarlier, UUID.randomUUID(), hot, chiliRow, false),
                modifier(weighedEarlier, UUID.randomUUID(), packing, null, true));

        List<PricingCommand.Item> items = AmendmentBasket.pricingItemsWeighing(
                AmendmentBasket.units(List.of(weighedNow, weighedEarlier, burger, cola)),
                Map.of(weighedEarlier.lineId(), earlierRows),
                weighedNow.lineId(),
                1_300);

        assertThat(items).hasSize(3);
        assertThat(items.get(0).lineKey()).isEqualTo(weighedNow.lineId().toString());
        assertThat(items.get(0).actualWeightGrams())
                .as("the line being weighed carries the grams just captured")
                .isEqualTo(1_300);
        assertThat(items.get(1).actualWeightGrams())
                .as("a line weighed before keeps its weight")
                .isEqualTo(1_250);
        assertThat(items.get(1).modifierOptionIds())
                .as("the packing the server applied is applied again by pricing, never handed back as a choice")
                .containsExactly(chili);
        assertThat(items.get(1).nestedModifiers())
                .extracting(PricingCommand.NestedModifier::parentOptionId, PricingCommand.NestedModifier::optionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(chili, hot));
        assertThat(items.get(2).variantId())
                .as("a combo on the same order goes back as its container, priced at its combo price")
                .isEqualTo(LUNCH);
        assertThat(items.get(2).comboPicks()).hasSize(2);
        assertThat(items.get(2).actualWeightGrams()).isNull();
    }

    // ------------------------------------------------------------- reserving

    @Test
    @DisplayName("only the units beyond what the live lines hold are reserved, for every component of a grown combo")
    void aGrownComboReservesItsComponentsGrowth() {
        OrderLineRow burger = component(1, BURGER, BURGER_IN_LUNCH, 2, 1, 2);
        OrderLineRow cola = component(2, COLA, COLA_IN_LUNCH, 2, 1, 2);
        OrderLineRow plain = plain(3, PLAIN_VARIANT, 1);
        List<AmendmentBasket.Unit> units = AmendmentBasket.units(List.of(burger, cola, plain));

        QuoteSnapshot quote = quote(
                priced(SELECTION + "~1", BURGER, 5, BURGER_IN_LUNCH, 5, 1),
                priced(SELECTION + "~2", COLA, 5, COLA_IN_LUNCH, 5, 1),
                plainQuoted(plain.lineId().toString(), PLAIN_VARIANT, 1));

        Map<UUID, Integer> increase =
                AmendmentBasket.increases(quote, AmendmentBasket.touched(units, Set.of(burger.lineId())), Set.of());

        assertThat(increase)
                .as("three more burgers and three more colas; the untouched plain line is not asked for again")
                .containsOnly(Map.entry(BURGER, 3), Map.entry(COLA, 3));
    }

    @Test
    @DisplayName("an added combo reserves every component it is, though the request names only the container")
    void anAddedComboReservesItsComponents() {
        QuoteSnapshot quote = quote(
                priced("amend-new:0~1", BURGER, 2, BURGER_IN_LUNCH, 2, 1),
                priced("amend-new:0~2", COLA, 2, COLA_IN_LUNCH, 2, 1));

        Map<UUID, Integer> increase = AmendmentBasket.increases(quote, List.of(), Set.of("amend-new:0"));

        assertThat(increase).containsOnly(Map.entry(BURGER, 2), Map.entry(COLA, 2));
        assertThat(increase).as("the container is never stocked").doesNotContainKey(LUNCH);
    }

    // ---------------------------------------------------------------- fixtures

    private static OrderLineRow component(int number, UUID variant, UUID componentId, int units, int pick, int combos) {
        return new OrderLineRow(
                UUID.randomUUID(),
                number,
                UUID.randomUUID(),
                variant,
                "name",
                "variant",
                "SKU",
                BigDecimal.valueOf(units),
                1_000L,
                1_000L * units,
                1_000L * units,
                0L,
                "",
                SELECTION,
                LUNCH,
                "Lunch box",
                componentId,
                combos,
                pick,
                null,
                null,
                null,
                null);
    }

    private static OrderLineRow plain(int number, UUID variant, int quantity) {
        return plain(number, variant, BigDecimal.valueOf(quantity));
    }

    private static OrderLineRow plain(int number, UUID variant, BigDecimal quantity) {
        long finalAmount = quantity.multiply(BigDecimal.valueOf(2_000L)).longValue();
        return new OrderLineRow(
                UUID.randomUUID(),
                number,
                UUID.randomUUID(),
                variant,
                "name",
                "variant",
                "SKU",
                quantity,
                2_000L,
                finalAmount,
                finalAmount,
                0L,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static OrderLineRow weighed(int number, UUID variant, @Nullable Integer actualWeightGrams) {
        return new OrderLineRow(
                UUID.randomUUID(),
                number,
                UUID.randomUUID(),
                variant,
                "name",
                "variant",
                "SKU",
                BigDecimal.ONE,
                20_000L,
                20_000L,
                20_000L,
                0L,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                100,
                1_000,
                2_000L,
                actualWeightGrams);
    }

    private static OrderModifierRow modifier(
            OrderLineRow line, UUID modifierId, UUID optionId, @Nullable UUID parent, boolean auto) {
        return new OrderModifierRow(
                line.lineId(), UUID.randomUUID(), optionId, "group", "option", 1, 0L, 0L, modifierId, parent, auto);
    }

    private static QuoteSnapshot.Line priced(
            String key, UUID variant, int units, UUID componentId, int combos, int pick) {
        return new QuoteSnapshot.Line(
                key,
                variant,
                BigDecimal.valueOf(units),
                "name",
                1_000L,
                1_000L * units,
                1_000L * units,
                0L,
                UUID.randomUUID(),
                LUNCH,
                componentId,
                combos,
                pick,
                null);
    }

    private static QuoteSnapshot.Line plainQuoted(String key, UUID variant, int quantity) {
        return plainQuoted(key, variant, BigDecimal.valueOf(quantity));
    }

    private static QuoteSnapshot.Line plainQuoted(String key, UUID variant, BigDecimal quantity) {
        return new QuoteSnapshot.Line(key, variant, quantity, "name", 2_000L, 2_000L, 2_000L, 0L);
    }

    private static QuoteSnapshot quote(QuoteSnapshot.Line... lines) {
        return new QuoteSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "hash",
                0L,
                0L,
                0L,
                0L,
                0L,
                java.time.Instant.parse("2026-10-01T12:00:00Z"),
                List.of(lines),
                List.of(),
                null,
                null,
                null,
                null);
    }
}
