package uz.qoida.platform.fulfillment.domain.sourcing;

/**
 * ADR 0014's closed list of reasons a delivery needs a human.
 *
 * <p>A closed list because an exception screen filtered by free text is one
 * nobody filters, which is the same argument the migration's own comment on
 * {@code delivery_exceptions.reason_code} makes.
 *
 * <p>Strings rather than an enum for the reason {@link SourcingDecision}'s codes
 * are: they travel into a metric tag and an operations screen, where a value that
 * survives a refactor of this file is worth more than an exhaustive switch.
 */
public final class DeliveryExceptionReason {

    /** No partner is configured, or every configured one refused. */
    public static final String NO_PROVIDER = "NO_PROVIDER";

    /** Nothing could be assigned before {@code latest_assignment_at}. */
    public static final String LATE_ASSIGNMENT = "LATE_ASSIGNMENT";

    /** The promise cannot be met by any remaining automated move. */
    public static final String PROMISE_UNREACHABLE = "PROMISE_UNREACHABLE";

    /** A partner may or may not have accepted, and a query could not settle it. */
    public static final String AWAITING_RECONCILIATION = "AWAITING_RECONCILIATION";

    /** A hold exists at a partner that nothing has confirmed or cancelled. */
    public static final String ABANDONED_HOLD = "ABANDONED_HOLD";

    public static final String LATE_RESTAURANT = "LATE_RESTAURANT";
    public static final String COURIER_NO_SHOW = "COURIER_NO_SHOW";
    public static final String ADDRESS_ISSUE = "ADDRESS_ISSUE";

    private DeliveryExceptionReason() {
    }

    /**
     * The exception a sourcing decision's reason code becomes.
     *
     * <p>Nine sourcing reasons collapse onto four exception reasons on purpose:
     * the reason code stays on the attempt as the fine-grained evidence, and the
     * exception row is the coarse thing an operator filters a shift's work by.
     */
    public static String forDecision(String decisionReason) {
        return switch (decisionReason) {
            case SourcingDecision.PROMISE_UNREACHABLE -> PROMISE_UNREACHABLE;
            case SourcingDecision.AWAITING_RECONCILIATION -> AWAITING_RECONCILIATION;
            case SourcingDecision.NO_PARTNER_CONFIGURED,
                 SourcingDecision.PARTNERS_EXHAUSTED,
                 SourcingDecision.NO_INTERNAL_CANDIDATE,
                 SourcingDecision.FLEET_DECLINED -> NO_PROVIDER;
            // FLEET_BUDGET_SPENT, MANUAL_MODE and anything added later: the plan
            // still has clock left but no automated move, which is the case an
            // operator can actually rescue by assigning somebody by hand.
            default -> LATE_ASSIGNMENT;
        };
    }
}
