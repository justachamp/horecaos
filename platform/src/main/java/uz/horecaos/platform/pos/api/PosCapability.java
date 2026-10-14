package uz.horecaos.platform.pos.api;

/**
 * What a point of sale can be asked to do (ADR 0011).
 *
 * <p>Capability codes rather than provider names, for the reason ADR 0011 gives:
 * a branch on "is this Clopos?" inside ordering or catalog makes every new till
 * an edit to core commerce code. Ordering asks for a binding that has
 * {@link #ORDER_EXPORT} and gets one, or gets none and takes the manual path.
 *
 * <p>The enum constant name is the capability code stored in
 * {@code integration.binding_capabilities.capability_code} and in
 * {@code integration.pos_provider_capabilities}. They are the same string on
 * purpose: a second spelling in the database is how a capability comes to be
 * enabled under one name and assessed under another.
 *
 * <p>The first six are ADR 0011's original ports. The next four were found while
 * reading a real POS API and are declared here rather than left as provider
 * quirks, because each one is something a domain module will eventually want and
 * none of them is Clopos-specific in principle. The eleventh, {@link #LOYALTY_BALANCE_SYNC},
 * is ADR 0171's, and the twelfth, {@link #ORDER_OPERATOR_ATTRIBUTION}, is gap-map row 9.2c's.
 */
public enum PosCapability {

    /** Read the provider's menu structure: products, variants, modifiers, categories. */
    CATALOG_READ,

    /** Read what the provider says is out of stock or limited. */
    AVAILABILITY_READ,

    /**
     * The provider decides whether an order is accepted.
     *
     * <p>A real authority where it is supported, and worth being precise about:
     * this is the till accepting or refusing, not the till being told. Where the
     * provider offers no push, the decision arrives one poll interval late, and
     * ADR 0011's "first valid approval wins" is then decided by our polling
     * cadence rather than by who decided first.
     */
    ORDER_APPROVAL,

    /** Send a confirmed HorecaOS order to the provider. */
    ORDER_EXPORT,

    /** Withdraw an exported order at the provider. */
    ORDER_CANCELLATION,

    /**
     * The provider reports its kitchen's progress back to us.
     *
     * <p>Declared so that a provider which genuinely has it can say so, and
     * refused for one that does not. The test is strict and it is the whole
     * value of the capability: the provider must <em>report</em> preparation. A
     * field we write and can read back again is not a report, and presenting one
     * to a branch manager as though it were would be a screen that invents its
     * own contents.
     */
    PREPARATION_STATUS,

    /** Read closed receipts and the stock movements they caused. Feeds ADR 0043. */
    RECEIPT_READ,

    /**
     * Write back the fiscal identifier some other system issued (ADR 0038).
     *
     * <p>Not fiscalisation. HorecaOS's {@code fiscal} module discharges the
     * obligation through a payment provider; this closes the loop so the
     * restaurant's own POS reporting reconciles against the receipt that was
     * actually issued.
     */
    FISCAL_IDENTIFIER_WRITE_BACK,

    /**
     * Tell the provider where an order has got to.
     *
     * <p>The mirror image of {@link #PREPARATION_STATUS} and deliberately a
     * separate capability. This one is outbound telemetry for the restaurant's
     * benefit; reading it back and calling it kitchen progress is the confusion
     * these two names exist to prevent.
     */
    FULFILLMENT_STATUS_WRITE,

    /**
     * Create or find the provider's own customer record.
     *
     * <p>An ADR 0029 personal-data flow rather than a convenience: exporting a
     * customer's phone and address to a third-party system needs a consent basis,
     * and having an endpoint for it is not one.
     */
    CUSTOMER_UPSERT,

    /**
     * Keep a till's customer bonus balance equal to the platform's loyalty ledger (ADR 0171, row 6.3b).
     *
     * <p>The platform's append-only ledger is the one authority for every balance it issues; the
     * till's number is a mirror. This capability is what lets an adapter list the till's customers
     * (so each can be found by the phone the till returns, and nothing about the customer is sent to
     * the till), read a balance, list the movements that happened at the till since a cursor, and
     * write the platform's balance to the till's own customer id under an idempotency key. Its
     * sub-properties are recorded as data on the ceiling row (<em>limits</em>): a vendor that can write
     * a balance and cannot read movements is PARTIAL, and the rationale names the missing half.
     *
     * <p>Every vendor starts unassessed, and an unassessed capability cannot be enabled on a binding
     * (the trigger refuses it). That includes Clopos, whose notes record no balance field, iiko and
     * R-Keeper, about which nothing in this repository says what either exposes for a wallet.
     */
    LOYALTY_BALANCE_SYNC,

    /**
     * The till's create-order call carries <em>which colleague</em> took the order, and the adapter
     * puts {@code OrderExport#operatorExternalId} on the wire (gap-map row {@code 9.2c}, ADR 0139).
     *
     * <p>The pairing between a HorecaOS staff member and the till's own operator id is written from
     * the console (entity type {@code OPERATOR}) and resolved off {@code accepted_by_actor_id} on the
     * next accepted order. That is a writer and a reader. This capability is the sender, and it is a
     * separate statement about the vendor because the other two are about us: a till whose documented
     * order API has no waiter, cashier or user field cannot be handed one, and an adapter that sent an
     * undocumented field anyway would be guessing on a vendor that has already punished a guess
     * ({@code product_hash}). So the capability is declared only by an adapter whose vendor documents
     * the field, and the order-export path hands an operator id <em>only</em> to an adapter that
     * declares it.
     *
     * <p>Clopos does not declare it: {@code CreateOrderRequest} names {@code sale_type_id},
     * {@code venue_id}, {@code customer} and {@code products}, and every call acts on behalf of the
     * credential's Staff user (docs/providers/clopos-api.md section 6.5). A vendor whose API carries
     * the operator is an external fact (docs/runbooks/external-connections/w13-staff-access.md); the
     * controlled fake declares it so the contract can be proved against something that does.
     */
    ORDER_OPERATOR_ATTRIBUTION;

    /** The string stored in the database. Identical to the constant name by design. */
    public String code() {
        return name();
    }
}
