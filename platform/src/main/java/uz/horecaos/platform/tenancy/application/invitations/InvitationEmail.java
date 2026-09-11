package uz.horecaos.platform.tenancy.application.invitations;

import java.util.Map;
import uz.horecaos.platform.mail.api.OutgoingMail;

/**
 * The words of an owner's invitation (ADR 0097), in the language the operator
 * chose for them.
 *
 * <p>Plain text and a plain HTML twin carrying the same sentences; the tenant's
 * name is the only value that reaches the HTML, and it is escaped, because a
 * display name is typed by a person and may hold anything.
 */
final class InvitationEmail {

    private record Words(String subject, String greeting, String body, String action, String expiry, String ignore) {}

    private static final Map<String, Words> WORDS = Map.of(
            "uz",
            new Words(
                    "%s uchun HorecaOS hisobingizni sozlang",
                    "Assalomu alaykum!",
                    "%s HorecaOS'ga ulanmoqda va siz uning egasisiz. Ismingiz va parolingizni shu havola orqali kiriting:",
                    "Hisobni sozlash",
                    "Havola %d soat davomida va faqat bir marta ishlaydi.",
                    "Agar bu xatni kutmagan bo'lsangiz, uni e'tiborsiz qoldiring."),
            "ru",
            new Words(
                    "Настройте учётную запись HorecaOS для %s",
                    "Здравствуйте!",
                    "%s подключается к HorecaOS, и вы — его владелец. Укажите имя и пароль по этой ссылке:",
                    "Настроить учётную запись",
                    "Ссылка действует %d часа и только один раз.",
                    "Если вы не ждали этого письма, просто проигнорируйте его."),
            "en",
            new Words(
                    "Set up your HorecaOS account for %s",
                    "Hello,",
                    "%s is being set up on HorecaOS, and you are its owner. Set your name and password here:",
                    "Set up my account",
                    "The link works for %d hours, and only once.",
                    "If you did not expect this email, ignore it."));

    private InvitationEmail() {}

    static OutgoingMail render(String to, String locale, String tenantName, String link, long hours) {
        Words words = java.util.Objects.requireNonNull(WORDS.getOrDefault(locale, WORDS.get("ru")));
        String subject = words.subject().formatted(tenantName.replaceAll("[\\r\\n]+", " "));
        String body = words.body().formatted(tenantName);
        String expiry = words.expiry().formatted(hours);
        String text = String.join("\n\n", words.greeting(), body, link, expiry, words.ignore(), "HorecaOS");
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
                        escape(body),
                        escape(link),
                        escape(words.action()),
                        escape(expiry),
                        escape(words.ignore()));
        return new OutgoingMail(to, subject, text, html);
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
