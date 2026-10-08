package uz.horecaos.platform.fulfillment.api;

/**
 * One measured driving route, attributed to the provider and the dataset that
 * produced it (ADR 0147).
 *
 * <p>The dataset version is part of the answer, and not a separate lookup, because
 * a road figure is only reproducible against the map that made it: the same two
 * points measure a few hundred metres differently after a monthly refresh, and a
 * band edge turns that into a price. The fee evidence stores it beside the metres.
 *
 * @param meters         the road distance, rounded to the nearest metre
 * @param seconds        the engine's free-flow estimate of the travel time. It is
 *                       the speed profile's figure for an empty road and says
 *                       nothing about rush hour, so any consumer that promises a
 *                       customer a time from it must say so (ADR 0147, decision 2)
 * @param provider       the adapter's own name, stored so a bad calibration can be
 *                       traced to it
 * @param datasetVersion the routing dataset's tag, normally its extract date
 */
public record RoadRoute(int meters, int seconds, String provider, String datasetVersion) {

    public RoadRoute {
        if (meters < 0) {
            throw new IllegalArgumentException("A road distance cannot be negative: " + meters);
        }
        if (seconds < 0) {
            throw new IllegalArgumentException("A travel time cannot be negative: " + seconds);
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("A road route must name the provider that measured it");
        }
        if (datasetVersion == null || datasetVersion.isBlank()) {
            throw new IllegalArgumentException("A road route must name the dataset that measured it");
        }
    }
}
