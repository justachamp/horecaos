package uz.horecaos.platform.tenancy.api.geo;

/**
 * Why a lookup produced no answer at all, as opposed to an answer of "nothing found"
 * (ADR 0145 decision 6).
 *
 * <p>The distinction is the screen's whole honesty: "no addresses match" and "address search
 * is not set up" are opposite facts, and a person who is told the first when the second is
 * true retypes the address until they give up. Closed and bounded, so it is also a safe
 * metric label and a safe response field.
 */
public enum GeoUnavailableReason {
    /** No provider key is configured in this environment. Nothing works, and says so. */
    NOT_CONFIGURED,
    /** The provider refused us: a key that is wrong, expired or out of quota. Retrying changes nothing. */
    PROVIDER_REFUSED,
    /** A timeout, a server fault, an unreadable answer or an open circuit. Try again later. */
    PROVIDER_UNAVAILABLE
}
