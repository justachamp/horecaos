package uz.horecaos.platform.pos.infrastructure.clopos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.domain.SourceKind;

/**
 * The normalizer, and specifically the vendor traps it exists to absorb
 * (ADR 0012).
 *
 * <p>The fixtures are shaped after Clopos's own documented example responses, so
 * a change in this file is a claim about the vendor rather than about our code.
 */
class CloposCatalogNormalizerTests {

    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");

    private final CloposCatalogNormalizer normalizer = new CloposCatalogNormalizer("UZS");

    @Test
    @DisplayName("a modification becomes a variant and a modificator becomes a modifier option")
    void theTwoWordsThreeCharactersApartDoNotCross() {
        Map<String, Object> dish = product(41, "Lagman", "DISH", 32000);
        dish.put("modifications", List.of(product(410, "Large", "MODIFICATION", 36000)));
        dish.put(
                "modificator_groups",
                List.of(Map.of(
                        "id",
                        90,
                        "name",
                        "Spice level",
                        "min",
                        0,
                        "max",
                        1,
                        "modificators",
                        List.of(Map.of("id", 901, "name", "Extra chilli", "price", 2000)))));

        CatalogSnapshot snapshot = normalizer.normalize(List.of(dish), List.of(), List.of(), NOW, false, 1);

        assertThat(snapshot.variants()).singleElement().satisfies(variant -> {
            assertThat(variant.externalId()).isEqualTo("410");
            assertThat(variant.externalProductId()).isEqualTo("41");
            assertThat(variant.priceMinor()).isEqualTo(36000L);
        });
        assertThat(snapshot.modifierGroups())
                .singleElement()
                .satisfies(group -> assertThat(group.externalId()).isEqualTo("90"));
        assertThat(snapshot.modifiers()).singleElement().satisfies(modifier -> {
            assertThat(modifier.externalId()).isEqualTo("901");
            assertThat(modifier.externalGroupId()).isEqualTo("90");
        });
    }

    @Test
    @DisplayName("a product with variants is marked as not itself sellable")
    void aParentWithVariantsIsAShell() {
        Map<String, Object> parent = product(7, "Cola", "GOODS", 0);
        parent.put("modifications", List.of(product(70, "0.5l", "MODIFICATION", 9000)));

        CatalogSnapshot snapshot = normalizer.normalize(List.of(parent), List.of(), List.of(), NOW, false, 1);

        assertThat(snapshot.products())
                .singleElement()
                .satisfies(product -> assertThat(product.parentOnly())
                        .as("the provider states the parent's price may be zero and is not inherited "
                                + "at sale time, so publishing it as priceable publishes a free dish")
                        .isTrue());
    }

    @Test
    @DisplayName("ingredients are staged and marked, never dropped and never comparable")
    void inventoryKindsSurviveAsEvidence() {
        CatalogSnapshot snapshot = normalizer.normalize(
                List.of(
                        product(3, "Test_Tomato", "INGREDIENT", 0),
                        product(4, "Test_Onion", "PREPARATION", 0),
                        product(5, "PS5 hour", "TIMER", 0)),
                List.of(),
                List.of(),
                NOW,
                false,
                1);

        assertThat(snapshot.products()).hasSize(3);
        assertThat(snapshot.comparableProducts())
                .as("the provider's own example response returns a tomato and an onion")
                .isEmpty();
        assertThat(snapshot.products())
                .extracting(CatalogSnapshot.Product::sourceKind)
                .containsExactly(SourceKind.INGREDIENT, SourceKind.PREPARATION, SourceKind.TIMER);
    }

    @Test
    @DisplayName("a product type the OpenAPI enum does not contain is UNKNOWN rather than guessed")
    void anUnrecognisedTypeIsNotMappedToAGuess() {
        CatalogSnapshot snapshot = normalizer.normalize(
                List.of(product(8, "Extra cheese", "MODIFIER", 3000)), List.of(), List.of(), NOW, false, 1);

        assertThat(snapshot.products())
                .singleElement()
                .satisfies(product -> assertThat(product.sourceKind())
                        .as("MODIFIER appears in the prose field reference and not in the schema "
                                + "enum; guessing would hide the discrepancy")
                        .isEqualTo(SourceKind.UNKNOWN));
    }

    @Test
    @DisplayName("the stop list timestamp is milliseconds while the rest of the API is not")
    void theStopListIsReadInMilliseconds() {
        CatalogSnapshot snapshot = normalizer.normalize(
                List.of(), List.of(), List.of(Map.of("id", 54, "limit", 0, "timestamp", 1761202010781L)), NOW, true, 1);

        assertThat(snapshot.availability()).singleElement().satisfies(availability -> {
            assertThat(availability.externalId()).isEqualTo("54");
            assertThat(availability.stockLimit()).isEqualByComparingTo("0");
            assertThat(availability.observedAt())
                    .as("read as seconds this lands in the year 57000")
                    .isEqualTo(Instant.ofEpochMilli(1761202010781L));
        });
    }

    @Test
    @DisplayName("a fractional price is read exactly rather than through a double")
    void moneyDoesNotPassThroughFloatingPoint() {
        CatalogSnapshot snapshot = normalizer.normalize(
                List.of(product(11, "Espresso", "DISH", 8.5)), List.of(), List.of(), NOW, false, 1);

        assertThat(snapshot.products())
                .singleElement()
                .satisfies(product -> assertThat(product.priceMinor())
                        .as("for UZS a minor unit is a whole som, and truncating 8.5 would lose "
                                + "money quietly on every line")
                        .isEqualTo(9L));
    }

    @Test
    @DisplayName("a nested-set column is never read as an identifier")
    void categoriesAreReadByParentIdAndDepth() {
        CatalogSnapshot snapshot = normalizer.normalize(
                List.of(),
                List.of(Map.of(
                        "id",
                        12,
                        "parent_id",
                        3,
                        "name",
                        "Hot dishes",
                        "depth",
                        1,
                        "_lft",
                        44,
                        "_rgt",
                        51,
                        "status",
                        1)),
                List.of(),
                NOW,
                false,
                1);

        assertThat(snapshot.categories()).singleElement().satisfies(category -> {
            assertThat(category.externalId()).isEqualTo("12");
            assertThat(category.externalParentId()).isEqualTo("3");
            assertThat(category.depth()).isEqualTo(1);
            assertThat(category.raw())
                    .as("the nested-set columns are kept as raw evidence and read as nothing, "
                            + "because they renumber on every tree edit")
                    .containsKeys("_lft", "_rgt");
        });
    }

    @Test
    @DisplayName("a status of integer 1 is active, because the vendor spells booleans as numbers")
    void integerBooleansAreUnderstood() {
        Map<String, Object> raw = product(41, "Lagman", "DISH", 32000);
        raw.put("status", 0);
        raw.put("hidden", 1);

        CatalogSnapshot snapshot = normalizer.normalize(List.of(raw), List.of(), List.of(), NOW, false, 1);

        assertThat(snapshot.products()).singleElement().satisfies(product -> {
            assertThat(product.active()).isFalse();
            assertThat(product.hidden()).isTrue();
        });
    }

    private static Map<String, Object> product(int id, String name, String type, Number price) {
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("id", id);
        raw.put("name", name);
        raw.put("type", type);
        raw.put("price", price);
        raw.put("category_id", 1);
        raw.put("unit_id", 1);
        return raw;
    }
}
