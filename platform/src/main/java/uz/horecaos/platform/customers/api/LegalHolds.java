package uz.horecaos.platform.customers.api;

/**
 * How a retention job leaves a customer alone while a legal hold stands on her (ADR 0029: "legal hold" suspends expiry).
 *
 * <p>A retention job that deletes rows by age is a batch statement; it does not load a customer to ask whether she is
 * held. So the question is a SQL predicate the job adds to the statement it already runs, and this class is the one place
 * that spells it, so no job writes its own reading of {@code customer.legal_holds}. A hold is a row with {@code status
 * 'ACTIVE'}; the predicate is true for a row whose customer is not held, and for a row with no customer at all (a guest's
 * cart), since a null never matches.
 *
 * <p>Only jobs that expire a customer's data by age carry it: abandoned carts, conversation history, audience
 * memberships. The erasure itself is refused by {@code CustomerErasureService} while a hold stands.
 */
public final class LegalHolds {

    private LegalHolds() {}

    /**
     * A predicate that is true when the customer named by the two column expressions is not under a legal hold.
     *
     * @param tenantColumn   the tenant id column of the table being swept, qualified ({@code c.tenant_id})
     * @param customerColumn the customer account id column, qualified ({@code c.customer_account_id})
     */
    public static String notHeld(String tenantColumn, String customerColumn) {
        return "NOT EXISTS (SELECT 1 FROM customer.legal_holds hold WHERE hold.tenant_id = " + tenantColumn
                + " AND hold.customer_account_id = " + customerColumn + " AND hold.status = 'ACTIVE')";
    }
}
