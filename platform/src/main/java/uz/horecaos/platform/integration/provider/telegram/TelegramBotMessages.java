package uz.horecaos.platform.integration.provider.telegram;

import java.util.Locale;

/**
 * The bot's own protocol-level replies during the {@code /link} handshake (ADR
 * 0058: "Group-language from tenant configuration").
 *
 * <p>Deliberately not routed through {@code notifications.templates}: these are
 * not business notifications with a template key, a class, or an audit trail —
 * they are the bot answering the command that just ran, the same way a CLI
 * prints "done" or an error to the terminal that invoked it. Three fixed
 * languages, matching {@code MessageLocale}'s own closed set.
 */
final class TelegramBotMessages {

    private TelegramBotMessages() {}

    static String notAGroup(String locale) {
        return pick(
                locale,
                "Bu buyruq faqat guruhda ishlaydi. Guruhga qo'shing va qayta urinib ko'ring.",
                "Эта команда работает только в группе. Добавьте бота в группу и повторите.",
                "This command only works inside a group. Add the bot to a group and try again.");
    }

    static String invalidOrExpiredCode() {
        // Deliberately one language: the failure carries no tenant to configure
        // for, since the code did not resolve to one.
        return "This link code is invalid or has expired. Generate a new one from Operations.";
    }

    static String crossTenantRefused() {
        return "This link code cannot be used with this bot. Generate a new one from Operations.";
    }

    static String insufficientRights(String locale, String reason) {
        return pick(
                locale,
                "Botni ushbu guruhda ADMINISTRATOR qilib tayinlang (kerak bo'lsa \"Mavzularni boshqarish\" "
                        + "huquqi bilan) va qayta \"/link\" yuboring. Sabab: " + reason,
                "Назначьте бота АДМИНИСТРАТОРОМ этой группы (при необходимости с правом \"Управление темами\") "
                        + "и снова отправьте \"/link\". Причина: " + reason,
                "Make the bot an ADMINISTRATOR of this group (with \"Manage Topics\" if this is a forum topic) "
                        + "and send /link again. Reason: " + reason);
    }

    static String linked(String locale) {
        return pick(
                locale,
                "Ulandi. Ushbu guruh endi buyurtmalar bo'yicha operativ xabarlarni oladi.",
                "Готово. Эта группа теперь получает операционные уведомления по заказам.",
                "Linked. This group now receives operations notifications for orders.");
    }

    private static String pick(String locale, String uz, String ru, String en) {
        return switch (locale == null ? "" : locale.toLowerCase(Locale.ROOT)) {
            case "uz-latn", "uz" -> uz;
            case "en" -> en;
            default -> ru;
        };
    }
}
