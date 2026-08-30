package uz.horecaos.platform.fulfillment.domain.tariff;

/**
 * What actually produced the distance on the row, as opposed to what the tariff
 * asked for (ADR 0037).
 *
 * <p>Separate from {@link DistanceMode} because the interesting case is exactly
 * the one where they differ. A fee that cannot be re-derived from a recorded
 * distance is a fee nobody can defend to a tenant, and "we charged road prices
 * for a straight line" is a thing a tenant is entitled to find out from the row
 * rather than from a courier.
 */
public enum DistanceSource {

    RADIUS,
    ROAD,

    /**
     * Routing was asked and did not answer in time, so straight-line distance was
     * multiplied by the tariff's detour factor. The quote is never failed for
     * this — a customer cannot check out because a routing provider is slow is a
     * worse outcome than a slightly wrong fee — and a metric is incremented so
     * the frequency is visible.
     */
    RADIUS_FALLBACK
}
