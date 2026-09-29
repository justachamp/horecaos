package uz.horecaos.platform.tenancy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules a row that keeps its wording in three fixed columns <em>and</em> a
 * per-locale table follows (row 10.12's one-release transition).
 */
class LocalizedLabelsTests {

    @Test
    @DisplayName("a read reports each locale once: the column wins over a mirrored translation row")
    void mergeNeverDuplicatesAndTheColumnWins() {
        // The transition writes the triple to BOTH homes. If the mirror row is ever
        // stale (a hand edit, a column updated by an older writer) the merged answer
        // must be the column's, and 'ru' must appear exactly once.
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("ru", "STALE mirror");
        rows.put("kaa", "Piyazsiz");
        rows.put("en", "STALE mirror en");

        Map<String, String> merged = LocalizedLabels.merge("Без лука", "Piyozsiz", "No onion", rows);

        assertThat(merged)
                .containsExactly(
                        Map.entry("ru", "Без лука"),
                        Map.entry("uz-Latn", "Piyozsiz"),
                        Map.entry("en", "No onion"),
                        Map.entry("kaa", "Piyazsiz"));
    }

    @Test
    @DisplayName("a blank column is skipped so the merged map never carries an empty wording")
    void mergeSkipsABlankColumn() {
        Map<String, String> merged = LocalizedLabels.merge("Без лука", "  ", "No onion", Map.of());

        assertThat(merged).containsOnlyKeys("ru", "en");
    }

    @Test
    @DisplayName("the map overlays the legacy fields, blanks are dropped and wording is trimmed")
    void suppliedOverlaysAndTrims() {
        Map<String, String> supplied =
                LocalizedLabels.supplied("старое", " ", "Old", Map.of("ru", "  новое  ", "kaa", "Yangi"), 120);

        assertThat(supplied)
                .containsEntry("ru", "новое")
                .containsEntry("en", "Old")
                .containsEntry("kaa", "Yangi")
                .doesNotContainKey("uz-Latn");
    }

    @Test
    @DisplayName("a bare 'uz' is refused: it would be a second, never-read Uzbek beside uz-Latn")
    void aBareUzIsRefused() {
        assertThatThrownBy(() -> LocalizedLabels.supplied(null, null, null, Map.of("uz", "Salom"), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uz-Latn");
    }

    @Test
    @DisplayName("a case variant of uz-Latn lands on the platform's Uzbek, never on a second, never-read one")
    void aCaseVariantOfUzLatnIsCanonicalised() {
        // 'uz-latn' passes the well-formedness pattern (and the CHECK constraints) but is
        // neither the column's 'uz-Latn' nor a bare 'uz': stored as-is it would be listed
        // as another language beside the label_uz column the storefront and checkout read.
        assertThat(LocalizedLabels.supplied(null, null, null, Map.of("uz-latn", "Piyozsiz"), 120))
                .containsExactly(Map.entry("uz-Latn", "Piyozsiz"));
        assertThat(LocalizedLabels.supplied(null, null, null, Map.of("uz-LATN", "Piyozsiz"), 120))
                .containsExactly(Map.entry("uz-Latn", "Piyozsiz"));
    }

    @Test
    @DisplayName("a case variant overlays the legacy field it is the same language as")
    void aCaseVariantOverlaysTheLegacyField() {
        assertThat(LocalizedLabels.supplied(null, "eski", null, Map.of("uz-latn", "yangi"), 120))
                .containsExactly(Map.entry("uz-Latn", "yangi"));
    }

    @Test
    @DisplayName("a stored case variant of a triple locale is a stale mirror, ignored like the others")
    void mergeIgnoresACaseVariantOfATripleLocale() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("uz-latn", "STALE mirror");
        rows.put("kaa", "Piyazsiz");

        assertThat(LocalizedLabels.merge("Без лука", "Piyozsiz", "No onion", rows))
                .containsOnlyKeys("ru", "uz-Latn", "en", "kaa")
                .containsEntry("uz-Latn", "Piyozsiz");
    }

    @Test
    @DisplayName("a malformed locale tag is refused before it reaches the CHECK constraint")
    void aMalformedLocaleIsRefused() {
        assertThatThrownBy(() -> LocalizedLabels.supplied(null, null, null, Map.of("RU_ru", "x"), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RU_ru");
    }

    @Test
    @DisplayName("wording longer than the column is refused, naming the locale")
    void overLongWordingIsRefused() {
        assertThatThrownBy(() -> LocalizedLabels.supplied(null, null, "x".repeat(121), Map.of(), 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("en");
    }

    @Test
    @DisplayName("pick answers the first wanted locale the set has, a locale beyond the triple included")
    void pickFollowsThePreferenceOrder() {
        Map<String, String> merged = LocalizedLabels.merge(
                "Без лука", "Piyozsiz", "No onion", new LinkedHashMap<>(Map.of("kaa", "Piyazsiz")));

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
}
