package uz.horecaos.platform.notifications.web;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The four pages of the unsubscribe flow, in the three languages the platform sends in (ADR
 * 0182). Self-contained HTML: inline style, no script, no image, no external address, so opening
 * one reveals nothing to a third party and needs nothing to render.
 */
final class UnsubscribePages {

    private UnsubscribePages() {}

    private enum Language {
        RU,
        UZ,
        EN;

        static Language of(@Nullable String locale) {
            String tag = locale == null ? "ru" : locale.toLowerCase(Locale.ROOT);
            if (tag.startsWith("uz")) {
                return UZ;
            }
            return tag.startsWith("en") ? EN : RU;
        }
    }

    static String confirm(String brandName, @Nullable String locale) {
        Language language = Language.of(locale);
        String brand = brand(brandName);
        String title =
                switch (language) {
                    case RU -> "Отписаться от рассылки";
                    case UZ -> "Xabarnomadan voz kechish";
                    case EN -> "Unsubscribe";
                };
        String question =
                switch (language) {
                    case RU -> "Больше не получать письма с рекламой от %s?".formatted(escape(brand));
                    case UZ -> "%s dan reklama xatlarini endi olmaysizmi?".formatted(escape(brand));
                    case EN -> "Stop receiving promotional emails from %s?".formatted(escape(brand));
                };
        String button =
                switch (language) {
                    case RU -> "Отписаться";
                    case UZ -> "Voz kechish";
                    case EN -> "Unsubscribe";
                };
        return page(
                language,
                title,
                "<p>" + question + "</p><form method=\"post\"><button type=\"submit\">" + button + "</button></form>");
    }

    static String done(String brandName, @Nullable String locale) {
        Language language = Language.of(locale);
        String brand = escape(brand(brandName));
        return switch (language) {
            case RU ->
                page(
                        language,
                        "Вы отписались",
                        "<p>Вы больше не будете получать письма с рекламой от " + brand + ".</p>");
            case UZ ->
                page(language, "Voz kechdingiz", "<p>Siz endi " + brand + " dan reklama xatlarini olmaysiz.</p>");
            case EN ->
                page(
                        language,
                        "You are unsubscribed",
                        "<p>You will no longer receive promotional emails from " + brand + ".</p>");
        };
    }

    /** One page for a token that is unknown or expired: the three languages together, since none is known. */
    static String unknownLink() {
        return page(
                Language.EN,
                "Link not valid",
                "<p>This link is not valid or has expired.</p><p>Эта ссылка недействительна или устарела.</p>"
                        + "<p>Bu havola yaroqsiz yoki muddati o'tgan.</p>");
    }

    static String tooManyAttempts() {
        return page(
                Language.EN,
                "Try again shortly",
                "<p>Too many attempts. Try again in a minute.</p><p>Слишком много попыток. Повторите через минуту.</p>"
                        + "<p>Juda ko'p urinish. Bir daqiqadan so'ng qayta urining.</p>");
    }

    private static String brand(String brandName) {
        return brandName == null || brandName.isBlank() ? "HorecaOS" : brandName;
    }

    private static String page(Language language, String title, String body) {
        String lang =
                switch (language) {
                    case RU -> "ru";
                    case UZ -> "uz";
                    case EN -> "en";
                };
        return "<!doctype html><html lang=\"" + lang + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + escape(title) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;"
                + "line-height:1.5}button{font:inherit;padding:.6rem 1.2rem}</style></head><body><h1>"
                + escape(title) + "</h1>" + body + "</body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
