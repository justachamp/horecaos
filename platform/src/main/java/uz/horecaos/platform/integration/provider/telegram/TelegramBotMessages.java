package uz.horecaos.platform.integration.provider.telegram;

import java.util.Locale;

/**
 * The bot's own protocol-level replies (ADR 0058 stage 1's {@code /link}
 * handshake, extended by ADR 0060 for buttons and typed commands: "Group-
 * language from tenant configuration").
 *
 * <p>Deliberately not routed through {@code notifications.templates}: these are
 * not business notifications with a template key, a class, or an audit trail —
 * they are the bot answering the command that just ran, the same way a CLI
 * prints "done" or an error to the terminal that invoked it. Three fixed
 * languages, matching {@code MessageLocale}'s own closed set.
 *
 * <p>Public rather than package-private: {@code TelegramChannelAdapter} lives
 * in {@code integration.camel.notification.telegram}, a different package
 * from the {@code /link} handshake, and needs the button labels ADR 0060 §2
 * adds here.
 */
public final class TelegramBotMessages {

    private TelegramBotMessages() {}

    // -------------------------------------------------- ADR 0058 group linking

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

    // --------------------------------------------- ADR 0060 §3 staff identity linking

    static String staffLinkInvalidOrExpiredCode() {
        return "This link code is invalid or has expired. Generate a new one from your staff session.";
    }

    static String staffLinked(String locale) {
        return pick(
                locale,
                "Hisobingiz ulandi. Endi buyurtmalarni tasdiqlashingiz, rad etishingiz va typed "
                        + "buyruqlardan foydalanishingiz mumkin.",
                "Готово. Ваш аккаунт привязан — теперь вы можете подтверждать и отклонять заказы, а "
                        + "также использовать команды бота.",
                "Linked. You can now approve or reject orders and use the bot's typed commands.");
    }

    // ---------------------------------------- ADR 0058 stage 2 customer linking

    /**
     * Deliberately one language, the same choice {@link #invalidOrExpiredCode}
     * and {@link #staffLinkInvalidOrExpiredCode} make: the failure carries no
     * resolved account or tenant to pick a language from, since the code
     * never resolved to one.
     */
    static String customerLinkInvalidOrExpiredCode() {
        return "This link is invalid or has expired. Go back to the app and try again.";
    }

    static String customerLinked(String locale) {
        return pick(
                locale,
                "Ulandi. Endi buyurtma holatlari va kvitansiyalar shu yerga keladi.",
                "Готово. Теперь статусы заказов и чеки будут приходить сюда.",
                "Linked. Your order updates and receipts will arrive here.");
    }

    // -------------------------------------------------- ADR 0063 auth sign-in

    static String authLinkInvalidOrExpiredCode() {
        // One language, the same choice every other invalid/expired-code
        // message on this bot makes: the failure carries no resolved tenant to
        // pick a language from.
        return "This sign-in link is invalid or has expired. Go back to the app and try again.";
    }

    static String authRequestContactPrompt(String locale) {
        return pick(
                locale,
                "Kirish uchun telefon raqamingizni yuboring.",
                "Отправьте свой номер телефона, чтобы войти.",
                "Share your phone number to sign in.");
    }

    static String authRequestContactButton(String locale) {
        return pick(locale, "📱 Raqamni yuborish", "📱 Отправить номер", "📱 Share phone number");
    }

    /** {@code contact.user_id != from.id}: a forwarded stranger's contact, refused by name of nothing. */
    static String authContactMustBeOwn(String locale) {
        return pick(
                locale,
                "Iltimos, o'zingizning kontaktingizni yuboring (\"📎\" orqali forward qilinganini emas).",
                "Пожалуйста, отправьте именно свой контакт (не пересланный).",
                "Please share your own contact, not a forwarded one.");
    }

    /** The configured allowed-phone pattern refused a number. Names nothing about why (ADR 0063's own gate). */
    static String authPhoneNotAllowed(String locale) {
        return pick(
                locale,
                "Bu raqam bilan kirib bo'lmaydi.",
                "С этим номером войти нельзя.",
                "Sign-in is not available for this number.");
    }

    static String authTooManyAttempts(String locale) {
        return pick(
                locale,
                "Juda ko'p urinish. Birozdan so'ng qayta urinib ko'ring.",
                "Слишком много попыток. Повторите чуть позже.",
                "Too many attempts. Try again shortly.");
    }

    static String authLinked(String locale) {
        return pick(
                locale,
                "Kirish muvaffaqiyatli. Ilovaga qaytishingiz mumkin.",
                "Вход выполнен. Вернитесь в приложение.",
                "You're signed in. Go back to the app.");
    }

    // -------------------------------------------------- ADR 0060 §2 the buttons

    public static String approveButtonLabel(String locale) {
        return pick(locale, "✅ Tasdiqlash", "✅ Подтвердить", "✅ Approve");
    }

    public static String rejectButtonLabel(String locale) {
        return pick(locale, "❌ Rad etish", "❌ Отклонить", "❌ Reject");
    }

    static String decisionApplied(String locale, boolean approved, String actorLabel) {
        String action = approved
                ? pick(locale, "tasdiqlandi", "подтверждён", "approved")
                : pick(locale, "rad etildi", "отклонён", "rejected");
        return pick(
                locale,
                "Buyurtma " + action + ". Bajardi: " + actorLabel,
                "Заказ " + action + ". Решение принял(а): " + actorLabel,
                "Order " + action + ". Decided by " + actorLabel);
    }

    /** ADR 0060 §4: "a late tapper is answered with the settling decision and actor." */
    static String decisionAlreadySettled(String locale, String settledAction, String actorLabel) {
        String action = "APPROVE".equalsIgnoreCase(settledAction)
                ? pick(locale, "allaqachon tasdiqlangan", "уже подтверждён", "already approved")
                : pick(locale, "allaqachon rad etilgan", "уже отклонён", "already rejected");
        return pick(
                locale,
                "Bu buyurtma " + action + ". Bajardi: " + actorLabel,
                "Этот заказ " + action + ". Решение принял(а): " + actorLabel,
                "This order was " + action + ". Decided by " + actorLabel);
    }

    static String decisionSettledElsewhere(String locale) {
        return pick(
                locale,
                "Bu buyurtma boshqa yoʻl bilan hal qilingan (masalan, muddati oʻtgan).",
                "Этот заказ уже разрешился другим способом (например, истёк срок).",
                "This order was already settled some other way (for example, it expired).");
    }

    static String decisionUnauthorized(String locale) {
        return pick(
                locale,
                "Sizda bu amal uchun ruxsat yoʻq (yoki bekor qilingan). Menejeringizga murojaat qiling.",
                "У вас нет прав на это действие (доступ мог быть отозван). Обратитесь к менеджеру.",
                "You are not authorized for this action (your access may have been revoked). Ask your manager.");
    }

    static String decisionNotLinked(String locale) {
        return pick(
                locale,
                "Telegram hisobingiz ulanmagan. Operatsiyalar ilovasidan kod oling va botga shaxsiy "
                        + "xabarda \"/link <kod>\" yuboring.",
                "Ваш Telegram-аккаунт не привязан. Получите код в приложении Operations и отправьте "
                        + "боту в личном чате \"/link <код>\".",
                "Your Telegram account is not linked. Get a code from your staff session and send "
                        + "\"/link <code>\" to the bot in a private chat.");
    }

    static String decisionTokenExpired(String locale) {
        return pick(
                locale,
                "Bu tugma eskirgan. Buyurtmani Operations ilovasida oching.",
                "Эта кнопка устарела. Откройте заказ в приложении Operations.",
                "This button has expired. Open the order in Operations instead.");
    }

    static String botNotEnabledForTenant(String locale) {
        return pick(
                locale,
                "Ushbu tenant uchun bot orqali amallar hali yoqilmagan.",
                "Интерактивные действия бота для этого тенанта пока не включены.",
                "Interactive bot actions are not enabled for this tenant yet.");
    }

    // ------------------------------------------------ ADR 0060 §3 typed commands

    static String anonymousAdminRefused(String locale) {
        return pick(
                locale,
                "Anonim administrator sifatida buyruqlarni ishlata olmaysiz. Tugmalardan foydalaning "
                        + "yoki anonimlikni oʻchiring.",
                "Команды недоступны от имени анонимного администратора. Используйте кнопки или "
                        + "отключите анонимность.",
                "Typed commands cannot be run as an anonymous group admin. Use the buttons instead, "
                        + "or turn off admin anonymity.");
    }

    static String noTenantLinked(String locale) {
        return pick(
                locale,
                "Sizda ulangan tenant yoʻq. Operatsiyalar ilovasidan \"/link\" kodini oling.",
                "У вас нет привязанного тенанта. Получите код \"/link\" в приложении Operations.",
                "You have no linked tenant. Get a \"/link\" code from your staff session first.");
    }

    static String tenantPickerPrompt(String locale) {
        return pick(locale, "Qaysi tenant uchun?", "Для какого тенанта?", "Which tenant is this for?");
    }

    static String tenantPickerExpired(String locale) {
        return pick(
                locale,
                "Bu tanlov eskirgan. Buyruqni qayta yuboring.",
                "Этот выбор устарел. Отправьте команду ещё раз.",
                "This selection has expired. Send the command again.");
    }

    static String noGrantInAnyLinkedTenant(String locale) {
        return pick(
                locale,
                "Ulangan tenantlaringizning birortasida ham bu amal uchun ruxsatingiz yoʻq.",
                "Ни в одном из привязанных тенантов у вас нет прав на это действие.",
                "You hold no grant for this action in any of your linked tenants.");
    }

    static String ambiguousLocation(String locale) {
        return pick(
                locale,
                "Sizning ruxsatingiz bir nechta filialni qamrab oladi — bu buyruqni bog'langan "
                        + "guruh chatidan yuboring yoki ilovadan foydalaning.",
                "Ваш доступ охватывает несколько точек — отправьте эту команду из привязанного "
                        + "группового чата или используйте приложение.",
                "Your grant covers more than one location — send this command from a linked group "
                        + "chat instead, or use the app.");
    }

    // ------------------------------------------------------------- stop list (86)

    static String stopListUsage(String locale) {
        return pick(
                locale,
                "Foydalanish: /86 — roʻyxatni koʻrish; /86 <kod> — yoqish/oʻchirish.",
                "Использование: /86 — показать список; /86 <код> — переключить.",
                "Usage: /86 to list items; /86 <code> to toggle one.");
    }

    static String stopListEmpty(String locale) {
        return pick(
                locale,
                "Bu filialda hech qanday taom yoʻq.",
                "В этой точке нет позиций меню.",
                "No menu items at this location.");
    }

    static String stopListRow(boolean available, String code, String name) {
        String status = available ? "✅" : "⛔";
        return status + " " + code + " — " + name;
    }

    static String stopListUnknownReference(String locale, String reference) {
        return pick(
                locale,
                "\"" + reference + "\" topilmadi. \"/86\" bilan roʻyxatni qaytadan koʻring.",
                "\"" + reference + "\" не найден. Отправьте \"/86\" ещё раз, чтобы увидеть список.",
                "\"" + reference + "\" was not found. Send \"/86\" again to see the current list.");
    }

    static String stopListToggled(String locale, String name, boolean nowAvailable) {
        String state = nowAvailable
                ? pick(locale, "sotuvda", "в продаже", "available")
                : pick(locale, "86'landi (sotuvda emas)", "снят с продажи (86)", "86'd (unavailable)");
        return pick(
                locale,
                name + " endi " + state + ".",
                name + " теперь " + state + ".",
                name + " is now " + state + ".");
    }

    // ------------------------------------------------------------------- stats

    static String statsHeader(String locale) {
        return pick(locale, "Joriy holat:", "Текущее состояние:", "Current state:");
    }

    static String statsLabel(String locale, String key) {
        return switch (key) {
            case "new" -> pick(locale, "Yangi", "Новые", "New");
            case "awaiting" -> pick(locale, "Tasdiqlash kutmoqda", "Ожидают подтверждения", "Awaiting approval");
            case "kitchen" -> pick(locale, "Oshxonada", "На кухне", "In kitchen");
            case "ready" -> pick(locale, "Tayyor", "Готово", "Ready");
            case "fulfilling" -> pick(locale, "Yetkazilmoqda", "В доставке", "Fulfilling");
            case "completed" -> pick(locale, "Yakunlangan", "Завершено", "Completed");
            case "cancelled" -> pick(locale, "Bekor qilingan", "Отменено", "Cancelled");
            default -> key;
        };
    }

    static String statsRow(String locale, String key, long count) {
        return "• " + statsLabel(locale, key) + ": " + count;
    }

    private static String pick(String locale, String uz, String ru, String en) {
        return switch (locale == null ? "" : locale.toLowerCase(Locale.ROOT)) {
            case "uz-latn", "uz" -> uz;
            case "en" -> en;
            default -> ru;
        };
    }
}
