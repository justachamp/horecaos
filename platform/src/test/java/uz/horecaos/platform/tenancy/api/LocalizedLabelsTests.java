package uz.horecaos.platform.tenancy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules a per-locale wording follows (row 10.12): the map is the only shape of a wording. */
class LocalizedLabelsTests {

    @Test
    @DisplayName("a bare 'uz' is refused: it would be a second, never-read Uzbek beside uz-Latn")
    void aBareUzIsRefused() {
        assertThatThrownBy(() -> LocalizedLabels.supplied(Map.of("uz", "Salom"), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uz-Latn");
    }

    @Test
    @DisplayName("a case variant of uz-Latn lands on the platform's Uzbek, never on a second, never-read one")
    void aCaseVariantOfUzLatnIsCanonicalised() {
        // 'uz-latn' passes the well-formedness pattern (and the CHECK constraints) but is
        // neither the registry's 'uz-Latn' nor a bare 'uz': stored as-is it would be listed
        // as another language beside the one the storefront and checkout read.
        assertThat(LocalizedLabels.supplied(Map.of("uz-latn", "Piyozsiz"), 120))
                .containsExactly(Map.entry("uz-Latn", "Piyozsiz"));
        assertThat(LocalizedLabels.supplied(Map.of("uz-LATN", "Piyozsiz"), 120))
                .containsExactly(Map.entry("uz-Latn", "Piyozsiz"));
    }

    @Test
    @DisplayName("a malformed locale tag is refused before it reaches the CHECK constraint")
    void aMalformedLocaleIsRefused() {
        assertThatThrownBy(() -> LocalizedLabels.supplied(Map.of("RU_ru", "x"), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RU_ru");
    }

    @Test
    @DisplayName("wording longer than the column is refused, naming the locale")
    void overLongWordingIsRefused() {
        assertThatThrownBy(() -> LocalizedLabels.supplied(Map.of("en", "x".repeat(121)), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("en");
    }

    @Test
    @DisplayName("pick answers the first wanted locale the set has, a locale beyond the triple included")
    void pickFollowsThePreferenceOrder() {
        Map<String, String> merged = LocalizedLabels.merge(new LinkedHashMap<>(
                Map.of("ru", "Без лука", "uz-Latn", "Piyozsiz", "en", "No onion", "kaa", "Piyazsiz")));

        assertThat(LocalizedLabels.pick(merged, java.util.List.of("kaa", "ru"))).isEqualTo("Piyazsiz");
        assertThat(LocalizedLabels.pick(merged, java.util.List.of("de", "uz-latn", "ru")))
                .as("an unknown locale is skipped and a case variant of a triple locale matches")
                .isEqualTo("Piyozsiz");
    }

    @Test
    @DisplayName("pick falls through to the set's first wording rather than blank, and to null only for an empty set")
    void pickFallsThroughToWhateverThereIs() {
        Map<String, String> onlyKaa = Map.of("kaa", "Piyazsiz");

        assertThat(LocalizedLabels.pick(onlyKaa, java.util.List.of("ru", "en"))).isEqualTo("Piyazsiz");
        assertThat(LocalizedLabels.pick(Map.of(), java.util.List.of("ru"))).isNull();
    }

    // ---------------------------------------- since V0678: the map is the only home of the wording

    @Test
    @DisplayName("a map-only merge puts the platform triple first in its order and every other locale sorted after it")
    void mapOnlyMergeOrdersTheTripleFirst() {
        Map<String, String> rows = new java.util.LinkedHashMap<>();
        rows.put("kaa", "Piyazsiz");
        rows.put("en", "No onion");
        rows.put("ka", "ყველა");
        rows.put("uz-Latn", "Piyozsiz");
        rows.put("ru", "Без лука");

        assertThat(LocalizedLabels.merge(rows))
                .containsExactly(
                        Map.entry("ru", "Без лука"),
                        Map.entry("uz-Latn", "Piyozsiz"),
                        Map.entry("en", "No onion"),
                        Map.entry("ka", "ყველა"),
                        Map.entry("kaa", "Piyazsiz"));
    }

    @Test
    @DisplayName(
            "a case variant of a locale never shadows the row stored under the canonical tag, and a blank is dropped")
    void mapOnlyMergeKeepsTheCanonicalRowAndDropsABlank() {
        Map<String, String> rows = new java.util.LinkedHashMap<>();
        rows.put("uz-latn", "variant");
        rows.put("uz-Latn", "Piyozsiz");
        rows.put("en", "  ");

        assertThat(LocalizedLabels.merge(rows)).containsExactly(Map.entry("uz-Latn", "Piyozsiz"));
    }

    @Test
    @DisplayName("a case variant with no canonical row is reported once, under the canonical tag")
    void aLoneCaseVariantIsReportedUnderTheCanonicalTag() {
        assertThat(LocalizedLabels.merge(Map.of("uz-LATN", "Piyozsiz")))
                .containsExactly(Map.entry("uz-Latn", "Piyozsiz"));
    }

    @Test
    @DisplayName("a map-only write refuses a bare uz and a malformed tag, trims, and drops a blank")
    void mapOnlySuppliedAppliesTheSameRules() {
        assertThat(LocalizedLabels.supplied(Map.of("ru", "  Без лука ", "en", " "), 120))
                .containsExactly(Map.entry("ru", "Без лука"));
        assertThatThrownBy(() -> LocalizedLabels.supplied(Map.of("uz", "x"), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uz-Latn");
        assertThatThrownBy(() -> LocalizedLabels.supplied(Map.of("RU_ru", "x"), 120))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalizedLabels.supplied(Map.of("ru", "x".repeat(121)), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("121");
    }
}
