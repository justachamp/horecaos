package uz.horecaos.platform.tenancy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Direction;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;

/**
 * The registry, as ADR 0149 specifies it: one entry per language, a lifecycle per tier, one spelling
 * of Uzbek, and no language activated by the registry's existence.
 */
class PlatformLocalesTests {

    @Test
    @DisplayName(
            "every entry says what a renderer needs about it: a script, a direction, a face, a rank and its own name")
    void everyEntryIsComplete() {
        for (PlatformLocale locale : PlatformLocales.all()) {
            assertThat(locale.script()).as(locale.tag() + " script").isNotNull();
            assertThat(locale.direction()).as(locale.tag() + " direction").isEqualTo(Direction.LTR);
            assertThat(locale.face()).as(locale.tag() + " face").isNotBlank();
            assertThat(locale.names()).as(locale.tag() + " names itself").containsKey(locale.tag());
            assertThat(locale.tag()).matches("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
        }
        assertThat(PlatformLocales.all().stream()
                        .map(PlatformLocale::fallbackRank)
                        .toList())
                .as("ranks are distinct and the list is in rank order, which is what the SQL ordering reads")
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("ru, uz-Latn and en are live in every tier; kk and ka are declared with none, so nothing is activated")
    void onlyTheThreeAreLive() {
        for (Tier tier : Tier.values()) {
            assertThat(PlatformLocales.activeTags(tier)).as("live in %s", tier).containsExactly("ru", "uz-Latn", "en");
        }
        assertThat(PlatformLocales.all().stream().map(PlatformLocale::tag)).contains("kk", "ka");
        assertThat(PlatformLocales.byTag("kk").orElseThrow().live())
                .as("declared, not live: the cost is visible in one place and costs nothing at runtime")
                .isFalse();
        assertThat(PlatformLocales.byTag("ka").orElseThrow().live()).isFalse();
        assertThat(PlatformLocales.parseActive("kk", Tier.CONTENT)).isEmpty();
        assertThat(PlatformLocales.isActive("ka", Tier.STAFF_UI)).isFalse();
    }

    @Test
    @DisplayName(
            "uz is not a tag: it parses as an input alias of uz-Latn and is stored only as that entry's catalogCode")
    void uzIsAnAliasAndNeverATag() {
        assertThat(PlatformLocales.byTag("uz")).as("not a tag").isEmpty();
        assertThat(PlatformLocales.all().stream().map(PlatformLocale::tag)).doesNotContain("uz");

        assertThat(PlatformLocales.parse("uz").orElseThrow().tag()).isEqualTo("uz-Latn");
        assertThat(PlatformLocales.parse("UZ").orElseThrow().tag())
                .as("any casing")
                .isEqualTo("uz-Latn");
        assertThat(PlatformLocales.parse("uz-latn").orElseThrow().tag())
                .as("a client's own casing is not a different language")
                .isEqualTo("uz-Latn");

        List<PlatformLocale> withADifferentCatalogCode = PlatformLocales.all().stream()
                .filter(locale -> !locale.catalogCode().equals(locale.tag()))
                .toList();
        assertThat(withADifferentCatalogCode)
                .as("there is exactly one place the catalog's own spelling survives")
                .singleElement()
                .satisfies(locale -> {
                    assertThat(locale.tag()).isEqualTo("uz-Latn");
                    assertThat(locale.catalogCode()).isEqualTo("uz");
                    assertThat(locale.inputAliases()).containsExactly("uz");
                });
        assertThat(PlatformLocales.byCatalogCode("uz").orElseThrow().tag()).isEqualTo("uz-Latn");
        assertThat(PlatformLocales.byCatalogCode("uz-Latn"))
                .as("the catalog's code is not the platform's tag")
                .isEmpty();
    }

    @Test
    @DisplayName("the fallback is registered, is the first in rank and is Russian")
    void theFallbackIsRegistered() {
        assertThat(PlatformLocales.fallback().tag()).isEqualTo("ru");
        assertThat(PlatformLocales.all().getFirst()).isEqualTo(PlatformLocales.fallback());
        assertThat(PlatformLocales.fallback().active(Tier.MESSAGES)).isTrue();
    }

    @Test
    @DisplayName("a region-qualified tag reads as its language; a script the entry is not written in does not")
    void resolveReadsRegionsButNotOtherScripts() {
        assertThat(PlatformLocales.resolve("en-US", Tier.MESSAGES)).isEqualTo("en");
        assertThat(PlatformLocales.resolve("uz_UZ", Tier.MESSAGES)).isEqualTo("uz-Latn");
        assertThat(PlatformLocales.resolve("uz-Latn-UZ", Tier.MESSAGES)).isEqualTo("uz-Latn");
        assertThat(PlatformLocales.resolve("uz-Cyrl", Tier.MESSAGES))
                .as("Uzbek has two scripts; Cyrillic Uzbek is not Latin Uzbek, which is why the tag carries one")
                .isEqualTo("ru");
        assertThat(PlatformLocales.resolve("kk", Tier.MESSAGES))
                .as("declared, not live: a customer who prefers it is written to in the fallback")
                .isEqualTo("ru");
        assertThat(PlatformLocales.resolve(null, Tier.MESSAGES)).isEqualTo("ru");
        assertThat(PlatformLocales.resolve("   ", Tier.STAFF_UI)).isEqualTo("ru");
        assertThat(PlatformLocales.resolve("xx", Tier.CONTENT)).isEqualTo("ru");
    }

    @Test
    @DisplayName("the SQL ordering ranks uz and uz-Latn alike, ru first, and refuses anything that is not a column")
    void fallbackOrderSqlRanksByRegistry() {
        assertThat(PlatformLocales.fallbackOrderSql("t.locale"))
                .isEqualTo("CASE t.locale WHEN 'ru' THEN 0 WHEN 'uz-Latn' THEN 1 WHEN 'uz' THEN 1"
                        + " WHEN 'en' THEN 2 WHEN 'kk' THEN 3 WHEN 'ka' THEN 4 ELSE 5 END");
        assertThatThrownBy(() -> PlatformLocales.fallbackOrderSql("t.locale; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformLocales.fallbackOrderSql("'ru'")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the display names of the live languages exist in every live language, for a picker")
    void liveLanguagesAreNamedInEveryLiveLanguage() {
        Set<String> live = Set.copyOf(PlatformLocales.activeTags(Tier.STAFF_UI));
        for (PlatformLocale locale : PlatformLocales.active(Tier.STAFF_UI)) {
            for (String displayedIn : live) {
                assertThat(locale.names())
                        .as("%s named in %s", locale.tag(), displayedIn)
                        .containsKey(displayedIn);
            }
        }
        assertThat(PlatformLocales.byTag("uz-Latn").orElseThrow().nameIn("ru")).isEqualTo("Узбекский");
        assertThat(PlatformLocales.byTag("uz-Latn").orElseThrow().ownName()).startsWith("O");
    }

    @Test
    @DisplayName("the platform's brand-less locale set follows the registry's content tier")
    void theBrandlessSetFollowsTheRegistry() {
        assertThat(TenantLocaleSet.platformFallback().locales()).isEqualTo(PlatformLocales.activeTags(Tier.CONTENT));
        assertThat(TenantLocaleSet.platformFallback().defaultLocale())
                .isEqualTo(PlatformLocales.fallback().tag());
    }

    /**
     * The glyph audit's table of alphabets (ADR 0149, Decision 7; gap-map row X.40). The frontend's
     * audit reads the font files the apps ship and fails when a language's face cannot draw its
     * letters, but it can only audit the languages it has an alphabet for. So the registry may not
     * declare a language without saying here, in the audit's own table, what the language is written
     * in and which face draws it, and a language made live must name a face that is actually shipped.
     */
    private static final Path ALPHABETS = Path.of("../frontend/tools/glyph-audit/alphabets.json");

    @Test
    @DisplayName(
            "every language the registry declares has a row in the glyph audit's alphabets, with the registry's own face, and a live language's face is shipped")
    void everyLanguageHasAnAlphabetRow() throws IOException {
        assertThat(ALPHABETS)
                .as("the glyph audit's table of alphabets sits beside the apps it audits")
                .exists();
        JsonNode table = JsonMapper.builder().build().readTree(Files.readString(ALPHABETS));
        JsonNode alphabets = table.path("alphabets");

        for (PlatformLocale locale : PlatformLocales.all()) {
            JsonNode row = alphabets.path(locale.tag());
            assertThat(row.isMissingNode())
                    .as("%s has no row in alphabets.json: say what it is written in before declaring it", locale.tag())
                    .isFalse();
            assertThat(row.path("face").asText())
                    .as("%s: the audit must read the face the registry names", locale.tag())
                    .isEqualTo(locale.face());
            assertThat(row.path("ranges").size())
                    .as("%s names the letters it needs", locale.tag())
                    .isPositive();
            if (locale.live()) {
                assertThat(table.path("faces")
                                .path(locale.face())
                                .path("shipped")
                                .asBoolean())
                        .as(
                                "%s is live, so its face %s must be one an app ships, or the browser paints it in a system face",
                                locale.tag(), locale.face())
                        .isTrue();
            }
        }

        List<String> tags =
                PlatformLocales.all().stream().map(PlatformLocale::tag).toList();
        assertThat(StreamSupport.stream(alphabets.propertyNames().spliterator(), false))
                .as("a row for a language the registry does not declare audits nothing")
                .allMatch(tags::contains);
    }
}
