package uz.horecaos.platform.courier.domain;

/**
 * When a customer's exact drop-off location is revealed to the assigned
 * courier (ADR 0042, gap map row {@code 10.13}, couriers.md §16).
 *
 * <p>{@code AFTER_ACCEPT} is the conservative default: a courier who has not
 * yet committed to a delivery has no operational need for the customer's
 * exact address, and revealing it earlier hands the same detail to every
 * courier who glances at an offer and declines it.
 */
public enum RevealTiming {

    /** The exact address is visible while the offer is still being decided. */
    BEFORE_ACCEPT,

    /** The exact address appears only once the courier has accepted the assignment. */
    AFTER_ACCEPT
}
