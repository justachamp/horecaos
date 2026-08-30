package uz.horecaos.platform.fulfillment.domain.tariff;

/** How far the address is, per tariff (ADR 0037). */
public enum DistanceMode {

    /** Great-circle from the branch. Needs no provider and cannot time out. */
    RADIUS,

    /**
     * Routed distance from a provider under ADR 0026. Refused at activation
     * without a routing binding, and never silently degraded: a timeout produces
     * a {@link DistanceSource#RADIUS_FALLBACK} fee that says so.
     */
    ROAD
}
