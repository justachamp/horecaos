package uz.qoida.platform.tenancy.domain;

/**
 * How a branch came to have — or not have — a point on the map.
 *
 * <p>The same discipline ADR 0015 applies to customer addresses, with two of that
 * vocabulary's values deliberately missing.
 *
 * <p>There is no {@code LANDMARK_ONLY}. A customer's address may legitimately
 * have no coordinate: a mahalla house given by its ориентир is a finished state,
 * and dispatch reaches it by telephone. A branch is not that. It has a door that
 * a human can point at on a map, and a branch without a point cannot originate an
 * ADR 0037 delivery zone or be sourced against by distance. Offering a "finished,
 * no point" state here would let a location opt out of being locatable while
 * presenting as fully configured — and then quietly excluding itself from
 * delivery for a reason nobody could see.
 *
 * <p>There is no {@code CUSTOMER_PIN} either, for the obvious reason.
 */
public enum CoordinateSource {

    /** Not attempted, or attempted and failed. Retryable, and worth retrying. */
    NOT_GEOCODED,

    /** Resolved by the ADR 0015 geocoding port. */
    GEOCODER,

    /** The tenant placed their own pin, usually during onboarding. */
    MERCHANT_PIN,

    /** Qoida support placed it, usually while on the phone to the merchant. */
    OPERATOR_PIN;

    /** Whether a branch in this state still owes the platform a coordinate. */
    public boolean awaitingCoordinates() {
        return this == NOT_GEOCODED;
    }
}
