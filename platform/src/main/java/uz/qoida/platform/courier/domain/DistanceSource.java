package uz.qoida.platform.courier.domain;

/**
 * Where the distance a courier is paid for came from (ADR 0042).
 *
 * <p>None of these is the GPS track. The track pays for detours, for circling
 * the block, and for drift in Tashkent's courtyards, and neither party can see
 * the figure before the trip. ADR 0045 owns the track; it is evidence, it may
 * trigger a review, and it never pays.
 */
public enum DistanceSource {

    ROUTING,
    HAVERSINE_FACTORED,
    MANUAL
}
