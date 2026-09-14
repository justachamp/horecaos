package uz.horecaos.platform.tenancy.application.invitations;

import java.util.Map;
import uz.horecaos.platform.mail.api.OutgoingMail;

/**
 * The words of a staff invitation (ADR 0116, staff-and-access.md §4), in the
 * language the inviter chose.
 *
 * <p>Deliberately its own words rather than {@link InvitationEmail} reused: an
 * owner's invitation says "you are its owner", which is wrong for a cook or a
 * cashier, and this one names the job instead. Plain text and a plain HTML
 * twin carrying the same sentences; the tenant's name and the job name are the
 * only values that reach the HTML, and both are escaped, because both are
 * typed by a person.
 */
final class StaffInvitationEmail {

    private record Words(String subject, String greeting, String body, String action, String expiry, String ignore) {}

    private static final Map<String, Words> WORDS = Map.of(
            "uz",
            new Words(
                    "%s jamoasiga qo'shiling — HorecaOS",
                    "Assalomu alaykum!",
                    "Sizni %s jamoasiga \"%s\" lavozimiga taklif qilishdi. Ismingiz va parolingizni shu havola"
                            + " orqali kiriting:",
                    "Hisobni sozlash",
                    "Havola %d soat davomida va faqat bir marta ishlaydi.",
                    "Agar bu xatni kutmagan bo'lsangiz, uni e'tiborsiz qoldiring."),
            "ru",
            new Words(
                    "Приглашение в команду %s — HorecaOS",
                    "Здравствуйте!",
                    "Вас пригласили в команду %s на должность «%s». Укажите имя и пароль по этой ссылке:",
                    "Настроить учётную запись",
                    "Ссылка действует %d часа и только один раз.",
                    "Если вы не ждали этого письма, просто проигнорируйте его."),
            "en",
            new Words(
                    "Join %s on HorecaOS",
                    "Hello,",
                    "You have been invited to join %s as \"%s\". Set your name and password here:",
                    "Set up my account",
                    "The link works for %d hours, and only once.",
                    "If you did not expect this email, ignore it."));

    private StaffInvitationEmail() {}

    static OutgoingMail render(String to, String locale, String tenantName, String jobName, String link, long hours) {
        Words words = java.util.Objects.requireNonNull(WORDS.getOrDefault(locale, WORDS.get("ru")));
        String subject = words.subject().formatted(tenantName.replaceAll("[\\r\\n]+", " "));
        String body = words.body().formatted(tenantName, jobName);
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
                        InvitationEmail.escape(words.greeting()),
                        InvitationEmail.escape(body),
                        InvitationEmail.escape(link),
                        InvitationEmail.escape(words.action()),
                        InvitationEmail.escape(expiry),
                        InvitationEmail.escape(words.ignore()));
        return new OutgoingMail(to, subject, text, html);
    }
}
