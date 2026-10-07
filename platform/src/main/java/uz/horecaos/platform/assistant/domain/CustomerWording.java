package uz.horecaos.platform.assistant.domain;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every sentence the assistant says that a model did not write, in the three
 * languages.
 *
 * <p>A refusal, a handoff and the disclosure are words the platform owns: they
 * must be true whether or not a provider is reachable, they must not vary with a
 * prompt, and the one about whether anyone is there to answer ({@link
 * #handoff}) is a statement of fact about the world that a model has no way to
 * know. So none of them passes through the model.
 *
 * <p>Plain text, no markup: the channel renders it as it is.
 */
public final class CustomerWording {

    private static final Map<String, String> DISCLOSURE = Map.of(
            "en",
            "I'm an automated assistant. Your question is processed by an external AI service with personal "
                    + "details removed. Please don't send phone numbers or addresses here.",
            "ru",
            "Я автоматический помощник. Ваш вопрос обрабатывает внешний ИИ-сервис, личные данные при этом "
                    + "удаляются. Пожалуйста, не отправляйте здесь телефоны и адреса.",
            "uz",
            "Men avtomatik yordamchiman. Savolingiz tashqi sun'iy intellekt xizmatida shaxsiy ma'lumotlarsiz "
                    + "qayta ishlanadi. Iltimos, bu yerga telefon raqami va manzil yubormang.");

    private static final Map<String, String> CANNOT_ANSWER = Map.of(
            "en", "I can't answer that reliably, so I won't guess.",
            "ru", "Не могу ответить на это достоверно, поэтому не буду гадать.",
            "uz", "Bunga ishonchli javob bera olmayman, shuning uchun taxmin qilmayman.");

    private static final Map<String, String> NOT_NOW = Map.of(
            "en", "I can't answer right now.",
            "ru", "Сейчас я не могу ответить.",
            "uz", "Hozir javob bera olmayman.");

    private static final Map<String, String> SORRY = Map.of(
            "en", "I'm sorry this happened. A person needs to look into it.",
            "ru", "Сожалею, что так вышло. Этим должен заняться человек.",
            "uz", "Afsuski, shunday bo'ldi. Buni inson ko'rib chiqishi kerak.");

    private static final Map<String, String> OF_COURSE = Map.of(
            "en", "Of course.",
            "ru", "Конечно.",
            "uz", "Albatta.");

    private static final Map<String, String> PERSON_ONLINE = Map.of(
            "en", "A person from our team will reply here.",
            "ru", "Сотрудник нашей команды ответит вам здесь.",
            "uz", "Jamoamiz xodimi shu yerda javob beradi.");

    private static final Map<String, String> NOBODY_ONLINE = Map.of(
            "en",
            "Nobody from our team is online right now. Your message is saved, and a person will reply when "
                    + "the team is back.",
            "ru",
            "Сейчас никого из команды нет онлайн. Ваше сообщение сохранено, человек ответит, когда команда вернётся.",
            "uz",
            "Hozir jamoamizdan hech kim onlayn emas. Xabaringiz saqlandi, jamoa qaytganda inson javob beradi.");

    private CustomerWording() {}

    /** Said once, before the first assistant answer of a conversation. */
    public static String disclosure(String locale) {
        return pick(DISCLOSURE, locale);
    }

    /**
     * What the assistant says when it hands the conversation to a person: why, then
     * -- truthfully -- whether anyone is there to receive it (ADR 0064's operator
     * presence). It never says "a person will reply shortly" when nobody is online.
     */
    public static String handoff(
            String locale, @Nullable EscalationTopic topic, @Nullable RefusalReason reason, boolean someoneOnline) {
        String why;
        if (topic != null) {
            why = switch (topic) {
                case COMPLAINT, REFUND -> pick(SORRY, locale);
                case HUMAN_REQUESTED -> pick(OF_COURSE, locale);
            };
        } else if (reason == null) {
            why = pick(CANNOT_ANSWER, locale);
        } else {
            why = switch (reason) {
                case PROVIDER_UNAVAILABLE, SPEND_CEILING, TURN_CAP, ENTITLEMENT_LIMIT, RATE_LIMITED ->
                    pick(NOT_NOW, locale);
                case NO_GROUNDING, UNGROUNDED_REPLY, MODEL_REFUSED -> pick(CANNOT_ANSWER, locale);
            };
        }
        return why + " " + pick(someoneOnline ? PERSON_ONLINE : NOBODY_ONLINE, locale);
    }

    private static String pick(Map<String, String> byLocale, String locale) {
        return java.util.Objects.requireNonNull(byLocale.getOrDefault(locale, byLocale.get("en")));
    }
}
