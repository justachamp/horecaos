package uz.horecaos.platform.commercial.domain;

/**
 * What a module is billed per (ADR 0087).
 *
 * <p>Counted at the end of the month a statement covers, from the ledger the
 * platform already keeps, so a statement issued twice for the same month
 * reads the same quantity.
 */
public enum BillingUnit {

    /** Once per tenant per month. */
    PER_TENANT,

    /** Each brand the tenant had at the end of the month. */
    PER_BRAND,

    /** Each branch the tenant had at the end of the month; a kitchen display is sold this way. */
    PER_LOCATION,

    /** A quantity agreed when the module was added: kiosks, a courier service. */
    PER_UNIT,

    /** Charged once, in the month the module was added: a white-label app. */
    ONE_OFF
}
