/**
 * What fulfillment exposes to other modules: a resolved delivery charge and the
 * reasons one could not be produced, and nothing of the zones, the geometry, or
 * the rate tables that produced it (ADR 0037).
 *
 * <p>Pricing consumes {@link uz.qoida.platform.fulfillment.api.DeliveryFeePort}
 * and never these tables directly. If it could join them, a zone edit would
 * change what a past quote recomputes, which is the property the whole versioning
 * scheme exists to prevent.
 *
 * <p>The two sourcing ports point the other way (ADR 0014). Fulfilment declares
 * {@link uz.qoida.platform.fulfillment.api.InternalFleetPort} and
 * {@link uz.qoida.platform.fulfillment.api.ShipmentBookingPort} and other
 * modules implement them, which is what keeps sourcing free of both Camel and
 * ADR 0042's workforce model — and, for the booking port specifically, is the
 * only direction that does not close fulfillment and integration into a cycle.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.qoida.platform.fulfillment.api;
