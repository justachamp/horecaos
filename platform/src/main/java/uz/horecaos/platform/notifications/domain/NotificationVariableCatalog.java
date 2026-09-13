package uz.horecaos.platform.notifications.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 */
public final class NotificationVariableCatalog {

    private NotificationVariableCatalog() {}

    /** One variable an author may declare and insert, with what it means. */
    public record Variable(String name, String description) {}

    private static final List<Variable> ORDER_MONEY_VARIABLES = List.of(
            new Variable("orderNumber", "The order's own public number, e.g. \"A-1042\"."),
            new Variable("amount", "The order's total, formatted with its currency, e.g. \"85 000 UZS\"."),
            new Variable("currency", "The order's ISO currency code, e.g. \"UZS\"."));

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
                        ORDER_MONEY_VARIABLES,
                        new Variable(
                                "reasonCode",
                                "The stable rejection reason code (ORDER_REJECTED only), "
                                        + "e.g. \"OUT_OF_STOCK\".")));
        catalog.put(NotificationClass.TRANSACTIONAL_OPTIONAL, ORDER_MONEY_VARIABLES);

        // No fixed contract: a marketing message's variables are whatever the
        // campaign's own audience segment supplies (ADR 0044), which is
        // free-form per campaign rather than a name this module owns. These
        // two are offered as the common case, not an exhaustive list — an
        // author may declare any name a campaign actually provides.
        catalog.put(
                NotificationClass.MARKETING,
                List.of(
                        new Variable(
                                "customerName", "The customer's own display name, when the audience supplies one."),
                        new Variable("discountCode", "A campaign's own promo code, when the audience supplies one.")));

        // SECURITY has no trigger in this build (OTP delivery rides its own
        // VerificationCodeTransport port, not this template model) — "code" is
        // offered ahead of a real caller because a tenant that types a wording
        // for a future account-security notice must be able to name the one
        // variable every such message needs.
        catalog.put(
                NotificationClass.SECURITY,
                List.of(new Variable("code", "A one-time or verification code the message is about.")));

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
                        new Variable("subscriptionId", "The affected commercial subscription's own id.")));

        return Map.copyOf(catalog);
    }

    private static List<Variable> concat(List<Variable> base, Variable... extra) {
        return java.util.stream.Stream.concat(base.stream(), java.util.Arrays.stream(extra))
                .toList();
    }

    /** Every variable an author may declare for this class, in a stable order. */
    public static List<Variable> forClass(NotificationClass notificationClass) {
        return BY_CLASS.getOrDefault(notificationClass, List.of());
    }

    /** The whole catalogue, one entry per class that has any variables at all. */
    public static Map<NotificationClass, List<Variable>> all() {
        return BY_CLASS;
    }
}
