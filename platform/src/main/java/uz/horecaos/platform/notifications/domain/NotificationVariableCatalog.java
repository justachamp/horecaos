package uz.horecaos.platform.notifications.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which merge variables an author may declare for a class of message
 * (gap map row {@code X.27}, wave P36).
 *
 * <p>The defect this catalogue exists to fix: the operations console hard-coded
 * every version's {@code variablesSchema} to {@code {}}, and {@link
 * TemplateRenderer#validate} — correctly — refuses any placeholder a version's
 * schema does not declare. The renderer was never the bug; the editor never
 * gave an author anything to declare. This is that catalogue, code-owned for
 * the reason {@code uz.horecaos.platform.tenancy.api.ConfigurationKey}'s own
 * Javadoc gives for keys: declared here rather than in the database, so an
 * unknown variable name fails at authoring time rather than resolving to an
 * empty string when a customer is waiting.
 *
 * <p>Grouped by {@link NotificationClass} rather than by template key, per the
 * gap map's own framing: a class is the dimension an author already picks
 * when registering a template ({@code
 * NotificationTemplateController.CreateTemplateRequest}), so it is the one
 * the editor can offer without asking a second question. This is coarser
 * than "one set per template key" — an {@code OPERATIONS_ALERT} template can
 * be any one of several real alert types (a stock-out, a payment failure, a
 * fiscal block…), each with its own event-specific variable — so that class's
 * list is deliberately the union across every alert trigger that exists
 * today (see the field-level comments below for which trigger contributes
 * which name), not a promise that any one wording uses all of them. An
 * undeclared variable still fails {@link TemplateRenderer#validate}; this
 * catalogue only widens what an author is offered to declare, never what the
 * renderer accepts.
 *
 * <p><strong>ADR 0184 widened what an order message may say, and fixed which values may be
 * personal.</strong> The order classes now offer the restaurant's name, the promised time, the
 * preparation time, the product list (with or without prices, a switch on the version), the
 * discount and the delivery fee beside the number and the total. Two values are personal and are the
 * only two: {@code customerName}, for a customer-addressed class only, and {@code courierFirstName},
 * for {@code ORDER_FULFILLING} only. Both are <em>resolved at render and never written down</em>:
 * {@link #RESOLVED_AT_RENDER} names them, and nothing that freezes variables onto a notification row
 * may carry them. The customer's phone and the courier's phone and surname are
 * {@linkplain #DECLINED declined}: no list below offers them, and {@link #refusal} stops a version
 * that names one from being saved, so the refusal is at authoring and not at 22:00. D21 is the
 * decision; ADR 0045 is why the courier's number was never an option.
 */
public final class NotificationVariableCatalog {

    private NotificationVariableCatalog() {}

    /** One variable an author may declare and insert, with what it means. */
    public record Variable(String name, String description) {}

    /**
     * The three values every order-derived message has had since ADR 0020. {@code amount} is a plain
     * number in the currency's whole units ({@link MoneyText}), so a tenant writes {@code
     * {{amount}} {{currency}}} and decides the order and the spacing itself.
     */
    private static final List<Variable> ORDER_MONEY_VARIABLES = List.of(
            new Variable("orderNumber", "The order's own public number, e.g. \"A-1042\"."),
            new Variable("amount", "The order's total as a number in the currency's whole units, e.g. \"85000\"."),
            new Variable("currency", "The order's ISO currency code, e.g. \"UZS\"."));

    /** What an order message about a customer's own order may say beyond the three above (ADR 0184, D21). */
    private static final List<Variable> ORDER_DETAIL_VARIABLES = List.of(
            new Variable("restaurantName", "The brand's display name, e.g. \"Oqtepa Lavash\"."),
            new Variable(
                    "deliveryTime",
                    "The time the order was promised for, in the branch's own clock, e.g. \"19:45\"; \"—\" when no"
                            + " promise was made."),
            new Variable(
                    "preparationTime",
                    "The kitchen's promised preparation time in minutes, e.g. \"35\"; \"—\" when none was promised."),
            new Variable(
                    "productList",
                    "The order's items, e.g. \"2x Lavash, 1x Cola\"; with each price after it when the version's"
                            + " «show item prices» switch is on."),
            new Variable("discountAmount", "The discount on the order as a number, e.g. \"5000\"; \"0\" when none."),
            new Variable("deliveryFee", "The order's fee line as a number, e.g. \"12000\"; \"0\" when it has none."));

    /**
     * The customer's own name, for a message addressed to the customer. Personal, so it is resolved when
     * the message is rendered and written nowhere (D21); a customer with no name on file reads as a
     * neutral word in their language.
     */
    private static final Variable CUSTOMER_NAME =
            new Variable("customerName", "The customer's own name; a neutral word when they have given none.");

    /**
     * The first name of the courier carrying the order, for the «on the way» message and no other. Personal,
     * so it is resolved when the message is rendered and written nowhere (D21); an order with no in-house
     * courier reads as a neutral phrase in the customer's language. ADR 0045: the first name only, never a
     * surname and never a number.
     */
    private static final Variable COURIER_FIRST_NAME = new Variable(
            "courierFirstName",
            "The first name of the courier carrying the order (the on-the-way message only); a neutral phrase when"
                    + " none is known.");

    /** The one template key that may name {@code courierFirstName}: ADR 0045's handover milestone. */
    public static final String COURIER_NAME_TEMPLATE_KEY = "ORDER_FULFILLING";

    /**
     * Values that are about a person and are therefore resolved at render and never frozen onto a
     * notification row, a log line, an event or a hash input (D21, ADR 0029).
     */
    public static final Set<String> RESOLVED_AT_RENDER = Set.of("customerName", "courierFirstName");

    /**
     * Personal values this platform will not offer as merge variables, and why each is declined:
     * the customer's phone because the message is already sent to it and a number in the text is a
     * number on the customer's handset for anyone who sees it; the courier's phone because ADR 0045
     * decided customers do not get one; the courier's surname because ADR 0045 allows the first name
     * and no more.
     */
    public static final Set<String> DECLINED = Set.of("customerPhone", "courierPhone", "courierSurname");

    /**
     * {@code LOYALTY_POINTS_EXPIRING} (ADR 0046, ADR 0181): the message is about a customer's points and not an
     * order, so it carries these three typed values and no order variable. {@code LoyaltyExpiryWarningNotifier}
     * is the only writer, and renders with exactly these.
     */
    private static final List<Variable> LOYALTY_EXPIRY_VARIABLES = List.of(
            new Variable(
                    "loyaltyPoints",
                    "The points about to lapse, added together over the lots now inside their warning window, "
                            + "e.g. \"12 000\"."),
            new Variable(
                    "loyaltyExpiresOn",
                    "The date the earliest of those lots lapses, written day.month.year, e.g. \"28.10.2026\"."),
            new Variable("loyaltyDaysRemaining", "Whole days left until that earliest date, e.g. \"14\"."));

    private static final Map<NotificationClass, List<Variable>> BY_CLASS = buildCatalog();

    private static Map<NotificationClass, List<Variable>> buildCatalog() {
        Map<NotificationClass, List<Variable>> catalog = new EnumMap<>(NotificationClass.class);

        // OrderNotificationTrigger#reasonVariables: ORDER_REJECTED's own reason,
        // beside every order-derived class's orderNumber/amount/currency
        // (NotificationEligibilityService#variablesFor adds those three
        // automatically whenever the message names an order — declaring them
        // is what lets a template actually use them, not what produces them).
        catalog.put(
                NotificationClass.TRANSACTIONAL_REQUIRED,
                concat(
                        concat(
                                concat(ORDER_MONEY_VARIABLES, ORDER_DETAIL_VARIABLES.toArray(Variable[]::new)),
                                new Variable(
                                        "reasonCode",
                                        "The stable rejection reason code (ORDER_REJECTED only), "
                                                + "e.g. \"OUT_OF_STOCK\"."),
                                CUSTOMER_NAME),
                        LOYALTY_EXPIRY_VARIABLES));
        catalog.put(
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                concat(
                        concat(concat(ORDER_MONEY_VARIABLES, ORDER_DETAIL_VARIABLES.toArray(Variable[]::new)), CUSTOMER_NAME),
                        LOYALTY_EXPIRY_VARIABLES));

        // No fixed contract: a marketing message's variables are whatever the
        // campaign's own audience segment supplies (ADR 0044), which is
        // free-form per campaign rather than a name this module owns. These
        // two are offered as the common case, not an exhaustive list — an
        // author may declare any name a campaign actually provides.
        catalog.put(
                NotificationClass.MARKETING,
                List.of(
                        CUSTOMER_NAME,
                        new Variable("discountCode", "A campaign's own promo code, when the audience supplies one.")));

        // SECURITY has no entry: ADR 0184, D19. The one-time code sentence is platform-owned
        // (customers.api.OtpWording) and a tenant cannot author a SECURITY template, so there is
        // nothing to offer a variable for.

        // The union across every OPERATIONS_ALERT trigger that exists today.
        // Each is real and named: OrderNotificationTrigger (ORDER_AWAITING_APPROVAL,
        // order money above), ApprovalDeadlineWarningSweeper, FiscalOperationsAlertTrigger,
        // InventoryOperationsAlertTrigger, OnboardingStuckRunAlertSweeper,
        // CommercialArrearsReviewSweeper. A single wording never uses all of
        // them; TemplateRenderer.validate still refuses whichever this
        // template does not actually use to be declared — this list only
        // says what is available to pick from.
        catalog.put(
                NotificationClass.OPERATIONS_ALERT,
                concat(
                        ORDER_MONEY_VARIABLES,
                        new Variable("reasonCode", "A stable reason code (rejection, fiscal block, stock-out)."),
                        new Variable("itemName", "The affected catalog item's name (stock-out alerts)."),
                        new Variable(
                                "approvalDeadlineAt", "When the pending order's approval window closes, ISO-8601."),
                        new Variable("runId", "The affected onboarding run's own id."),
                        new Variable("tenantId", "The tenant the alert is about (control-plane audience alerts)."),
                        new Variable("subscriptionId", "The affected commercial subscription's own id."),
                        // ReviewsOperationsAlertTrigger (ADR 0159): a service-recovery case, by id only.
                        new Variable("caseId", "The service-recovery case's own id."),
                        new Variable("clock", "Which clock a case passed: FIRST_RESPONSE or RESOLUTION."),
                        new Variable("dueAt", "When the clock was due, ISO-8601."),
                        // ReviewsOperationsAlertTrigger (ADR 0187): a submitted review, by id and stars only.
                        new Variable("reviewId", "The review's own id."),
                        new Variable("rating", "The stars the customer gave, 1 to 5."),
                        // LoyaltyOperationsAlertTrigger (ADR 0171): a till-sync difference, by ids and kind only.
                        new Variable("bindingId", "The point-of-sale binding the difference was found on."),
                        new Variable("discrepancyId", "The first difference of this kind found today."),
                        new Variable(
                                "discrepancyKind",
                                "Which difference: TILL_HIGHER, TILL_LOWER, SPEND_EXCEEDS_BALANCE, CREDIT_HELD, "
                                        + "AMBIGUOUS_MATCH, RATIO_MISMATCH, NOT_CONSENTED or UNVERIFIABLE.")));

        // ADR 0182, D15: a dispatcher's broadcast to couriers names an approved wording and supplies
        // these. Short operational values typed at send time, not data from an event: the wording is
        // what the gateway approves, and these three are the places it leaves for the dispatcher.
        catalog.put(
                NotificationClass.COURIER_DISPATCH,
                List.of(
                        new Variable("place", "Where it applies: a branch, a zone, a street."),
                        new Variable("time", "When it applies, as the dispatcher writes it, e.g. \"18:00\"."),
                        new Variable("note", "A short note from the dispatcher: what changed or what to do.")));

        return Map.copyOf(catalog);
    }

    private static List<Variable> concat(List<Variable> base, Variable... extra) {
        return java.util.stream.Stream.concat(base.stream(), java.util.Arrays.stream(extra))
                .toList();
    }

    private static List<Variable> concat(List<Variable> base, List<Variable> extra) {
        return java.util.stream.Stream.concat(base.stream(), extra.stream()).toList();
    }

    /** Every variable an author may declare for this class, in a stable order. */
    public static List<Variable> forClass(NotificationClass notificationClass) {
        return BY_CLASS.getOrDefault(notificationClass, List.of());
    }

    /**
     * The variables for one template key of one class: the class's own, plus the one that only a key
     * may have ({@code courierFirstName} on {@link #COURIER_NAME_TEMPLATE_KEY}, D21).
     */
    public static List<Variable> forTemplate(NotificationClass notificationClass, String templateKey) {
        List<Variable> base = forClass(notificationClass);
        if (permitsCourierFirstName(notificationClass, templateKey)) {
            return concat(base, COURIER_FIRST_NAME);
        }
        return base;
    }

    /** The whole catalogue, one entry per class that has any variables at all. */
    public static Map<NotificationClass, List<Variable>> all() {
        return BY_CLASS;
    }

    /**
     * Whether a message of this class is addressed to a customer: the classes that may say the
     * customer's name and the classes that hold a data subject in ADR 0015's sense.
     */
    public static boolean isCustomerAddressed(NotificationClass notificationClass) {
        return switch (notificationClass) {
            case TRANSACTIONAL_REQUIRED, TRANSACTIONAL_OPTIONAL, MARKETING, SECURITY -> true;
            case OPERATIONS_ALERT -> false;
        };
    }

    private static boolean permitsCourierFirstName(NotificationClass notificationClass, String templateKey) {
        return COURIER_NAME_TEMPLATE_KEY.equals(templateKey)
                && (notificationClass == NotificationClass.TRANSACTIONAL_OPTIONAL
                        || notificationClass == NotificationClass.TRANSACTIONAL_REQUIRED);
    }

    /**
     * Why a version that declares these variables must not be saved, or empty when none of them is a
     * personal value in the wrong place (D21). Checked when a draft is authored, beside the check that
     * a placeholder is declared: a refusal while somebody is writing, not an empty hole at send time.
     *
     * @return one sentence naming the first offender, never the text of the wording
     */
    public static Optional<String> refusal(
            NotificationClass notificationClass, String templateKey, Set<String> declared) {
        for (String name : declared.stream().sorted().toList()) {
            if (DECLINED.contains(name)) {
                return Optional.of(
                        "%s is not offered as a merge variable: a phone number or a courier's surname does not"
                                        .formatted(name)
                                + " go into a customer's message (ADR 0184, D21; ADR 0045).");
            }
            if ("customerName".equals(name) && !isCustomerAddressed(notificationClass)) {
                return Optional.of("customerName can only be used in a message addressed to the customer;"
                        + " a %s message goes to a group (ADR 0184, D21; ADR 0058).".formatted(notificationClass));
            }
            if ("courierFirstName".equals(name) && !permitsCourierFirstName(notificationClass, templateKey)) {
                return Optional.of("courierFirstName can only be used in the %s message (ADR 0184, D21; ADR 0045)."
                        .formatted(COURIER_NAME_TEMPLATE_KEY));
            }
        }
        return Optional.empty();
    }
}
