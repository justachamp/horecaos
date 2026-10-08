package uz.horecaos.platform.assistant.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.configuration.SearchText;

/**
 * Turning an open-ended question into the platform reads it needs (ADR 0069:
 * "the value here is not fluency; the platform already has the answers. The
 * value is turning an open-ended question into the right platform read, and
 * refusing when there isn't one").
 *
 * <p>Deterministic on purpose. Deciding <em>which read</em> a question needs is
 * the part of this feature that must be explainable, testable and free: a lexicon
 * in Russian, Uzbek and English over the matching skeleton ({@link SearchText})
 * can be read, extended by a pull request, and gated by the golden-set suite,
 * where a model's classification can be none of those. The model is asked only
 * to compose from what the reads returned.
 *
 * <p>A question that matches nothing is not an error: it classifies as {@link
 * RetrievalKind#KNOWLEDGE} alone, the tenant's own authored answers get their
 * chance, and if they have none the assistant refuses and offers a person.
 *
 * <p>Lexicon entries are phrases in ordinary spelling, reduced to skeletons at
 * class initialisation; a phrase matches consecutive words, a word of four or
 * more skeleton letters matching any word that starts with it, so one stem
 * ("жалоб") covers every inflection a customer types.
 */
public final class QuestionClassifier {

    private static final Map<RetrievalKind, List<String>> LEXICON = Map.of(
            RetrievalKind.PRICE,
            List.of(
                    "сколько стоит",
                    "сколько стоят",
                    "сколько будет",
                    "цена",
                    "цены",
                    "цену",
                    "ценник",
                    "почем",
                    "почём",
                    "стоимость",
                    "how much",
                    "price",
                    "prices",
                    "cost",
                    "narx",
                    "narxi",
                    "narxlari",
                    "qancha",
                    "necha pul",
                    "necha so'm",
                    "necha sum",
                    "nech pul",
                    "нарх",
                    "қанча",
                    "канча"),
            RetrievalKind.AVAILABILITY,
            List.of(
                    "есть ли",
                    "есть",
                    "в наличии",
                    "наличие",
                    "имеется",
                    "доступен",
                    "доступна",
                    "do you have",
                    "have you got",
                    "available",
                    "in stock",
                    "availability",
                    "bormi",
                    "bor mi",
                    "bormikan",
                    "mavjud",
                    "борми",
                    "бор ми"),
            RetrievalKind.BRANCHES,
            List.of(
                    "где вы",
                    "где находитесь",
                    "где находится",
                    "где ваш",
                    "где вас",
                    "адрес",
                    "филиал",
                    "как добраться",
                    "как доехать",
                    "ориентир",
                    "where are you",
                    "where is your",
                    "where can i find",
                    "where do you",
                    "address",
                    "branch",
                    "branches",
                    "location",
                    "how to get to",
                    "qayerda",
                    "qayerdasiz",
                    "manzil",
                    "filial",
                    "adres",
                    "мантзил",
                    "манзил"),
            RetrievalKind.HOURS,
            List.of(
                    "до скольки",
                    "работаете",
                    "работает",
                    "часы работы",
                    "график работы",
                    "режим работы",
                    "время работы",
                    "открыты",
                    "открыто",
                    "закрыты",
                    "закрыто",
                    "во сколько",
                    "opening hours",
                    "open",
                    "opens",
                    "closed",
                    "closing",
                    "close at",
                    "hours",
                    "until when",
                    "what time",
                    "ish vaqti",
                    "ishlaysiz",
                    "ishlaydi",
                    "ochiq",
                    "yopiq",
                    "soat nechagacha",
                    "nechagacha",
                    "qachongacha",
                    "qachon ochil",
                    "qachon yopil"),
            RetrievalKind.COVERAGE,
            List.of(
                    "доставка",
                    "доставку",
                    "доставляете",
                    "доставите",
                    "доставляют",
                    "зона доставки",
                    "привезете",
                    "привезёте",
                    "привезти",
                    "deliver",
                    "delivery",
                    "do you deliver",
                    "dostavka",
                    "yetkazib",
                    "yetkazish",
                    "yetkazasiz",
                    "eltib",
                    "олиб бориш"),
            RetrievalKind.ORDER_STATUS,
            List.of(
                    "мой заказ",
                    "мои заказ",
                    "моего заказа",
                    "моему заказу",
                    "статус заказа",
                    "где заказ",
                    "где мой",
                    "где моя",
                    "где моё",
                    "где мое",
                    "когда привезут",
                    "когда доставят",
                    "когда будет заказ",
                    "когда будет готов",
                    "заказ опаздывает",
                    "my order",
                    "order status",
                    "status of my",
                    "where is my",
                    "where's my",
                    "when will my",
                    "buyurtmam",
                    "buyurtma qani",
                    "buyurtmamning",
                    "zakazim",
                    "zakaz qani",
                    "qachon keladi",
                    "qachon yetib"));

    private static final Map<EscalationTopic, List<String>> ESCALATION = Map.of(
            EscalationTopic.COMPLAINT,
            List.of(
                    "жалоб",
                    "недоволен",
                    "недовольна",
                    "ужасн",
                    "отвратительн",
                    "обман",
                    "испорчен",
                    "отравил",
                    "хамств",
                    "complaint",
                    "complain",
                    "terrible",
                    "disgusting",
                    "unhappy",
                    "unacceptable",
                    "rotten",
                    "cold food",
                    "shikoyat",
                    "norozi",
                    "yomon",
                    "sifatsiz",
                    "ayanchli"),
            EscalationTopic.REFUND,
            List.of(
                    "возврат",
                    "верните деньги",
                    "вернуть деньги",
                    "вернуть оплату",
                    "компенсац",
                    "refund",
                    "money back",
                    "chargeback",
                    "reimburse",
                    "pulni qaytar",
                    "pulimni qaytar",
                    "qaytarib bering",
                    "kompensatsiya"),
            EscalationTopic.HUMAN_REQUESTED,
            List.of(
                    "оператор",
                    "менеджер",
                    "администратор",
                    "живой человек",
                    "живого человека",
                    "с человеком",
                    "позовите",
                    "соедините",
                    "speak to someone",
                    "talk to someone",
                    "human",
                    "real person",
                    "operator",
                    "manager",
                    "agent",
                    "staff",
                    "odam bilan",
                    "inson",
                    "menejer",
                    "administrator",
                    "ulang"));

    /**
     * Skeleton words that frame a question and name no dish. Written in ordinary
     * spelling like the lexicons and reduced the same way.
     */
    private static final Set<String> FILLER = skeletonSet(
            // Russian
            "привет",
            "здравствуйте",
            "добрый",
            "день",
            "вечер",
            "утро",
            "пожалуйста",
            "подскажите",
            "скажите",
            "можно",
            "мне",
            "нужно",
            "хочу",
            "заказать",
            "вас",
            "вы",
            "у",
            "а",
            "и",
            "или",
            "ли",
            "бы",
            "это",
            "то",
            "по",
            "на",
            "в",
            "с",
            "как",
            "где",
            "когда",
            "что",
            "какая",
            "какой",
            "какие",
            "сегодня",
            "сейчас",
            "сколько",
            "стоит",
            "стоят",
            "есть",
            "ваш",
            "ваша",
            "ваше",
            "ваши",
            "нас",
            "мы",
            "я",
            "спасибо",
            "порция",
            "порцию",
            "штука",
            "шт",
            "за",
            "для",
            "от",
            "до",
            "из",
            "же",
            "ещё",
            "еще",
            "такой",
            "такая",
            "весь",
            "все",
            "всё",
            // Uzbek (Latin)
            "salom",
            "assalomu",
            "alaykum",
            "aleykum",
            "iltimos",
            "menga",
            "kerak",
            "bugun",
            "hozir",
            "va",
            "bu",
            "shu",
            "bilan",
            "uchun",
            "nima",
            "qayerda",
            "qachon",
            "qanday",
            "bormi",
            "bor",
            "mi",
            "mikan",
            "bormikan",
            "ekan",
            "necha",
            "nechta",
            "qancha",
            "narx",
            "narxi",
            "pul",
            "som",
            "sum",
            "so'm",
            "sizda",
            "sizlarda",
            "bizga",
            "men",
            "siz",
            "kechirasiz",
            "rahmat",
            "yoq",
            "ha",
            "aytib",
            "bering",
            "olsam",
            "bo'ladimi",
            "boladimi",
            // English
            "hi",
            "hello",
            "hey",
            "please",
            "do",
            "does",
            "you",
            "have",
            "how",
            "much",
            "is",
            "the",
            "a",
            "an",
            "are",
            "what",
            "price",
            "prices",
            "cost",
            "of",
            "for",
            "today",
            "now",
            "available",
            "stock",
            "in",
            "your",
            "there",
            "any",
            "can",
            "i",
            "get",
            "want",
            "order",
            "tell",
            "me",
            "about",
            "it",
            "this",
            "that",
            "and",
            "or",
            "to",
            "got",
            "on",
            "at",
            "with",
            "thanks",
            "thank",
            "would",
            "like",
            "much",
            "many");

    private QuestionClassifier() {}

    /** The placeholders {@code PiiEgressGuard} leaves in redacted text: they are not words anybody asked. */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\[(?:phone|email|handle|address)]");

    public static QuestionClassification classify(String text) {
        String skeleton = SearchText.normalize(PLACEHOLDER.matcher(text).replaceAll(" "));

        EscalationTopic escalation = escalationOf(skeleton);
        Set<RetrievalKind> kinds = EnumSet.of(RetrievalKind.KNOWLEDGE);
        for (Map.Entry<RetrievalKind, List<String>> entry : LEXICON.entrySet()) {
            if (matchesAny(skeleton, entry.getValue())) {
                kinds.add(entry.getKey());
            }
        }
        // "When will my delivery arrive" is a question about an order, not about
        // whether a branch delivers: the order is what answers it.
        if (kinds.contains(RetrievalKind.ORDER_STATUS)) {
            kinds.remove(RetrievalKind.COVERAGE);
            kinds.remove(RetrievalKind.AVAILABILITY);
            kinds.remove(RetrievalKind.PRICE);
        }
        // "Do you have plov" and "how much is plov" are about the menu whichever
        // word carries the verb; a bare "are you open" is about hours and nothing else.
        if (kinds.contains(RetrievalKind.PRICE)
                && kinds.contains(RetrievalKind.HOURS)
                && dishTerms(skeleton).isEmpty()) {
            kinds.remove(RetrievalKind.PRICE);
        }
        return new QuestionClassification(kinds, escalation, dishTerms(skeleton), skeleton);
    }

    /**
     * The words of a text that carry meaning of their own once the framing and the
     * lexicon's words are removed -- what a knowledge entry's question is about, and
     * what a customer's question is about, compared on equal terms.
     */
    public static List<String> significantTerms(String text) {
        return dishTerms(SearchText.normalize(PLACEHOLDER.matcher(text).replaceAll(" ")));
    }

    private static @Nullable EscalationTopic escalationOf(String skeleton) {
        // Ordered by what a person must do first: a refund is a complaint with a
        // remedy asked for, and a customer asking for a person gets one whatever else
        // they wrote.
        for (EscalationTopic topic :
                List.of(EscalationTopic.REFUND, EscalationTopic.COMPLAINT, EscalationTopic.HUMAN_REQUESTED)) {
            if (matchesAny(skeleton, java.util.Objects.requireNonNull(ESCALATION.get(topic)))) {
                return topic;
            }
        }
        return null;
    }

    /**
     * What is left of the question once the framing words and the lexicon's own
     * words are removed: what the customer called the dish.
     */
    private static List<String> dishTerms(String skeleton) {
        List<String> terms = new ArrayList<>();
        for (String word : SearchText.tokens(skeleton)) {
            if (FILLER.contains(word) || isLexiconWord(word) || word.chars().allMatch(Character::isDigit)) {
                continue;
            }
            terms.add(word);
        }
        return terms;
    }

    private static boolean isLexiconWord(String word) {
        for (List<String> phrases : LEXICON.values()) {
            for (String phrase : phrases) {
                for (String lexiconWord : SearchText.tokens(phrase)) {
                    if (word.equals(lexiconWord) || (lexiconWord.length() >= 4 && word.startsWith(lexiconWord))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matchesAny(String skeleton, List<String> phrases) {
        for (String phrase : phrases) {
            if (SearchText.containsPhrase(skeleton, phrase)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> skeletonSet(String... words) {
        Set<String> set = new java.util.HashSet<>();
        for (String word : words) {
            set.addAll(SearchText.tokens(word));
        }
        return Set.copyOf(set);
    }
}
