package uz.qoida.platform.ordering.domain;

import java.util.EnumSet;
import java.util.Set;

import uz.qoida.platform.tenancy.api.FulfillmentMode;

/**
 * The closed, platform-owned category every outcome carries (ADR 0039).
 *
 * <p>Tenant reason registries drift into dozens of near-duplicates — «Не
 * дозвонились», «Клиент не отвечает», «Нет ответа» are three rows describing one
 * thing — and that is not a defect to be prevented but a fact to be contained.
 * The category is what cross-tenant reporting groups by; the tenant's own wording
 * is only what the operator picks from.
 *
 * <p>Code-owned and closed for the same reason {@link OrderStatus} is: a
 * tenant-defined category would be a bucket no report knows how to place.
 */
public enum OutcomeSystemCategory {

    // ------------------------------------------------------------ cancellation

    CUSTOMER_CANCELLED(OutcomeReasonKind.CANCELLATION),
    CUSTOMER_UNREACHABLE(OutcomeReasonKind.CANCELLATION),
    CUSTOMER_NO_SHOW(OutcomeReasonKind.CANCELLATION),
    RESTAURANT_REFUSED(OutcomeReasonKind.CANCELLATION),
    ITEM_UNAVAILABLE(OutcomeReasonKind.CANCELLATION),
    KITCHEN_CAPACITY(OutcomeReasonKind.CANCELLATION),
    DELIVERY_FAILED(OutcomeReasonKind.CANCELLATION),
    COURIER_UNAVAILABLE(OutcomeReasonKind.CANCELLATION),
    ADDRESS_UNSERVICEABLE(OutcomeReasonKind.CANCELLATION),
    PAYMENT_NOT_RECEIVED(OutcomeReasonKind.CANCELLATION),
    DUPLICATE_ORDER(OutcomeReasonKind.CANCELLATION),
    TEST_ORDER(OutcomeReasonKind.CANCELLATION),
    SUSPECTED_FRAUD(OutcomeReasonKind.CANCELLATION),
    PRICING_ERROR(OutcomeReasonKind.CANCELLATION),

    // -------------------------------------------------------------- completion

    /** Our own courier delivered it. The courier SLA report counts these. */
    DELIVERED_OWN_COURIER(OutcomeReasonKind.COMPLETION),

    /**
     * A third-party service delivered it.
     *
     * <p>Separate from {@link #DELIVERED_OWN_COURIER} because the
     * external-logistics settlement report is built entirely on the distinction,
     * and an order ending {@code COMPLETED} with nothing else recorded cannot say
     * whether a courier was owed for it.
     */
    DELIVERED_PARTNER_COURIER(OutcomeReasonKind.COMPLETION),

    COLLECTED_BY_CUSTOMER(OutcomeReasonKind.COMPLETION),
    SERVED_IN_HOUSE(OutcomeReasonKind.COMPLETION),

    // ------------------------------------------------------- system-only facts

    /**
     * Nobody answered in time. Not a cancellation and not a rejection: "the
     * restaurant declined" and "the restaurant never looked" are different facts
     * with different branch metrics, and no operator picks this one.
     */
    APPROVAL_DEADLINE_LAPSED(null),

    /** Neither registry offers this; it is what an unmapped tenant reason lands on. */
    OTHER(null);

    private final OutcomeReasonKind reasonKind;

    OutcomeSystemCategory(OutcomeReasonKind reasonKind) {
        this.reasonKind = reasonKind;
    }

    /**
     * Whether a tenant may register a reason of this kind under this category.
     *
     * <p>{@link #OTHER} is allowed under both kinds: it is the honest landing
     * place for a reason that fits nothing, and refusing it would push tenants
     * into mis-categorising instead.
     */
    public boolean availableFor(OutcomeReasonKind kind) {
        if (this == OTHER) {
            return true;
        }
        return reasonKind == kind;
    }

    /** The categories a tenant may choose from for this kind. */
    public static Set<OutcomeSystemCategory> selectableFor(OutcomeReasonKind kind) {
        EnumSet<OutcomeSystemCategory> selectable = EnumSet.noneOf(OutcomeSystemCategory.class);
        for (OutcomeSystemCategory category : values()) {
            if (category.availableFor(kind)) {
                selectable.add(category);
            }
        }
        return selectable;
    }

    /**
     * The completion category an order of this mode ends in when nobody picked a
     * reason.
     *
     * <p>ADR 0039: where exactly one reason is valid for the order's mode, the
     * action completes without a dialog. An operator confirming «Доставлен» on
     * every delivery three hundred times a shift is a dialog that teaches people
     * to click through dialogs.
     *
     * <p>A delivery defaults to our own courier rather than a partner's, because
     * that is the only one Qoida can observe today; a partner delivery is a
     * reason an operator picks deliberately, and inventing it here would put
     * orders into the external-logistics settlement that nobody was billed for.
     */
    public static OutcomeSystemCategory defaultCompletionFor(FulfillmentMode mode) {
        return switch (mode) {
            case DELIVERY -> DELIVERED_OWN_COURIER;
            case PICKUP -> COLLECTED_BY_CUSTOMER;
            case DINE_IN -> SERVED_IN_HOUSE;
        };
    }
}
