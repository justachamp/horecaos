package uz.horecaos.platform.partner.domain;

/**
 * Who paid for the discount on an externally priced order (ADR 0040).
 *
 * <p>{@link #UNKNOWN} is the honest default and the frequent answer: most
 * partner protocols do not say. Defaulting to {@link #MERCHANT} would put the
 * cost of an aggregator's own campaign on the restaurant's profit and loss, and
 * defaulting to {@link #PARTNER} would hide a real cost the restaurant is
 * carrying. Neither error is discoverable from the order.
 */
public enum DiscountFunding {

    PARTNER,
    MERCHANT,
    SPLIT,
    UNKNOWN
}
