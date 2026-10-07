package uz.horecaos.platform.iam.application.mfa;

import uz.horecaos.platform.iam.api.mail.StaffEmail;

/**
 * The words the person is sent for each change to their second factor (ADR 0148, Decision 7):
 * an authenticator enrolled, added, removed, or all of them reset by an administrator.
 *
 * <p>Plain text and a plain HTML twin carrying the same sentences, shaped after ADR 0098's
 * {@code PasswordResetEmail}. Nothing here is interpolated from anything a person typed, and none
 * of it names an authenticator, a label, a device, a code or the administrator: an email that did
 * would put a person's phone model, or a colleague's name, into a message that may be forwarded.
 * The one thing it always says is what to do if the change was not theirs.
 */
final class StaffMfaEmails {

    enum Kind {
        ENROLLED,
        ADDED,
        REMOVED,
        RESET
    }

    private record Words(String subject, String body, String ifNotYou) {}

    private StaffMfaEmails() {}

    /**
     * @param language the registry's tag for the person's language ({@code LocaleVocabulary#message}),
     *     never a raw stored value; a language the platform sends in before this table has its
     *     wording is addressed in Russian, the staff default, and never in a machine translation
     */
    static StaffEmail render(String to, String language, Kind kind) {
        Words words = words(language, kind);
        String greeting =
                switch (language) {
                    case "uz-Latn" -> "Assalomu alaykum!";
                    case "en" -> "Hello,";
                    default -> "Здравствуйте!";
                };
        String text = String.join("\n\n", greeting, words.body(), words.ifNotYou(), "HorecaOS");
        String html = """
                <!doctype html>
                <html><body style="font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1d2330">
                <p>%s</p>
                <p>%s</p>
                <p style="font-size:13px;color:#555b66">%s</p>
                <p style="font-size:13px;color:#555b66">HorecaOS</p>
                </body></html>
                """.formatted(escape(greeting), escape(words.body()), escape(words.ifNotYou()));
        return new StaffEmail(to, words.subject(), text, html);
    }

    private static Words words(String language, Kind kind) {
        return switch (language) {
            case "uz-Latn" ->
                switch (kind) {
                    case ENROLLED ->
                        new Words(
                                "HorecaOS: ikki bosqichli kirish yoqildi",
                                "HorecaOS hisobingiz uchun ikki bosqichli kirish (autentifikator ilovasi) yoqildi.",
                                "Agar buni siz qilmagan bo'lsangiz, zudlik bilan administratoringizga murojaat qiling.");
                    case ADDED ->
                        new Words(
                                "HorecaOS: yangi autentifikator qo'shildi",
                                "HorecaOS hisobingizga yana bitta autentifikator qo'shildi.",
                                "Agar buni siz qilmagan bo'lsangiz, zudlik bilan administratoringizga murojaat qiling.");
                    case REMOVED ->
                        new Words(
                                "HorecaOS: autentifikator o'chirildi",
                                "HorecaOS hisobingizdan bitta autentifikator o'chirildi.",
                                "Agar buni siz qilmagan bo'lsangiz, zudlik bilan administratoringizga murojaat qiling.");
                    case RESET ->
                        new Words(
                                "HorecaOS: ikki bosqichli kirish bekor qilindi",
                                "Administrator HorecaOS hisobingizdagi barcha autentifikatorlarni o'chirdi va seanslaringizni yakunladi. "
                                        + "Keyingi kirishda autentifikatorni qaytadan sozlaysiz.",
                                "Agar bunga sabab yo'q bo'lsa, zudlik bilan administratoringizga murojaat qiling.");
                };
            case "en" ->
                switch (kind) {
                    case ENROLLED ->
                        new Words(
                                "HorecaOS: two-step sign-in is on",
                                "Two-step sign-in with an authenticator app was turned on for your HorecaOS account.",
                                "If this was not you, contact your administrator right away.");
                    case ADDED ->
                        new Words(
                                "HorecaOS: an authenticator was added",
                                "Another authenticator was added to your HorecaOS account.",
                                "If this was not you, contact your administrator right away.");
                    case REMOVED ->
                        new Words(
                                "HorecaOS: an authenticator was removed",
                                "An authenticator was removed from your HorecaOS account.",
                                "If this was not you, contact your administrator right away.");
                    case RESET ->
                        new Words(
                                "HorecaOS: two-step sign-in was reset",
                                "An administrator removed every authenticator from your HorecaOS account and ended your "
                                        + "sessions. You will set the authenticator up again the next time you sign in.",
                                "If you did not expect this, contact your administrator right away.");
                };
            default ->
                switch (kind) {
                    case ENROLLED ->
                        new Words(
                                "HorecaOS: включён вход в два шага",
                                "Для вашей учётной записи HorecaOS включён вход в два шага через приложение-аутентификатор.",
                                "Если это сделали не вы, немедленно сообщите администратору.");
                    case ADDED ->
                        new Words(
                                "HorecaOS: добавлен аутентификатор",
                                "В вашу учётную запись HorecaOS добавлен ещё один аутентификатор.",
                                "Если это сделали не вы, немедленно сообщите администратору.");
                    case REMOVED ->
                        new Words(
                                "HorecaOS: аутентификатор удалён",
                                "Из вашей учётной записи HorecaOS удалён один аутентификатор.",
                                "Если это сделали не вы, немедленно сообщите администратору.");
                    case RESET ->
                        new Words(
                                "HorecaOS: вход в два шага сброшен",
                                "Администратор удалил все аутентификаторы вашей учётной записи HorecaOS и завершил ваши "
                                        + "сеансы. При следующем входе вы настроите аутентификатор заново.",
                                "Если вы этого не ожидали, немедленно сообщите администратору.");
                };
        };
    }

    private static String escape(String value) {
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
