/**
 * Kitchen execution: production stations, routing, tickets, and release.
 *
 * <p>A module of its own rather than a corner of {@code fulfillment}, and the
 * reason is a real one rather than taxonomy. Fulfillment is about getting food
 * that already exists to somebody — couriers, partners, zones, tracking. The
 * kitchen is about the food coming into existence, which happens before any of
 * that and matters just as much for a pickup order that fulfillment never
 * touches.
 *
 * <p>Placing it inside fulfillment also produced a dependency cycle, which is
 * the kind of evidence worth listening to. ADR 0037 made pricing depend on
 * fulfillment so a quote can carry a delivery fee; the kitchen depends on
 * ordering so a confirmed order opens a ticket; and ordering depends on pricing.
 * Fulfillment and the kitchen only belonged in one module if they shared a
 * reason to change, and the cycle is what happens when two things that do not
 * are filed together.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Kitchen")
package uz.horecaos.platform.kitchen;
