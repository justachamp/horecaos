package uz.horecaos.platform.iam.application.passwordresets;

import java.util.Map;
import java.util.Objects;
import uz.horecaos.platform.iam.api.mail.StaffEmail;

/**
 * The words of a staff password reset (ADR 0098), in the language of the page
 * it was asked from.
 *
 * <p>Plain text and a plain HTML twin carrying the same sentences, shaped
 * after ADR 0097's {@code InvitationEmail}. Nothing here is interpolated from
 * anything a person typed: the only variable is the link the platform made and
 * the number of minutes it lives, so the escaping below is belt to that
 * braces rather than the other way round.
 *
 * <p>It never names the account. An email that repeated the address or the
 * user name back would put both in the body of a message that may be
 * forwarded, and the person reading it is at the address already.
 */
final class PasswordResetEmail {

    private record Words(String subject, String greeting, String body, String action, String expiry, String ignore) {}

    private static final Map<String, Words> WORDS = Map.of(
            "uz",
            new Words(
                    "HorecaOS parolingizni tiklash",
                    "Assalomu alaykum!",
                    "HorecaOS hisobingiz uchun yangi parol so'raldi. Yangi parolni shu havola orqali kiriting:",
                    "Yangi parol kiritish",
                    "Havola %d daqiqa davomida va faqat bir marta ishlaydi.",
                    "Agar buni siz so'ramagan bo'lsangiz, bu xatni e'tiborsiz qoldiring: parolingiz o'zgarmaydi."),
            "ru",
            new Words(
                    "Восстановление пароля HorecaOS",
                    "Здравствуйте!",
                    "Для вашей учётной записи HorecaOS запросили новый пароль. Задайте его по этой ссылке:",
                    "Задать новый пароль",
                    "Ссылка действует %d минут и только один раз.",
                    "Если вы этого не запрашивали, просто проигнорируйте письмо: пароль не изменится."),
            "en",
            new Words(
                    "Reset your HorecaOS password",
                    "Hello,",
                    "Somebody asked for a new password for your HorecaOS account. Set one here:",
                    "Set a new password",
                    "The link works for %d minutes, and only once.",
                    "If this was not you, ignore this email: your password does not change."));

    private PasswordResetEmail() {}

    static StaffEmail render(String to, String locale, String link, long minutes) {
        Words words = Objects.requireNonNull(WORDS.getOrDefault(locale, WORDS.get("ru")));
        String expiry = words.expiry().formatted(minutes);
        String text = String.join("\n\n", words.greeting(), words.body(), link, expiry, words.ignore(), "HorecaOS");
        String html = """
                <!doctype html>
                <html><body style="font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1d2330">
                <p>%s</p>
                <p>%s</p>
                <p><a href="%s" style="display:inline-block;padding:10px 18px;background:#1f5eff;color:#ffffff;\
                text-decoration:none;border-radius:6px">%s</a></p>
                <p style="font-size:13px;color:#555b66">%s<br>%s</p>
                <p style="font-size:13px;color:#555b66">HorecaOS</p>
                </body></html>
                """.formatted(
                        escape(words.greeting()),
                        escape(words.body()),
                        escape(link),
                        escape(words.action()),
                        escape(expiry),
                        escape(words.ignore()));
        return new StaffEmail(to, words.subject(), text, html);
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
