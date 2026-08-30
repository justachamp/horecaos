package uz.qoida.platform.fulfillment.domain.zone;

/**
 * What a zone decides (ADR 0037).
 *
 * <p>Two roles on one entity rather than Delever's three overlapping geometry
 * layers. A "free geozone" is not a third role: it is a {@code DELIVERY} zone
 * whose tariff resolves to zero, and expressing it as a layer is what left three
 * layers with no documented interaction.
 */
public enum ZoneRole {

    /** Whether an address may be delivered to, and at what price. Carries a tariff. */
    DELIVERY,

    /**
     * Which locations are candidates, and the branch containment guard. Carries no
     * tariff, enforced by {@code ck_zone_version_catchment_is_not_priced}: two
     * rate tables for one address with nothing saying which wins is the ambiguity
     * this ADR closes.
     */
    CATCHMENT
}
