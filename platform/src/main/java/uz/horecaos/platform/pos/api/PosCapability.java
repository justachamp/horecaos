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
 * <p>The first six are ADR 0011's original ports. The last four were found while
 * reading a real POS API and are declared here rather than left as provider
 * quirks, because each one is something a domain module will eventually want and
 * none of them is Clopos-specific in principle.
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
    CUSTOMER_UPSERT;

    /** The string stored in the database. Identical to the constant name by design. */
    public String code() {
        return name();
    }
}
