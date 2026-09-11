package uz.horecaos.platform.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the sample menu contains (ADR 0099).
 *
 * <p>Ten items in four categories, in Uzbek, Russian and English, with no image
 * anywhere: a sample menu exists to prove the platform serves a menu, and a
 * reference to media nobody uploaded would fail {@code MEDIA_NOT_AVAILABLE} at
 * publication for a tenant that has not been shown the media library yet.
 *
 * <p>The items and their prices are {@code tools/seed-data/horecaos-tenant.json}'s,
 * narrowed to the ten that read as a menu without a photograph. They are
 * repeated here rather than read from that file because application code must
 * not depend on a developer tool's data — see ADR 0099's accepted trade-offs.
 *
 * <p>Every code, SKU and name says "sample" in the reader's own language. That
 * is the only thing standing between a tenant that activated on the sample and a
 * customer who orders from it, so it is not decoration.
 */
final class SampleMenuContent {

    /** What the sample catalog is called, in the authoring locale's language. */
    static final Map<String, String> CATALOG_NAME =
            Map.of("uz", "Namuna menyu", "ru", "Образец меню", "en", "Sample menu");

    static final List<SampleCategory> CATEGORIES = List.of(
            new SampleCategory(
                    "SAMPLE-MAINS",
                    0,
                    Map.of(
                            "uz", "Namuna: Asosiy taomlar",
                            "ru", "Образец: Основные блюда",
                            "en", "Sample: Main dishes")),
            new SampleCategory(
                    "SAMPLE-SHASHLIK",
                    1,
                    Map.of(
                            "uz", "Namuna: Shashlik",
                            "ru", "Образец: Шашлык",
                            "en", "Sample: Shashlik & grill")),
            new SampleCategory(
                    "SAMPLE-STARTERS",
                    2,
                    Map.of(
                            "uz", "Namuna: Salatlar va gazaklar",
                            "ru", "Образец: Салаты и закуски",
                            "en", "Sample: Salads & starters")),
            new SampleCategory(
                    "SAMPLE-DRINKS",
                    3,
                    Map.of("uz", "Namuna: Ichimliklar", "ru", "Образец: Напитки", "en", "Sample: Drinks")));

    static final List<SampleProduct> PRODUCTS = List.of(
            new SampleProduct(
                    "SAMPLE-PLOV",
                    "SAMPLE-MAINS",
                    "PORTION",
                    0,
                    38_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Toshkent oshi",
                                            "An'anaviy Toshkentcha palov, qo'y go'shti va sabzi bilan"),
                            "ru",
                                    new SampleText(
                                            "Образец: Ташкентский плов",
                                            "Традиционный ташкентский плов с бараниной и морковью"),
                            "en",
                                    new SampleText(
                                            "Sample: Tashkent plov",
                                            "Traditional Tashkent-style plov with lamb and carrots"))),
            new SampleProduct(
                    "SAMPLE-LAGMON",
                    "SAMPLE-MAINS",
                    "PORTION",
                    1,
                    32_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Lag'mon", "Qo'lda tortilgan xamir, go'sht va sabzavotlar bilan"),
                            "ru", new SampleText("Образец: Лагман", "Вытянутая вручную лапша с мясом и овощами"),
                            "en", new SampleText("Sample: Lagman", "Hand-pulled noodles with meat and vegetables"))),
            new SampleProduct(
                    "SAMPLE-MANTI",
                    "SAMPLE-MAINS",
                    "PORTION",
                    2,
                    30_000L,
                    Map.of(
                            "uz", new SampleText("Namuna: Manti", "Bug'da pishirilgan, qiyma go'shtli xamir cho'ntagi"),
                            "ru", new SampleText("Образец: Манты", "Паровые манты с мясной начинкой"),
                            "en", new SampleText("Sample: Manti", "Steamed dumplings filled with minced meat"))),
            new SampleProduct(
                    "SAMPLE-SHASHLIK-LAMB",
                    "SAMPLE-SHASHLIK",
                    "SKEWER",
                    3,
                    28_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Qo'y go'shtidan shashlik",
                                            "Ko'mirda pishirilgan, marinadlangan qo'y go'shti"),
                            "ru", new SampleText("Образец: Шашлык из баранины", "Маринованная баранина на углях"),
                            "en",
                                    new SampleText(
                                            "Sample: Lamb shashlik", "Marinated lamb skewers grilled over charcoal"))),
            new SampleProduct(
                    "SAMPLE-SHASHLIK-BEEF",
                    "SAMPLE-SHASHLIK",
                    "SKEWER",
                    4,
                    26_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Mol go'shtidan shashlik",
                                            "Ko'mirda pishirilgan, marinadlangan mol go'shti"),
                            "ru", new SampleText("Образец: Шашлык из говядины", "Маринованная говядина на углях"),
                            "en",
                                    new SampleText(
                                            "Sample: Beef shashlik", "Marinated beef skewers grilled over charcoal"))),
            new SampleProduct(
                    "SAMPLE-SHASHLIK-CHICKEN",
                    "SAMPLE-SHASHLIK",
                    "SKEWER",
                    5,
                    22_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Tovuq shashlik",
                                            "Ko'mirda pishirilgan, marinadlangan tovuq go'shti"),
                            "ru", new SampleText("Образец: Куриный шашлык", "Маринованное куриное филе на углях"),
                            "en",
                                    new SampleText(
                                            "Sample: Chicken shashlik",
                                            "Marinated chicken skewers grilled over charcoal"))),
            new SampleProduct(
                    "SAMPLE-SOMSA",
                    "SAMPLE-STARTERS",
                    "PIECE",
                    6,
                    12_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Go'shtli somsa",
                                            "Tandirda pishirilgan, mayda to'g'ralgan go'sht va piyoz bilan"),
                            "ru", new SampleText("Образец: Самса с мясом", "Самса из тандыра с рубленым мясом и луком"),
                            "en",
                                    new SampleText(
                                            "Sample: Meat somsa",
                                            "Tandoor-baked pastry filled with diced meat and onion"))),
            new SampleProduct(
                    "SAMPLE-SALAD-ACHICHUK",
                    "SAMPLE-STARTERS",
                    "PORTION",
                    7,
                    15_000L,
                    Map.of(
                            "uz",
                                    new SampleText(
                                            "Namuna: Achchiq-chuchuk salati",
                                            "Pomidor, piyoz va ko'katlardan tayyorlangan yangi salat"),
                            "ru",
                                    new SampleText(
                                            "Образец: Салат аччик-чучук", "Свежий салат из помидоров, лука и зелени"),
                            "en", new SampleText("Sample: Achichuk salad", "Fresh tomato, onion and herb salad"))),
            new SampleProduct(
                    "SAMPLE-NON",
                    "SAMPLE-STARTERS",
                    "PIECE",
                    8,
                    6_000L,
                    Map.of(
                            "uz", new SampleText("Namuna: Issiq non", "Tandirda pishirilgan an'anaviy o'zbek noni"),
                            "ru", new SampleText("Образец: Горячая лепёшка", "Традиционная узбекская лепёшка"),
                            "en", new SampleText("Sample: Fresh non bread", "Traditional Uzbek tandoor-baked bread"))),
            new SampleProduct(
                    "SAMPLE-TEA-GREEN",
                    "SAMPLE-DRINKS",
                    "POT",
                    9,
                    8_000L,
                    Map.of(
                            "uz", new SampleText("Namuna: Ko'k choy", "An'anaviy choynakda tortilgan ko'k choy"),
                            "ru", new SampleText("Образец: Зелёный чай", "Зелёный чай в традиционном чайнике"),
                            "en", new SampleText("Sample: Green tea (pot)", "Green tea served in a traditional pot"))));

    private SampleMenuContent() {}

    /** The locales every sample entity is named in. */
    static List<String> locales() {
        return List.of("uz", "ru", "en");
    }

    /**
     * A name in a locale the sample does not carry falls back to Uzbek.
     *
     * <p>The {@code requireNonNull} is not defensive: every map above is a
     * literal that contains {@code uz}, so a missing fallback is a mistake in
     * this file rather than a runtime condition, and failing loudly here beats
     * writing a null name into a translation row.
     */
    static String catalogName(String locale) {
        return Objects.requireNonNull(CATALOG_NAME.getOrDefault(locale, CATALOG_NAME.get("uz")));
    }

    record SampleCategory(String code, int sortOrder, Map<String, String> names) {

        String name(String locale) {
            return Objects.requireNonNull(names.getOrDefault(locale, names.get("uz")));
        }
    }

    /**
     * One sample dish.
     *
     * @param sku the SKU is the code: a sample item is unique in its brand by
     *            construction, and a second naming scheme would be one more thing
     *            a retry has to match on
     */
    record SampleProduct(
            String code,
            String categoryCode,
            String unitCode,
            int sortOrder,
            long amountMinor,
            Map<String, SampleText> text) {

        String sku() {
            return code;
        }

        SampleText text(String locale) {
            return Objects.requireNonNull(text.getOrDefault(locale, text.get("uz")));
        }
    }

    record SampleText(String name, String description) {}
}
