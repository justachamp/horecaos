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
}
