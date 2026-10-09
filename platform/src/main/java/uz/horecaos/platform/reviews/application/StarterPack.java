package uz.horecaos.platform.reviews.application;

import java.util.List;
import java.util.Map;
import uz.horecaos.platform.reviews.domain.TagCategory;
import uz.horecaos.platform.reviews.domain.TagSentiment;

/**
 * The twelve starter feedback tags a tenant may import once and then edit (ADR 0159, Specification).
 *
 * <p>Six praise and six complain. Category follows the code's prefix -- {@code food_} is {@code FOOD},
 * {@code service_} is {@code SERVICE}, {@code delivery_} is {@code DELIVERY_TIME} -- and
 * {@code order_incomplete} is {@code OTHER}. None is {@code COURIER}: a courier is the person a review
 * most often names and the one person the platform may not rate (ADR 0042), so the pack offers no tag
 * about one.
 *
 * <p>The wording is written in Russian, Uzbek (Latin) and English and never machine-translated
 * ({@code ProviderCategory} has no translation category). It is product's to confirm before a pilot
 * tenant imports it; a tenant can edit every label after the import, but the code, category and
 * sentiment are frozen once a review uses the tag.
 */
final class StarterPack {

    /** One starter tag. */
    record Tag(String code, TagCategory category, TagSentiment sentiment, Map<String, String> labels) {}

    static final List<Tag> TAGS = List.of(
            positive("food_tasty", TagCategory.FOOD, "Вкусно", "Mazali", "Tasty"),
            positive("food_fresh", TagCategory.FOOD, "Свежее", "Yangi", "Fresh"),
            positive("food_portion", TagCategory.FOOD, "Хорошая порция", "Porsiya yaxshi", "Generous portion"),
            positive(
                    "service_friendly",
                    TagCategory.SERVICE,
                    "Приветливый персонал",
                    "Xodimlar xushmuomala",
                    "Friendly staff"),
            positive(
                    "delivery_on_time",
                    TagCategory.DELIVERY_TIME,
                    "Доставили вовремя",
                    "Oʻz vaqtida yetkazildi",
                    "On time"),
            positive(
                    "delivery_careful",
                    TagCategory.DELIVERY_TIME,
                    "Аккуратная доставка",
                    "Ehtiyotkorlik bilan yetkazildi",
                    "Carefully delivered"),
            negative("food_cold", TagCategory.FOOD, "Холодное", "Sovuq", "Cold"),
            negative("food_wrong_item", TagCategory.FOOD, "Не то блюдо", "Boshqa taom keltirildi", "Wrong item"),
            negative("service_rude", TagCategory.SERVICE, "Грубое обслуживание", "Qoʻpol xizmat", "Rude service"),
            negative("delivery_late", TagCategory.DELIVERY_TIME, "Доставили поздно", "Kech yetkazildi", "Late"),
            negative(
                    "delivery_damaged",
                    TagCategory.DELIVERY_TIME,
                    "Повреждённая упаковка",
                    "Qadoq shikastlangan",
                    "Damaged packaging"),
            negative(
                    "order_incomplete",
                    TagCategory.OTHER,
                    "Заказ неполный",
                    "Buyurtma toʻliq emas",
                    "Incomplete order"));

    private static Tag positive(String code, TagCategory category, String ru, String uz, String en) {
        return new Tag(code, category, TagSentiment.POSITIVE, labels(ru, uz, en));
    }

    private static Tag negative(String code, TagCategory category, String ru, String uz, String en) {
        return new Tag(code, category, TagSentiment.NEGATIVE, labels(ru, uz, en));
    }

    private static Map<String, String> labels(String ru, String uz, String en) {
        return Map.of("ru", ru, "uz-Latn", uz, "en", en);
    }

    private StarterPack() {}
}
