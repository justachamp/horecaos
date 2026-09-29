/**
 * What dine-in exposes to other modules: the table an order was eaten at, and
 * nothing of the session, the bill or the floor plan that produced it (ADR 0047).
 *
 * <p>Ordering and the kitchen consume {@link uz.horecaos.platform.dinein.api.OrderTablesPort}
 * to label an order with its table; neither may join {@code dinein.*} itself.
 * The dependency stays one way at the Java level -- dine-in imports nothing from
 * either -- which is what keeps {@code ModularArchitectureTests} free of a cycle
 * and keeps a DINE_IN order identical to every other order everywhere else
 * (pricing, inventory, fiscal treatment, audit, reporting).
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.dinein.api;
