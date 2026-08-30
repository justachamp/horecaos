package uz.horecaos.platform.partner.domain;

/**
 * How an order row came to exist (ADR 0040).
 *
 * <p>The distinction that matters is evidential rather than operational. A
 * pushed order's total is a partner's claim that HorecaOS verified arithmetically;
 * a keyed order's total is a number a human typed and the platform cannot verify
 * at all. Both are legitimate; a report that cannot tell them apart is not.
 */
public enum EntryMode {

    /** A storefront checkout or a partner push. */
    API,

    /**
     * An operator keyed it in — for an unintegrated partner, or to recover a
     * failed sync. Always carries an ADR 0027 audit fact naming the operator.
     */
    MANUAL,

    /** A migration wrote it (ADR 0024). */
    IMPORT
}
