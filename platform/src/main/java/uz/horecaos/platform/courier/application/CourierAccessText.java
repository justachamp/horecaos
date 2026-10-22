package uz.horecaos.platform.courier.application;

import java.time.Duration;
import java.util.Map;

/**
 * The words of a courier's first-sign-in text (ADR 0178), in the three languages the console speaks.
 *
 * <p>Short on purpose: an SMS segment is 70 characters in Cyrillic, and the link is already most of one.
 * Nothing here is interpolated from anything a person typed; the only variables are the link the platform
 * made and the number of hours it lives. It names no tenant, no courier and no number.
 */
final class CourierAccessText {

    private record Words(String invite, String reset) {}

    private static final Words ENGLISH = new Words(
            "HorecaOS: you are invited to the courier app. Set your password here: %s (valid for %d hours)",
            "HorecaOS: your courier app access was reset. Choose a new password here: %s (valid for %d hours)");

    private static final Map<String, Words> WORDS = Map.of(
            "uz-Latn",
            new Words(
                    "HorecaOS: sizni kuryer ilovasiga taklif qilishdi. Parolni shu havolada kiriting: %s (%d soat amal qiladi)",
                    "HorecaOS: kuryer ilovasiga kirish tiklandi. Yangi parolni shu havolada kiriting: %s (%d soat amal qiladi)"),
            "ru",
            new Words(
                    "HorecaOS: вас пригласили в приложение курьера. Задайте пароль по ссылке: %s (действует %d ч.)",
                    "HorecaOS: доступ к приложению курьера сброшен. Задайте новый пароль по ссылке: %s (действует %d ч.)"),
            "en",
            ENGLISH);

    private CourierAccessText() {}

    static String render(String kind, String locale, String link, Duration validFor) {
        Words words = WORDS.getOrDefault(locale, ENGLISH);
        String template = "RESET".equals(kind) ? words.reset() : words.invite();
        return template.formatted(link, validFor.toHours());
    }
}
