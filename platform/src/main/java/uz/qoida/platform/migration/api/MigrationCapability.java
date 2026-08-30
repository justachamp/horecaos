package uz.qoida.platform.migration.api;

/**
 * The unit that changes hands (ADR 0024).
 *
 * <p>A capability, not a table and not a module. ADR 0024 rejected table-by-table
 * migration because ownership that stops halfway through a journey leaves two
 * writers and nothing to roll back as a whole, so the grain here is the coherent
 * slice of behaviour a tenant would recognise as working or not working.
 *
 * <p>The constants are ordered the way the migration waves depend on each other:
 * tenants and identity before the things they own, media and catalog before the
 * prices and offerings that reference them, historical orders and payments
 * before the live journeys that continue them. The order is documentation, not a
 * constraint. Which capability may cut over next is a fact about a program's
 * recorded dependencies, and encoding it here would make it look decidable
 * without reading them.
 */
public enum MigrationCapability {

    /** Tenants, brands, locations, plans, entitlements, domains, and onboarding. */
    TENANCY,

    /** Keycloak subjects, principal links, and memberships (ADR 0015). */
    IDENTITY,

    /** Customer records, consent, addresses, and devices. */
    CUSTOMERS,

    /** Media assets and their storage objects (ADR 0010). */
    MEDIA,

    /** Catalog content, merchandising decisions, and publications. */
    CATALOG,

    /** Balances, reservations, and the movement ledger. */
    INVENTORY,

    /** Prices, promotions, and tax rules. */
    PRICING,

    /** Carts, orders, and order snapshots. */
    ORDERS,

    /** Payments, refunds, and fiscal receipts. */
    PAYMENTS,

    /** Delivery, courier facts, and POS export. */
    FULFILLMENT,

    /** Templates, subscriptions, and delivery history. */
    NOTIFICATIONS,

    /** Tenant configuration and policy versions. */
    CONFIGURATION,

    /** Reports, audit history, and privacy operations. */
    REPORTING
}
