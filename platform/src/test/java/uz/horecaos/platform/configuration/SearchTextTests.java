package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The matching skeleton customers' typing and tenants' menus are both reduced to
 * (ADR 0069). The failure each test guards is the one a customer sees: "no, we do
 * not have plov" about a dish the menu names in another script.
 */
class SearchTextTests {

    @Test
    @DisplayName("a Latin Uzbek word finds the Cyrillic name a Russian menu gives the same dish")
    void latinFindsCyrillic() {
        assertThat(SearchText.containsAll(List.of("plov"), "Плов самаркандский"))
                .isTrue();
        assertThat(SearchText.containsAll(List.of("shashlik"), "Шашлык из баранины"))
                .isTrue();
        assertThat(SearchText.containsAll(List.of("lagman"), "Лагман")).isTrue();
    }

    @Test
    @DisplayName("Cyrillic finds Latin, and an Uzbek apostrophe or x/q spelling does not matter")
    void cyrillicFindsLatinAndSpellingsFold() {
        assertThat(SearchText.containsAll(List.of("самса"), "Samsa with beef")).isTrue();
        assertThat(SearchText.containsAll(List.of("o'tkir"), "Otkir somsa")).isTrue();
        assertThat(SearchText.normalize("Qo'y go'shti xamir")).isEqualTo(SearchText.normalize("Қўй гўшти хамир"));
    }

    @Test
    @DisplayName("every term must name a word of the dish, not just one of them")
    void everyTermMustMatch() {
        assertThat(SearchText.containsAll(List.of("plov", "beef"), "Plov")).isFalse();
        assertThat(SearchText.containsAll(List.of("plov", "beef"), "Plov with beef"))
                .isTrue();
    }

    @Test
    @DisplayName("an empty term list names nothing, so a search with no dish matches no dish")
    void emptyTermsMatchNothing() {
        assertThat(SearchText.containsAll(List.of(), "Plov")).isFalse();
    }

    @Test
    @DisplayName("a word matches by prefix from three letters, and one slip of the finger from five")
    void prefixAndOneSlip() {
        assertThat(SearchText.wordMatches("plov", "plovlar")).isTrue();
        assertThat(SearchText.wordMatches("pl", "plov")).isFalse();
        assertThat(SearchText.wordMatches("shashlk", "shashlik")).isTrue();
        assertThat(SearchText.wordMatches("burger", "burgir")).isTrue();
        assertThat(SearchText.wordMatches("burger", "burner")).isTrue();
        assertThat(SearchText.wordMatches("burger", "bunker")).isFalse();
    }

    @Test
    @DisplayName("a phrase matches consecutive words, a stem of four letters matching every inflection")
    void phrases() {
        assertThat(SearchText.containsPhrase(SearchText.normalize("у меня жалоба на доставку"), "жалоб"))
                .isTrue();
        assertThat(SearchText.containsPhrase(SearchText.normalize("сколько стоит плов"), "сколько стоит"))
                .isTrue();
        assertThat(SearchText.containsPhrase(SearchText.normalize("стоит сколько плов"), "сколько стоит"))
                .isFalse();
    }
}
