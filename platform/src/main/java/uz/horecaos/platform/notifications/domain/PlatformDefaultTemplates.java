package uz.horecaos.platform.notifications.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The wording a customer is sent for an order milestone when the tenant has written none (ADR 0020's
 * «brand override, tenant override, platform default», the last layer, which ADR 0184 builds).
 *
 * <p>Bundled in code rather than stored as rows with no tenant. A row with a NULL tenant in a
 * tenant-scoped table would be the one row ADR 0056's row-level security does not govern, and the
 * layer needs no query: it is the same text for every tenant. This is the shape ADR 0068 gave
 * {@code PlatformDefaultTerms}, and for the same reason it carries a {@link #VERSION}: a notification
 * that was sent from {@code default-v1} must keep meaning exactly that wording after a later release
 * edits this class, so a change to any sentence below bumps the constant, and the notification row
 * freezes it ({@code platform_default_version}) as it freezes a tenant template's version number.
 *
 * <p><strong>What it covers.</strong> The eight order milestones a customer is told: confirmed,
 * preparing, ready, on the way, and the four ways an order ends without being delivered (rejected,
 * cancelled, expired, not paid in time). It does <em>not</em> cover {@code ORDER_COMPLETED}: that
 * message is the carrier of the rating prompt, ADR 0075 makes the prompt opt-in by a tenant
 * activating a template, and ADR 0159's preservation migration (V0545) rests on «an unactivated
 * template sends nothing». A default would switch the prompt on for every tenant that never chose it.
 *
 * <p><strong>What it uses.</strong> Only the order number and the total, the two values every order
 * has. A sentence that named the restaurant, a time or the courier would have to say something
 * sensible for an order that has none of them. A tenant that wants those writes its own wording, where
 * the merge variables exist ({@link NotificationVariableCatalog}).
 *
 * <p><strong>Who may read it.</strong> Only a customer-addressed message of the matching class: a
 * row with the class {@link NotificationClass#OPERATIONS_ALERT} shares these template keys
 * ({@code OrderNotificationTrigger} fans the same fact out to the branch's operations chat) and must
 * never be answered with a sentence written for the customer. The service enforces it; this class
 * only says what the wording and the class of each entry are.
 *
 * <p><strong>The wording is not reviewed by a native speaker yet.</strong> That is an external fact,
 * recorded in {@code docs/runbooks/external-connections/w9-notifications-settings.md}. Every sentence
 * is GSM-7 clean in Uzbek (the apostrophe is ASCII) and short, so an SMS in Uzbek or English is one
 * segment; Russian is UCS-2 by nature.
 */
public final class PlatformDefaultTemplates {

    /** Bump when any sentence below changes; see the class doc. */
    public static final int VERSION = 1;

    /**
     * The consent purpose of the optional milestones (preparing, ready, on the way). The tenant names
     * its own purposes (V0289); this one is the platform's name for «tell me where my order is», and a
     * customer who has not been asked is a customer who has not agreed (ADR 0015: absence is not
     * consent), so the optional milestones stay quiet until a decision under it is on record.
     */
    public static final String ORDER_PROGRESS_PURPOSE = "ORDER_PROGRESS_UPDATES";

    /** The customer channels a platform default is offered on: the wired ones that reach a customer. */
    public static final List<NotificationChannel> CHANNELS =
            List.of(NotificationChannel.SMS, NotificationChannel.TELEGRAM);

    private PlatformDefaultTemplates() {}

    /**
     * One template key's default.
     *
     * @param consentPurpose null for a required message; the purpose an optional one is checked
     *                       against
     * @param bodies         locale tag to sentence
     */
    public record Entry(
            String templateKey,
            NotificationClass notificationClass,
            @Nullable String consentPurpose,
            Map<String, String> bodies) {

        public Entry {
            bodies = Map.copyOf(bodies);
        }

        /** The sentence in this language, or empty when the platform has not written one. */
        public Optional<String> body(String localeTag) {
            return Optional.ofNullable(bodies.get(localeTag));
        }

        /** The merge variables the wording names, which is also the schema a default is declared with. */
        public Set<String> variables() {
            Set<String> used = new java.util.TreeSet<>();
            bodies.values().forEach(body -> used.addAll(TemplateRenderer.variablesUsedIn(body)));
            return used;
        }
    }

    private static final String RU = "ru";
    private static final String UZ = "uz-Latn";
    private static final String EN = "en";

    /** In the order of an order's life, which is the order the console lists them in. */
    private static final List<Entry> ENTRIES = List.of(
            required(
                    "ORDER_CONFIRMED",
                    "Заказ {{orderNumber}} принят. Сумма: {{amount}} {{currency}}.",
                    "{{orderNumber}}-buyurtma qabul qilindi. Summa: {{amount}} {{currency}}.",
                    "Order {{orderNumber}} is confirmed. Total: {{amount}} {{currency}}."),
            optional(
                    "ORDER_PREPARING",
                    "Заказ {{orderNumber}}: мы начали его готовить.",
                    "{{orderNumber}}-buyurtma: tayyorlashni boshladik.",
                    "Order {{orderNumber}}: we have started preparing it."),
            optional(
                    "ORDER_READY",
                    "Заказ {{orderNumber}} готов. Можно забирать.",
                    "{{orderNumber}}-buyurtma tayyor. Olib ketishingiz mumkin.",
                    "Order {{orderNumber}} is ready for pickup."),
            optional(
                    "ORDER_FULFILLING",
                    "Заказ {{orderNumber}} передан курьеру и уже в пути.",
                    "{{orderNumber}}-buyurtma kuryerga topshirildi va yo'lda.",
                    "Order {{orderNumber}} has been handed to the courier and is on its way."),
            required(
                    "ORDER_REJECTED",
                    "К сожалению, заказ {{orderNumber}} принять не удалось. Приносим извинения.",
                    "Afsuski, {{orderNumber}}-buyurtmani qabul qila olmadik. Uzr so'raymiz.",
                    "Sorry, we could not accept order {{orderNumber}}."),
            required(
                    "ORDER_CANCELLED",
                    "Заказ {{orderNumber}} отменён.",
                    "{{orderNumber}}-buyurtma bekor qilindi.",
                    "Order {{orderNumber}} has been cancelled."),
            required(
                    "ORDER_EXPIRED",
                    "Ресторан не успел ответить на заказ {{orderNumber}}, поэтому он не принят.",
                    "Restoran {{orderNumber}}-buyurtmaga o'z vaqtida javob bera olmadi, shuning uchun u qabul qilinmadi.",
                    "The restaurant could not answer in time, so order {{orderNumber}} was not accepted."),
            required(
                    "ORDER_PAYMENT_LAPSED",
                    "Заказ {{orderNumber}} не был оплачен вовремя и закрыт.",
                    "{{orderNumber}}-buyurtma o'z vaqtida to'lanmadi va yopildi.",
                    "Order {{orderNumber}} was not paid in time and has been closed."));

    private static Entry required(String key, String ru, String uz, String en) {
        return new Entry(key, NotificationClass.TRANSACTIONAL_REQUIRED, null, bodies(ru, uz, en));
    }

    private static Entry optional(String key, String ru, String uz, String en) {
        return new Entry(key, NotificationClass.TRANSACTIONAL_OPTIONAL, ORDER_PROGRESS_PURPOSE, bodies(ru, uz, en));
    }

    private static Map<String, String> bodies(String ru, String uz, String en) {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put(RU, ru);
        bodies.put(UZ, uz);
        bodies.put(EN, en);
        return bodies;
    }

    /** Every default, in the order of an order's life. */
    public static List<Entry> all() {
        return ENTRIES;
    }

    /** The default for a template key, or empty when the platform writes none (including {@code ORDER_COMPLETED}). */
    public static Optional<Entry> forKey(String templateKey) {
        return ENTRIES.stream()
                .filter(entry -> entry.templateKey().equals(templateKey))
                .findFirst();
    }

    /** Whether the platform default may be offered on this channel. */
    public static boolean offeredOn(NotificationChannel channel) {
        return CHANNELS.contains(channel);
    }
}
